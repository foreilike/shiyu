package com.shiyu.app.core.lang.ja

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScenarioParser（scenarios_*.json 解析）。
 *
 * 补测的缘由（2026-09-23 全盘审计）：这个类被 [JaModule] 与 [EnModule] **共用**
 * （两边的 buildScenarios 都调它），一个 bug 会同时废掉两门语言的场景对话页；
 * 而类注释明确承诺「损坏字段用缺省值跳过，**不抛异常**」，此前却没有任何测试守着这条承诺。
 *
 * 因此这里的重心不是「正常 JSON 能不能解析」，而是**各种坏输入下都不能抛异常**，
 * 且坏条目要被跳过、好条目照常返回。
 */
class ScenarioParserTest {

    // ---------- 正常路径 ----------

    @Test
    fun parses_all_fields() {
        val json = """
            [{"id":"self_intro","titleTarget":"自己紹介","titleZh":"自我介绍",
              "systemPrompt":"prompt body","openingLine":"こんにちは",
              "goalExpressions":["はじめまして"]}]
        """.trimIndent()
        val list = ScenarioParser.parse(json)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("self_intro", s.id)
        assertEquals("自己紹介", s.titleTarget)
        assertEquals("自我介绍", s.titleZh)
        assertEquals("prompt body", s.systemPrompt)
        assertEquals("こんにちは", s.openingLine)
        assertEquals(listOf("はじめまして"), s.goalExpressions)
    }

    @Test
    fun keeps_order_and_multiple_entries() {
        val json = """
            [{"id":"a"},{"id":"b"},{"id":"c"}]
        """.trimIndent()
        assertEquals(listOf("a", "b", "c"), ScenarioParser.parse(json).map { it.id })
    }

    @Test
    fun parses_multiple_goal_expressions() {
        val json = """[{"goalExpressions":["one","two","three"]}]"""
        assertEquals(listOf("one", "two", "three"), ScenarioParser.parse(json)[0].goalExpressions)
    }

    // ---------- 坏输入：一律返回空/跳过，绝不抛异常 ----------

    @Test
    fun empty_string_returns_empty_list() {
        assertTrue(ScenarioParser.parse("").isEmpty())
    }

    @Test
    fun garbage_returns_empty_list() {
        assertTrue(ScenarioParser.parse("not json at all").isEmpty())
    }

    @Test
    fun json_object_instead_of_array_returns_empty_list() {
        // 顶层是对象而非数组：JSONArray(json) 会抛 JSONException，必须被吞掉
        assertTrue(ScenarioParser.parse("""{"id":"a"}""").isEmpty())
    }

    @Test
    fun truncated_json_returns_empty_list() {
        assertTrue(ScenarioParser.parse("""[{"id":"a" """).isEmpty())
    }

    // ---------- 字段缺失：回落空串/空列表，不崩 ----------

    @Test
    fun missing_fields_default_to_empty() {
        val s = ScenarioParser.parse("""[{}]""")[0]
        assertEquals("", s.id)
        assertEquals("", s.titleTarget)
        assertEquals("", s.titleZh)
        assertEquals("", s.systemPrompt)
        assertEquals("", s.openingLine)
        assertTrue(s.goalExpressions.isEmpty())
    }

    @Test
    fun missing_goal_expressions_gives_empty_list() {
        assertTrue(ScenarioParser.parse("""[{"id":"a"}]""")[0].goalExpressions.isEmpty())
    }

    @Test
    fun empty_goal_expressions_gives_empty_list() {
        assertTrue(ScenarioParser.parse("""[{"goalExpressions":[]}]""")[0].goalExpressions.isEmpty())
    }

    @Test
    fun blank_goal_expressions_are_filtered_out() {
        val json = """[{"goalExpressions":["keep","","also-keep"]}]"""
        assertEquals(listOf("keep", "also-keep"), ScenarioParser.parse(json)[0].goalExpressions)
    }

    // ---------- 数组里混入非对象元素：跳过坏的，保留好的 ----------

    @Test
    fun skips_non_object_entries_and_keeps_valid_ones() {
        val json = """["just a string", null, 42, {"id":"good"}]"""
        val list = ScenarioParser.parse(json)
        assertEquals("坏条目应被跳过、好条目照常保留", 1, list.size)
        assertEquals("good", list[0].id)
    }

    @Test
    fun all_bad_entries_gives_empty_list() {
        assertTrue(ScenarioParser.parse("""["a", null, 1]""").isEmpty())
    }

    // ---------- 配套扩展 JSONObject.str() ----------
    // EnModule 在用（读 symbol / example / exampleZh / type / id），顺手守住

    @Test
    fun json_str_returns_empty_for_missing_key() {
        assertEquals("", JSONObject().str("nope"))
    }

    @Test
    fun json_str_returns_empty_for_null_value() {
        assertEquals("", JSONObject().put("k", JSONObject.NULL).str("k"))
    }

    @Test
    fun json_str_returns_actual_value_when_present() {
        assertEquals("v", JSONObject().put("k", "v").str("k"))
    }
}
