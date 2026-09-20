package com.shiyu.app.data.asset

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 资产内容断言（V4 / V18）：直接读 src/main/assets 下的六份 JSON（单测工作目录为模块目录）。
 */
class AssetContentTest {

    private fun asset(name: String): String =
        File("src/main/assets/$name").readText(Charsets.UTF_8)

    private fun arr(name: String): JSONArray = JSONArray(asset(name))

    private val kana46 = listOf(
        "あ", "い", "う", "え", "お",
        "か", "き", "く", "け", "こ",
        "さ", "し", "す", "せ", "そ",
        "た", "ち", "つ", "て", "と",
        "な", "に", "ぬ", "ね", "の",
        "は", "ひ", "ふ", "へ", "ほ",
        "ま", "み", "む", "め", "も",
        "や", "ゆ", "よ",
        "ら", "り", "る", "れ", "ろ",
        "わ", "を", "ん"
    )

    // ---------- kana.json（V4） ----------

    @Test
    fun kana_contains_all_46_base() {
        val arr = arr("kana.json")
        val hira = (0 until arr.length()).map { arr.getJSONObject(it).getString("hiragana") }
        for (k in kana46) {
            assertTrue("missing kana: $k", hira.contains(k))
        }
        assertEquals(46, hira.size)
        val kata = (0 until arr.length()).map { arr.getJSONObject(it).getString("katakana") }
        val romaji = (0 until arr.length()).map { arr.getJSONObject(it).optString("romaji") }
        assertTrue(romaji.none { it.isEmpty() })
        assertTrue(kata.none { it.isEmpty() })
    }

    // ---------- n5_vocab.json（V4） ----------

    @Test
    fun n5_vocab_size_and_anchors() {
        val arr = arr("n5_vocab.json")
        assertTrue("n5 vocab >= 100", arr.length() >= 100)
        val kanjis = (0 until arr.length()).map { arr.getJSONObject(it).optString("kanji") }
        for (anchor in listOf("私", "あなた", "学生", "先生", "日本", "水", "食べる", "飲む", "行く", "見る")) {
            assertTrue("missing anchor: $anchor", kanjis.contains(anchor))
        }
    }

    @Test
    fun n5_vocab_fields() {
        val arr = arr("n5_vocab.json")
        val ids = mutableSetOf<String>()
        val kanaRegex = Regex("^[\\u3041-\\u3096ー]+$")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            assertTrue("duplicate id: $id", ids.add(id))
            assertTrue("kana must be pure hiragana or contain ー: ${o.getString("kana")}",
                kanaRegex.matches(o.getString("kana")))
            assertTrue(o.optString("romaji").isNotEmpty())
            assertTrue("jlpt must be N5", o.optString("jlpt") == "N5")
            assertTrue(o.optString("meaningZh").isNotEmpty())
        }
    }

    // ---------- scenarios_ja.json（V4） ----------

    @Test
    fun ja_scenarios_count_and_goals() {
        val arr = arr("scenarios_ja.json")
        assertTrue("scenarios >= 5", arr.length() >= 5)
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue(ids.add(o.getString("id")))
            assertTrue(o.optString("titleTarget").isNotEmpty())
            assertTrue(o.optString("titleZh").isNotEmpty())
            assertTrue(o.optString("openingLine").isNotEmpty())
            val goals = o.getJSONArray("goalExpressions")
            assertTrue("goalExpressions >= 5 for ${o.getString("id")}", goals.length() >= 5)
            val sp = o.getString("systemPrompt")
            assertTrue(sp.contains("[JA]") && sp.contains("[ZH]") && sp.contains("[FIX]"))
            assertTrue(sp.contains("N5"))
            assertTrue(sp.contains("1〜3文"))
        }
    }

    // ---------- en_phonemes.json（V18） ----------

    @Test
    fun en_phonemes_count_and_types() {
        val arr = arr("en_phonemes.json")
        assertTrue("phonemes >= 24", arr.length() >= 24)
        val symbols = mutableSetOf<String>()
        var vowels = 0
        var consonants = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val symbol = o.getString("symbol")
            assertTrue("duplicate symbol: $symbol", symbols.add(symbol))
            val type = o.getString("type")
            assertTrue("type must be vowel or consonant", type == "vowel" || type == "consonant")
            if (type == "vowel") vowels++ else consonants++
            assertTrue(o.optString("example").isNotEmpty())
            assertTrue(o.optString("exampleZh").isNotEmpty())
        }
        assertTrue("vowels >= 12", vowels >= 12)
        assertTrue("consonants >= 12", consonants >= 12)
    }

    // ---------- en_words.json（V18） ----------

    @Test
    fun en_words_count_and_anchors() {
        val arr = arr("en_words.json")
        assertTrue("words >= 100", arr.length() >= 100)
        val words = (0 until arr.length()).map { arr.getJSONObject(it).getString("word") }
        for (anchor in listOf(
            "accommodation", "necessary", "receive", "environment", "government",
            "beautiful", "weather", "breakfast", "schedule", "restaurant"
        )) {
            assertTrue("missing anchor word: $anchor", words.contains(anchor))
        }
    }

    @Test
    fun en_words_fields() {
        val arr = arr("en_words.json")
        val ids = mutableSetOf<String>()
        val wordRegex = Regex("^[a-z][a-z'\\-]*$")
        val posSet = setOf("n.", "v.", "adj.", "adv.", "prep.", "pron.", "conj.")
        val levelSet = setOf("A1", "A2", "B1")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            assertTrue("duplicate id: $id", ids.add(id))
            val word = o.getString("word")
            assertTrue("word regex: $word", wordRegex.matches(word))
            assertTrue("pos illegal: ${o.optString("pos")}", posSet.contains(o.optString("pos")))
            assertTrue("level illegal: ${o.optString("level")}", levelSet.contains(o.optString("level")))
            assertTrue(o.optString("ipa").isNotEmpty())
            assertTrue("meaningZh empty for $word", o.optString("meaningZh").isNotEmpty())
            assertTrue("exampleZh empty for $word", o.optString("exampleZh").isNotEmpty())
            assertTrue(
                "example must contain the word itself: $word",
                o.getString("example").lowercase().contains(word)
            )
        }
    }

    // ---------- scenarios_en.json（V18） ----------

    @Test
    fun en_scenarios_count_and_goals() {
        val arr = arr("scenarios_en.json")
        assertTrue("scenarios >= 5", arr.length() >= 5)
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue(ids.add(o.getString("id")))
            assertTrue(o.optString("titleTarget").isNotEmpty())
            assertTrue(o.optString("titleZh").isNotEmpty())
            assertTrue(o.optString("openingLine").isNotEmpty())
            assertTrue("goalExpressions >= 5", o.getJSONArray("goalExpressions").length() >= 5)
            val sp = o.getString("systemPrompt")
            assertTrue(sp.contains("[EN]") && sp.contains("[ZH]") && sp.contains("[FIX]"))
            assertTrue(sp.contains("A2") && sp.contains("B1"))
            assertTrue(sp.contains("1-3 sentences"))
        }
    }

    // ---------- 安全：资产里不得有密钥 ----------

    @Test
    fun assets_contain_no_secret_like_strings() {
        val secretRegex = Regex("sk-[A-Za-z0-9]{20,}")
        for (name in listOf(
            "kana.json", "n5_vocab.json", "scenarios_ja.json",
            "en_phonemes.json", "en_words.json", "scenarios_en.json"
        )) {
            assertFalse("secret found in $name", secretRegex.containsMatchIn(asset(name)))
        }
    }
}
