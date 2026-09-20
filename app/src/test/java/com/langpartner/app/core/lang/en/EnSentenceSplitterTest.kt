package com.langpartner.app.core.lang.en

import org.junit.Assert.assertEquals
import org.junit.Test

/** EnSentenceSplitter（§5.6.5）：13 条锚点 + 边界扩展 */
class EnSentenceSplitterTest {

    private fun assertSplit(text: String, expected: List<String>) {
        assertEquals(expected, EnSentenceSplitter.split(text))
    }

    @Test
    fun anchor_mr_smith() {
        assertSplit(
            "I met Mr. Smith. He likes tea.",
            listOf("I met Mr. Smith.", "He likes tea.")
        )
    }

    @Test
    fun anchor_pi_is_3_14() {
        assertSplit("Pi is 3.14. OK?", listOf("Pi is 3.14.", "OK?"))
    }

    @Test
    fun anchor_quote_before_then() {
        assertSplit(
            "She said \"go.\" Then left.",
            listOf("She said \"go.\"", "Then left.")
        )
    }

    @Test
    fun anchor_ph_d() {
        assertSplit("Dr. Smith has a Ph.D.", listOf("Dr. Smith has a Ph.D."))
    }

    @Test
    fun anchor_wait_bang() {
        assertSplit("Wait!!! Really?", listOf("Wait!!!", "Really?"))
    }

    @Test
    fun anchor_us_stocks() {
        assertSplit("U.S. stocks fell.", listOf("U.S. stocks fell."))
    }

    @Test
    fun anchor_abc_then_x_y() {
        assertSplit("A.B.C. then X. Y", listOf("A.B.C. then X.", "Y"))
    }

    @Test
    fun anchor_mr_and_mrs_lee() {
        assertSplit("Mr. and Mrs. Lee are here.", listOf("Mr. and Mrs. Lee are here."))
    }

    @Test
    fun anchor_no_terminator() {
        assertSplit("No terminator here", listOf("No terminator here"))
    }

    @Test
    fun anchor_japanese() {
        assertSplit("今日はいい天気。明日は雨。", listOf("今日はいい天気。", "明日は雨。"))
    }

    @Test
    fun anchor_trailing_spaces() {
        assertSplit("Hello, world.   ", listOf("Hello, world."))
    }

    @Test
    fun anchor_empty() {
        assertSplit("", emptyList())
    }

    @Test
    fun anchor_blank() {
        assertSplit("   ", emptyList())
    }

    @Test
    fun extra_abbreviation_at_sentence_end_merges() {
        // 已知取舍：缩写/etc./U.S.出现在"句末"时不终止，与下一句合并（按规范规则推演）
        assertSplit(
            "He lives in the U.S. Really?",
            listOf("He lives in the U.S. Really?")
        )
    }

    @Test
    fun extra_un_abbreviation() {
        assertSplit("The U.N. spoke today.", listOf("The U.N. spoke today."))
    }

    @Test
    fun extra_etc_mid() {
        // etc 后跟非空白（字母）→ 不终止（缩写保护）
        assertSplit("Bring pens, etc. and go.", listOf("Bring pens, etc. and go."))
    }

    @Test
    fun extra_mixed_jp_en() {
        assertSplit("日本語とEnglish。Mixed.", listOf("日本語とEnglish。", "Mixed."))
    }

    @Test
    fun extra_exclamation_and_question_runs() {
        assertSplit("Hello!!! Hi?", listOf("Hello!!!", "Hi?"))
    }

    @Test
    fun extra_dots_then_question_bang() {
        assertSplit("Wait... really?!", listOf("Wait...", "really?!"))
    }

    @Test
    fun extra_single_char_dot_end() {
        assertSplit("End.", listOf("End."))
    }

    @Test
    fun extra_dot_before_digit_not_terminal() {
        assertSplit("Pi is 3.14 exactly.", listOf("Pi is 3.14 exactly."))
    }

    @Test
    fun extra_closer_chain() {
        assertSplit("(really?) done", listOf("(really?)", "done"))
    }

    @Test
    fun extra_leading_blank_sentences_skipped() {
        assertSplit("  ...Hello.  ", listOf("...Hello."))
    }

    @Test
    fun extra_ellipsis_is_terminal() {
        assertSplit("Wait… Now", listOf("Wait…", "Now"))
    }

    @Test
    fun extra_multiline() {
        assertSplit("Hi there.\nHow are you?", listOf("Hi there.", "How are you?"))
    }
}
