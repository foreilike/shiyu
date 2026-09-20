package com.langpartner.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Test

/** LogRedactor（§6.2） */
class LogRedactorTest {

    @Test
    fun anchor_65_char_key() {
        val key = "sk-ZD5Lr" + "x".repeat(53) + "tbkS" // 8+53+4 = 65 字符
        assertEquals(65, key.length)
        assertEquals("sk-****tbkS", LogRedactor.mask(key))
    }

    @Test
    fun short_key_masked_fixed() {
        assertEquals("****", LogRedactor.mask("abc1234")) // 7 字符
    }

    @Test
    fun empty_key() {
        assertEquals("****", LogRedactor.mask(""))
    }

    @Test
    fun exactly_eight_characters() {
        val key = "abcdefgh"
        assertEquals("abc****efgh", LogRedactor.mask(key))
    }

    @Test
    fun nine_characters() {
        assertEquals("abc****fghi", LogRedactor.mask("abcdefghi"))
    }
}
