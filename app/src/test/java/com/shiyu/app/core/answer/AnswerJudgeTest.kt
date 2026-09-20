package com.shiyu.app.core.answer

import com.shiyu.app.core.lang.en.EnModule
import com.shiyu.app.core.lang.ja.JaModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.lang.LangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AnswerJudge（§5.7） */
class AnswerJudgeTest {

    private val ja = LanguageRegistry.module(LangId.JA)
    private val en = LanguageRegistry.module(LangId.EN)

    // ---------- 日语 ----------

    @Test
    fun ja_katakana_input_counts() {
        // 片假名输入应判对
        val result = AnswerJudge.judge(ja, "わたし", "ワタシ")
        assertTrue(result.correct)
    }

    @Test
    fun ja_halfwidth_kana_counts() {
        val result = AnswerJudge.judge(ja, "わたし", "ﾜﾀｼ")
        assertTrue(result.correct)
    }

    @Test
    fun ja_fullwidth_space_and_punct_ignored() {
        assertTrue(AnswerJudge.judge(ja, "わたし", "　わたし。").correct)
        assertTrue(AnswerJudge.judge(ja, "わたし", " わ た し ").correct)
        assertTrue(AnswerJudge.judge(ja, "がくせい", "ガクセイ、").correct)
    }

    @Test
    fun ja_reading_mistake_fails() {
        val result = AnswerJudge.judge(ja, "わたし", "わらたし")
        assertFalse(result.correct)
        assertEquals("输入：わらたし，期望：わたし", result.reason)
    }

    @Test
    fun ja_wrong_meaning_word_fails() {
        assertFalse(AnswerJudge.judge(ja, "みず", "みずうみ").correct)
        assertFalse(AnswerJudge.judge(ja, "たべる", "のむ").correct)
    }

    @Test
    fun ja_long_vowel_variants_match() {
        // スーパー（长音符）与 suupaa 归一化后不同；全角连字符与 ー 统一才判对
        assertTrue(AnswerJudge.judge(ja, "すーぱー", "スーパー").correct)
    }

    @Test
    fun ja_normalized_input_returned() {
        val result = AnswerJudge.judge(ja, "わたし", "  ワタシ、 ")
        assertEquals("わたし", result.normalizedInput)
    }

    @Test
    fun ja_reason_null_when_correct() {
        assertNull(AnswerJudge.judge(ja, "いく", "いく").reason)
    }

    // ---------- 英语 ----------

    @Test
    fun en_well_known_hyphen_and_space() {
        assertTrue(AnswerJudge.judge(en, "well-known", "  Well Known  ").correct)
    }

    @Test
    fun en_receive_case_insensitive() {
        assertTrue(AnswerJudge.judge(en, "Receive", "receive").correct)
    }

    @Test
    fun en_restaurant_punctuation_ignored() {
        assertTrue(AnswerJudge.judge(en, "restaurant", "restaurant.").correct)
    }

    @Test
    fun en_apostrophe_ignored_known_tradeoff() {
        // 已知取舍：撇号被抹平，It's 与 its 判对
        assertTrue(AnswerJudge.judge(en, "It's", "its").correct)
    }

    @Test
    fun en_beautiful_misspelling_fails() {
        val result = AnswerJudge.judge(en, "beautiful", "beutiful")
        assertFalse(result.correct)
        assertEquals("输入：beutiful，期望：beautiful", result.reason)
    }

    @Test
    fun en_necessary_misspelling_fails() {
        assertFalse(AnswerJudge.judge(en, "necessary", "necessery").correct)
    }

    @Test
    fun en_accents_and_fullwidth_ignored() {
        assertTrue(AnswerJudge.judge(en, "resume", "RÉSUMÉ").correct)
        assertTrue(AnswerJudge.judge(en, "schedule", "ＳＣＨＥＤＵＬＥ").correct)
    }

    @Test
    fun en_uppercase_expected_matches() {
        assertTrue(AnswerJudge.judge(en, "COVID-19", "covid19").correct)
    }

    @Test
    fun en_empty_input_fails() {
        assertFalse(AnswerJudge.judge(en, "water", "").correct)
        assertFalse(AnswerJudge.judge(en, "water", "    ").correct)
    }
}
