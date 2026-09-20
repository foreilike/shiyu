package com.langpartner.app.core.lang.ja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KanaConverter（§5.6.2）：19 条锚点 + 边界扩展。
 * 不针对锚点硬编码特判，全部按规则链路期望。
 */
class KanaConverterTest {

    private fun convert(input: String): ConvertResult = KanaConverter.toHiragana(input)

    private fun assertKana(input: String, expected: String) {
        val result = convert(input)
        assertEquals("kana mismatch for input=$input", expected, result.kana)
    }

    private fun assertUnparsed(input: String, expected: List<String>) {
        val result = convert(input)
        assertEquals("unparsed mismatch for input=$input", expected, result.unparsed)
    }

    @Test
    fun anchor_sokuon_gakkou() = assertKana("gakkou", "がっこう")

    @Test
    fun anchor_sokuon_kekka() = assertKana("kekka", "けっか")

    @Test
    fun anchor_sokuon_tch_matcha() = assertKana("matcha", "まっちゃ")

    @Test
    fun anchor_longest_match_shashin() = assertKana("shashin", "しゃしん")

    @Test
    fun anchor_n_youon_shinjuku() = assertKana("shinjuku", "しんじゅく")

    @Test
    fun anchor_n_ketuban_nippon() = assertKana("nippon", "にっぽん")

    @Test
    fun anchor_n_before_p_kanpai() = assertKana("kanpai", "かんぱい")

    @Test
    fun anchor_nn_consumes_one_n_onna() = assertKana("onna", "おんな")

    @Test
    fun anchor_nn_consumes_one_n_konnichiwa() = assertKana("konnichiwa", "こんにちわ")

    @Test
    fun anchor_final_nn_honn() = assertKana("honn", "ほん")

    @Test
    fun anchor_n_apostrophe_shinyou() = assertKana("shin'you", "しんよう")

    @Test
    fun anchor_n_before_y_shinyou() = assertKana("shinyou", "しにょう")

    @Test
    fun anchor_napostrophe_vs_nya_honya() = assertKana("hon'ya", "ほんや")

    @Test
    fun anchor_napostrophe_vs_nya_reverse_honya() = assertKana("honya", "ほにゃ")

    @Test
    fun anchor_sokuon_longvowel_hokkaidou() = assertKana("hokkaidou", "ほっかいどう")

    @Test
    fun anchor_hyphen_super() = assertKana("su-pa-", "すーぱー")

    @Test
    fun anchor_plain_gakusei() = assertKana("gakusei", "がくせい")

    @Test
    fun anchor_uppercase_gakkou() = assertKana("Gakkou", "がっこう")

    @Test
    fun anchor_fullwidth_gakkou() = assertKana("ｇａｋｋｏｕ", "がっこう")

    @Test
    fun alias_shi_si() {
        assertKana("shi", "し")
        assertKana("si", "し")
    }

    @Test
    fun alias_tsu_tu() {
        assertKana("tsu", "つ")
        assertKana("tu", "つ")
    }

    @Test
    fun alias_chi_ti() {
        assertKana("chi", "ち")
        assertKana("ti", "ち")
    }

    @Test
    fun alias_fu_hu() {
        assertKana("fu", "ふ")
        assertKana("hu", "ふ")
    }

    @Test
    fun alias_ji_zi() {
        assertKana("ji", "じ")
        assertKana("zi", "じ")
    }

    @Test
    fun alias_sya_sha() {
        assertKana("sya", "しゃ")
        assertKana("sha", "しゃ")
    }

    @Test
    fun alias_tya_cha() {
        assertKana("tya", "ちゃ")
        assertKana("cha", "ちゃ")
    }

    @Test
    fun alias_zya_ja() {
        assertKana("zya", "じゃ")
        assertKana("ja", "じゃ")
    }

    @Test
    fun alias_di_du() {
        assertKana("di", "ぢ")
        assertKana("du", "づ")
    }

    @Test
    fun youon_full_rows() {
        assertKana("kya", "きゃ")
        assertKana("kyu", "きゅ")
        assertKana("kyo", "きょ")
        assertKana("nya", "にゃ")
        assertKana("hya", "ひゃ")
        assertKana("mya", "みゃ")
        assertKana("rya", "りゃ")
        assertKana("gya", "ぎゃ")
        assertKana("bya", "びゃ")
        assertKana("pya", "ぴゃ")
    }

    @Test
    fun dakuten_rows() {
        assertKana("gakusei", "がくせい")
        assertKana("zasshi", "ざっし")
        assertKana("benri", "べんり")
        assertKana("pan", "ぱん")
    }

    @Test
    fun long_vowel_spelled_out() {
        assertKana("ou", "おう")
        assertKana("uu", "うう")
        assertKana("ohayou", "おはよう")
        assertKana("arigatou", "ありがとう")
    }

    @Test
    fun sokuon_repeated_consonants() {
        assertKana("kekkonshiki", "けっこんしき")
        assertKana("zasshi", "ざっし")
        assertKana("issho", "いっしょ")
    }

    @Test
    fun sokuon_does_not_apply_to_n() {
        // nn 由拨音规则处理，不是促音
        assertKana("anna", "あんな")
    }

    @Test
    fun unparsed_loanword_syllable() {
        // fa 没有扩展音节条目：f 落到 unparsed，a 正常解析
        val result = convert("fa")
        assertEquals("fあ", result.kana)
        assertEquals(listOf("f"), result.unparsed)
    }

    @Test
    fun unparsed_kanji_and_space() {
        // 空格也计入 unparsed（§5.6.2：含汉字、数字、空格）
        val result = convert("a b漢")
        assertEquals("あ b漢", result.kana)
        assertEquals(listOf(" ", "b", "漢"), result.unparsed)
    }

    @Test
    fun unparsed_apostrophe_not_counted() {
        // 独立撇号（不在 n 后）原样输出但不计入 unparsed
        val result = convert("a'b")
        assertEquals("あ'b", result.kana)
        assertEquals(listOf("b"), result.unparsed)
    }

    @Test
    fun empty_input() {
        val result = convert("")
        assertEquals("", result.kana)
        assertTrue(result.unparsed.isEmpty())
    }

    @Test
    fun trim_and_whitespace() {
        val result = convert("  kouen  ")
        assertEquals("こうえん", result.kana)
    }

    @Test
    fun word_ending_n() {
        assertKana("nihon", "にほん")
        assertKana("pen", "ぺん")
        assertKana("denwa", "でんわ")
    }

    @Test
    fun n_before_various_consonants() {
        assertKana("sangatsu", "さんがつ")
        assertKana("tenki", "てんき")
        assertKana("ringo", "りんご")
    }
}
