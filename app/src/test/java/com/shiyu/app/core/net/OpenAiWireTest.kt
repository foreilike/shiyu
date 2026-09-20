package com.shiyu.app.core.net

import com.shiyu.app.data.model.ChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenAiWire（§5.3）：请求体构造与响应解析 */
class OpenAiWireTest {

    @Test
    fun chat_body_is_valid_json_with_order() {
        val messages = listOf(
            ChatMessage("system", "你是老师"),
            ChatMessage("user", "hello"),
            ChatMessage("assistant", "hi\n中文")
        )
        val body = OpenAiWire.buildChatBody("gpt-x", messages, 0.7)
        val root = JSONObject(body)
        assertEquals("gpt-x", root.getString("model"))
        assertEquals(0.7, root.getDouble("temperature"), 1e-9)
        val arr = root.getJSONArray("messages")
        assertEquals(3, arr.length())
        assertEquals("system", arr.getJSONObject(0).getString("role"))
        assertEquals("你是老师", arr.getJSONObject(0).getString("content"))
        assertEquals("user", arr.getJSONObject(1).getString("role"))
        assertEquals("hello", arr.getJSONObject(1).getString("content"))
        assertEquals("assistant", arr.getJSONObject(2).getString("role"))
        assertEquals("hi\n中文", arr.getJSONObject(2).getString("content"))
    }

    @Test
    fun chat_body_cjk_not_escaped() {
        val body = OpenAiWire.buildChatBody(
            "m", listOf(ChatMessage("user", "日本語と中文")), 0.7
        )
        assertTrue(body.contains("日本語と中文"))
        assertFalse(body.contains("\\u"))
    }

    @Test
    fun chat_body_escapes_newline_and_quote_and_backslash() {
        val body = OpenAiWire.buildChatBody(
            "m", listOf(ChatMessage("user", "a\nb\"c\\d")), 0.7
        )
        assertTrue(body.contains("\\n"))
        assertTrue(body.contains("\\\""))
        assertTrue(body.contains("\\\\"))
        // 回读后逐字还原
        val parsed = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals("a\nb\"c\\d", parsed)
    }

    @Test
    fun chat_body_round_trip_cjk_with_newline() {
        val content = "第一行\n第二行"
        val body = OpenAiWire.buildChatBody("m", listOf(ChatMessage("user", content)), 0.7)
        val parsed = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(content, parsed)
        assertTrue(body.contains("\\n"))
        assertTrue(body.contains("第一行"))
    }

    @Test
    fun parse_chat_content_ok() {
        val body = """{"choices":[{"message":{"content":"hello"}}]}"""
        assertEquals("hello", OpenAiWire.parseChatContent(body))
    }

    @Test
    fun parse_chat_content_empty_choices() {
        assertNull(OpenAiWire.parseChatContent("""{"choices":[]}"""))
    }

    @Test
    fun parse_chat_content_missing_choices() {
        assertNull(OpenAiWire.parseChatContent("""{"foo":1}"""))
    }

    @Test
    fun parse_chat_content_null_content() {
        assertNull(OpenAiWire.parseChatContent("""{"choices":[{"message":{"content":null}}]}"""))
    }

    @Test
    fun parse_chat_content_not_object() {
        assertNull(OpenAiWire.parseChatContent("[1,2,3]"))
        assertNull(OpenAiWire.parseChatContent("not json at all"))
    }

    @Test
    fun parse_chat_content_missing_message() {
        assertNull(OpenAiWire.parseChatContent("""{"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun parse_transcription_text_ok() {
        assertEquals("hello", OpenAiWire.parseTranscriptionText("""{"text":"hello"}"""))
    }

    @Test
    fun parse_transcription_text_failures() {
        assertNull(OpenAiWire.parseTranscriptionText("""{"foo":1}"""))
        assertNull(OpenAiWire.parseTranscriptionText("nope"))
    }

    @Test
    fun tts_body_default_voice_alloy() {
        val body = OpenAiWire.buildTtsBody("m", "你好", "")
        val root = JSONObject(body)
        assertEquals("alloy", root.getString("voice"))
        assertEquals("mp3", root.getString("response_format"))
        assertEquals("你好", root.getString("input"))
    }

    @Test
    fun tts_body_custom_voice() {
        val root = JSONObject(OpenAiWire.buildTtsBody("m", "hi", "nova"))
        assertEquals("nova", root.getString("voice"))
    }
}
