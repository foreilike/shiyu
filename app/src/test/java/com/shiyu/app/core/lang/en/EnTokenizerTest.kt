package com.shiyu.app.core.lang.en

import com.shiyu.app.core.lang.Token
import org.junit.Assert.assertEquals
import org.junit.Test

/** EnTokenizer（§5.6.4）：16 条锚点 + 偏移量与边界扩展 */
class EnTokenizerTest {

    private fun kinds(text: String): List<String> =
        EnTokenizer.tokenize(text).map { "${it.kind}:${it.text}" }

    private fun assertTokens(text: String, expected: String) {
        assertEquals(expected, kinds(text).joinToString(" | "))
    }

    @Test
    fun anchor_i_dont_like_it() {
        assertTokens(
            "I don't like it.",
            "WORD:I | WORD:don't | WORD:like | WORD:it | PUNCT:."
        )
    }

    @Test
    fun anchor_leading_quote_hello() {
        assertTokens(
            "'Hello,' she said.",
            "PUNCT:' | WORD:Hello | PUNCT:, | PUNCT:' | WORD:she | WORD:said | PUNCT:."
        )
    }

    @Test
    fun anchor_double_quoted() {
        assertTokens("\"Quoted\"", "PUNCT:\" | WORD:Quoted | PUNCT:\"")
    }

    @Test
    fun anchor_hyphenated() {
        assertTokens("well-known e-mail", "WORD:well-known | WORD:e-mail")
    }

    @Test
    fun anchor_covid19() {
        assertTokens("COVID-19 vaccine", "WORD:COVID-19 | WORD:vaccine")
    }

    @Test
    fun anchor_double_dash_a_b() {
        assertTokens("a--b", "WORD:a | PUNCT:-- | WORD:b")
    }

    @Test
    fun anchor_us_dot_stocks() {
        assertTokens(
            "U.S. stocks",
            "WORD:U | PUNCT:. | WORD:S | PUNCT:. | WORD:stocks"
        )
    }

    @Test
    fun anchor_thousands_number() {
        assertTokens(
            "It costs 1,000.50 dollars",
            "WORD:It | WORD:costs | NUMBER:1,000.50 | WORD:dollars"
        )
    }

    @Test
    fun anchor_3rd() {
        assertTokens("3rd place", "NUMBER:3rd | WORD:place")
    }

    @Test
    fun anchor_bang_wait() {
        assertTokens("!!! Wait", "PUNCT:!!! | WORD:Wait")
    }

    @Test
    fun anchor_wait_dots_question_bang() {
        assertTokens("Wait... really?!", "WORD:Wait | PUNCT:... | WORD:really | PUNCT:?!")
    }

    @Test
    fun anchor_parens() {
        assertTokens("(hello)", "PUNCT:( | WORD:hello | PUNCT:)")
    }

    @Test
    fun anchor_its_a_test() {
        assertTokens("it's a test", "WORD:it's | WORD:a | WORD:test")
    }

    @Test
    fun anchor_japanese_and_english() {
        assertTokens("日本語 and English", "WORD:日本語 | WORD:and | WORD:English")
    }

    @Test
    fun anchor_cafe_latte() {
        assertTokens("café latte", "WORD:café | WORD:latte")
    }

    @Test
    fun anchor_emoji_utf16_offsets() {
        val tokens = EnTokenizer.tokenize("a😀b")
        assertEquals(3, tokens.size)
        assertEquals(Token("WORD", "a", 0, 1), tokens[0])
        assertEquals(Token("PUNCT", "😀", 1, 3), tokens[1])
        assertEquals(Token("WORD", "b", 3, 4), tokens[2])
    }

    @Test
    fun extra_comma_quote_two_tokens() {
        // ,' 是逗号与引号两个 token（引号不与其它标点合并）
        assertTokens("hi,' there", "WORD:hi | PUNCT:, | PUNCT:' | WORD:there")
    }

    @Test
    fun extra_curly_quote_offsets_and_split() {
        assertTokens("“quoted”", "PUNCT:“ | WORD:quoted | PUNCT:”")
    }

    @Test
    fun extra_trailing_apostrophe_is_punct() {
        assertTokens("don'", "WORD:don | PUNCT:'")
    }

    @Test
    fun extra_leading_apostrophe_is_punct() {
        assertTokens("'twas", "PUNCT:' | WORD:twas")
    }

    @Test
    fun extra_hyphen_boundary_letter_digit() {
        assertTokens("COVID-19 x-1", "WORD:COVID-19 | WORD:x-1")
    }

    @Test
    fun extra_hyphen_needs_both_sides_alnum() {
        assertTokens("a - b", "WORD:a | PUNCT:- | WORD:b")
        assertTokens("-abc", "PUNCT:- | WORD:abc")
    }

    @Test
    fun extra_number_only_word_letters() {
        // WORD 分支也会吸收数字；点只在 NUMBER 起始时按数字边界吸收
        assertTokens("v3.14", "WORD:v3 | PUNCT:. | NUMBER:14")
    }

    @Test
    fun extra_number_leading_letters_absorbed() {
        assertTokens("42nd", "NUMBER:42nd")
    }

    @Test
    fun extra_offsets_basic() {
        val tokens = EnTokenizer.tokenize("ab cd")
        assertEquals(0, tokens[0].start)
        assertEquals(2, tokens[0].end)
        assertEquals(3, tokens[1].start)
        assertEquals(5, tokens[1].end)
    }

    @Test
    fun extra_empty_and_spaces() {
        assertEquals(0, EnTokenizer.tokenize("").size)
        assertEquals(0, EnTokenizer.tokenize("   \t").size)
    }

    @Test
    fun extra_punct_merge_run() {
        assertTokens("wow!!!??", "WORD:wow | PUNCT:!!!??")
        assertTokens("...done", "PUNCT:... | WORD:done")
    }
}
