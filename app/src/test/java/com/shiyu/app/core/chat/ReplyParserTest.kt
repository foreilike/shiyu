package com.shiyu.app.core.chat

import com.shiyu.app.core.lang.en.EnModule
import com.shiyu.app.core.lang.ja.JaModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReplyParser（§M2-4） */
class ReplyParserTest {

    private val ja = JaModule()
    private val en = EnModule()

    @Test
    fun ja_full_format() {
        val raw = "[JA] こんにちは！一緒に練習しましょう。\n[ZH] 你好！我们一起练习吧。\n[FIX] 「練習し足りない」应为「練習したい」"
        val parsed = ReplyParser.parse(ja, raw)
        assertEquals("こんにちは！一緒に練習しましょう。", parsed.body)
        assertEquals("你好！我们一起练习吧。", parsed.translation)
        assertEquals("「練習し足りない」应为「練習したい」", parsed.fix)
    }

    @Test
    fun en_full_format() {
        val raw = "[EN] Good morning! What can I get you?\n[ZH] 早上好！您要点什么？\n[FIX] 无"
        val parsed = ReplyParser.parse(en, raw)
        assertEquals("Good morning! What can I get you?", parsed.body)
        assertEquals("早上好！您要点什么？", parsed.translation)
        assertEquals("无", parsed.fix)
    }

    @Test
    fun tags_case_insensitive() {
        val parsed = ReplyParser.parse(ja, "[ja] おはよう\n[zh] 早上\n[fix] 无")
        assertEquals("おはよう", parsed.body)
        assertEquals("早上", parsed.translation)
        assertEquals("无", parsed.fix)
    }

    @Test
    fun spaces_around_tags_allowed() {
        val parsed = ReplyParser.parse(ja, "  [ JA ]  おはよう  \n [ZH] 早上 \n [FIX] 无 ")
        assertEquals("おはよう", parsed.body)
        assertEquals("早上", parsed.translation)
    }

    @Test
    fun missing_sections_stay_empty() {
        val parsed = ReplyParser.parse(en, "[EN] Only body here.")
        assertEquals("Only body here.", parsed.body)
        assertEquals("", parsed.translation)
        assertEquals("", parsed.fix)
    }

    @Test
    fun no_tags_at_all_treated_as_body() {
        val raw = "Just a plain reply without any markers."
        val parsed = ReplyParser.parse(en, raw)
        assertEquals(raw, parsed.body)
        assertEquals("", parsed.translation)
        assertEquals("", parsed.fix)
    }

    @Test
    fun multiline_body_continuation() {
        val raw = "[EN] First line.\nSecond line.\n[ZH] 第一行。第二行。"
        val parsed = ReplyParser.parse(en, raw)
        assertEquals("First line.\nSecond line.", parsed.body)
        assertEquals("第一行。第二行。", parsed.translation)
    }

    @Test
    fun empty_input() {
        val parsed = ReplyParser.parse(en, "")
        assertEquals("", parsed.body)
    }

    @Test
    fun garbage_input_never_throws() {
        val raw = "[[[ ]]] <<< >>> {{{ }}} 特殊符号"
        val parsed = ReplyParser.parse(ja, raw)
        assertEquals(raw, parsed.body)
    }

    @Test
    fun unknown_tag_does_not_break_sections() {
        // 未知标签行按普通文本续接到当前段落，已识别的段落不受影响
        val raw = "[EN] body\n[XX] mystery\n[ZH] 翻译"
        val parsed = ReplyParser.parse(en, raw)
        assertEquals("body\n[XX] mystery", parsed.body)
        assertEquals("翻译", parsed.translation)
    }

    @Test
    fun mock_reply_shape() {
        val parsed = ReplyParser.parse(ja, "[JA] こんにちは！(mock) 練習しましょう。\n[ZH] 你好！(mock)\n[FIX] 无")
        assertTrue(parsed.body.contains("(mock)"))
        assertTrue(parsed.translation.isNotEmpty())
        assertEquals("无", parsed.fix)
    }
}
