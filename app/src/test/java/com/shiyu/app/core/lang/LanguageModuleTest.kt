package com.shiyu.app.core.lang

import com.shiyu.app.core.lang.en.EnModule
import com.shiyu.app.core.lang.ja.JaModule
import com.shiyu.app.data.model.Card
import com.shiyu.app.data.model.ChartEntry
import com.shiyu.app.data.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语言模块契约（§5.6.6 映射表 + Registry） */
class LanguageModuleTest {

    private val ja = JaModule()
    private val en = EnModule()

    @Test
    fun registry_returns_modules() {
        assertTrue(LanguageRegistry.module(LangId.JA) is JaModule)
        assertTrue(LanguageRegistry.module(LangId.EN) is EnModule)
        assertTrue(LanguageRegistry.moduleForTag(null) is JaModule)
        assertTrue(LanguageRegistry.moduleForTag("en") is EnModule)
        assertTrue(LanguageRegistry.moduleForTag("xyz") is JaModule)
    }

    @Test
    fun lang_id_metadata() {
        assertEquals("ja", LangId.JA.tag)
        assertEquals("日语", LangId.JA.displayName)
        assertEquals("ja", LangId.JA.sttLanguage)
        assertEquals("en", LangId.EN.tag)
        assertEquals("英语", LangId.EN.displayName)
        assertEquals("en", LangId.EN.sttLanguage)
    }

    @Test
    fun answer_hints_and_tags() {
        assertEquals("输入假名读法", ja.answerHint)
        assertEquals("拼写英文单词", en.answerHint)
        assertEquals("JA", ja.replyTag)
        assertEquals("EN", en.replyTag)
    }

    @Test
    fun asset_names() {
        assertEquals(Triple("kana.json", "n5_vocab.json", "scenarios_ja.json"), ja.assetNames())
        assertEquals(
            Triple("en_phonemes.json", "en_words.json", "scenarios_en.json"),
            en.assetNames()
        )
    }

    @Test
    fun ja_deck_mapping() {
        val json = """[
            {"id":"v_1","kanji":"私","kana":"わたし","romaji":"watashi","meaningZh":"我","jlpt":"N5"},
            {"id":"v_2","kanji":"","kana":"がくせい","romaji":"gakusei","meaningZh":"学生","jlpt":"N5"}
        ]"""
        val deck = ja.buildDeck(json)
        assertEquals(2, deck.size)
        val first = deck[0]
        assertEquals("v_1", first.id)
        assertEquals("私", first.front)
        assertEquals("JLPT N5", first.frontSub)
        assertEquals("わたし", first.back)
        assertEquals("watashi", first.backSub)
        assertEquals("我", first.meaningZh)
        assertEquals("わたし", first.speakText)
        // 日语不做例句挖空
        assertEquals("", first.exampleBlank)
        // kanji 为空时 front 回落 kana
        assertEquals("がくせい", deck[1].front)
    }

    @Test
    fun en_deck_mapping_and_blank_example() {
        val json = """[
            {"id":"e_1","word":"breakfast","ipa":"/ˈbrekfəst/","pos":"n.","meaningZh":"早餐",
             "example":"I eat breakfast at seven.","exampleZh":"我七点吃早餐。","level":"A1"}
        ]"""
        val deck = en.buildDeck(json)
        val card = deck.single()
        assertEquals("早餐", card.front)
        assertEquals("A1 · n.", card.frontSub)
        assertEquals("breakfast", card.back)
        assertEquals("/ˈbrekfəst/", card.backSub)
        // 英语的释义已在正面，meaningZh 为空串
        assertEquals("", card.meaningZh)
        assertEquals("breakfast", card.speakText)
        // 例句挖空：首次出现替换为 ____
        assertEquals("I eat ____ at seven.", card.exampleBlank)
    }

    @Test
    fun en_blank_example_case_insensitive_first_occurrence() {
        assertEquals(
            "____ is power.",
            EnModule.blankExample("knowledge", "Knowledge is power.")
        )
        // 只替换首次出现
        assertEquals(
            "I ____ and practice English.",
            EnModule.blankExample("practice", "I practice and practice English.")
        )
        // 未命中时原样返回
        assertEquals("no match here", EnModule.blankExample("zebra", "no match here"))
    }

    @Test
    fun ja_chart_mapping() {
        val json = """[{"hiragana":"あ","katakana":"ア","romaji":"a"}]"""
        val entries: List<ChartEntry> = ja.buildChart(json)
        val entry = entries.single()
        assertEquals("あ", entry.cell)
        assertEquals("a", entry.sub)
        assertEquals("あ", entry.speak)
        assertEquals("ア", entry.alt)
    }

    @Test
    fun en_chart_mapping() {
        val json = """[{"symbol":"θ","type":"consonant","example":"think","exampleZh":"思考"}]"""
        val entries = en.buildChart(json)
        val entry = entries.single()
        assertEquals("θ", entry.cell)
        assertEquals("think", entry.sub)
        assertEquals("think", entry.speak)
        assertEquals("思考", entry.note)
        assertEquals("consonant", entry.section)
    }

    @Test
    fun build_parsers_survive_bad_json() {
        assertEquals(emptyList<Card>(), ja.buildDeck("{not json"))
        assertEquals(emptyList<Card>(), en.buildDeck("{not json"))
        assertEquals(emptyList<ChartEntry>(), ja.buildChart("nope"))
        assertEquals(emptyList<Scenario>(), ja.buildScenarios("nope"))
    }

    @Test
    fun ja_split_sentences() {
        val result = ja.splitSentences("今日はいい天気。明日は雨！まだですか？")
        assertEquals(listOf("今日はいい天気。", "明日は雨！", "まだですか？"), result)
    }

    @Test
    fun en_split_sentences_delegates() {
        val result = en.splitSentences("I met Mr. Smith. He likes tea.")
        assertEquals(listOf("I met Mr. Smith.", "He likes tea."), result)
    }

    @Test
    fun modules_are_distinct_instances() {
        assertFalse(ja.id == en.id)
        assertTrue(ja.id == LangId.JA)
        assertTrue(en.id == LangId.EN)
    }
}
