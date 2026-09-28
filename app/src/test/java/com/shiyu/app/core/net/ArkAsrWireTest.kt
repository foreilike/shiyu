package com.shiyu.app.core.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ArkAsrWire —— 火山方舟语音转写协议（§17.21.13）。
 *
 * 这套协议的存在缘由：stepfun 的 ASR 对**带中文口音的日语**会把语种判成中文、
 * 输出中文谐音，且 `language`/`prompt`/`hotwords` 实测都改不了输出文字体系。
 * 方舟走大模型，能用提示词指定输出文字体系（§17.21.12 实测）。
 *
 * 这里把三处容易写错的地方钉死：**提示词必须指定文字体系**、**必须关思考**、
 * **两步请求体的字段名**。
 */
class ArkAsrWireTest {

    // ---------- host 判定 ----------

    @Test
    fun detects_ark_host() {
        assertTrue(ArkAsrWire.isArkHost("https://ark.cn-beijing.volces.com/api/v3"))
        assertTrue(ArkAsrWire.isArkHost("https://ARK.CN-BEIJING.VOLCES.COM/api/v3"))
    }

    @Test
    fun other_hosts_are_not_ark() {
        assertFalse(ArkAsrWire.isArkHost("https://api.stepfun.com/step_plan/v1"))
        assertFalse(ArkAsrWire.isArkHost("https://api.groq.com/openai/v1"))
        // 不能因为域名里出现 volces 就误判（自建/代理会踩）
        assertFalse(ArkAsrWire.isArkHost("https://my-volces-mirror.example.com/v1"))
        assertFalse(ArkAsrWire.isArkHost(""))
    }

    @Test
    fun ark_and_stepfun_hosts_do_not_overlap() {
        // 三套协议靠 host 择一，判据重叠会导致选错 Provider
        val ark = "https://ark.cn-beijing.volces.com/api/v3"
        val step = "https://api.stepfun.com/step_plan/v1"
        assertTrue(ArkAsrWire.isArkHost(ark) && !StepfunAsrWire.isStepfunHost(ark))
        assertTrue(StepfunAsrWire.isStepfunHost(step) && !ArkAsrWire.isArkHost(step))
    }

    // ---------- 提示词（这套协议的关键，写错了整条路就废） ----------

    @Test
    fun japanese_prompt_requires_japanese_script() {
        val p = ArkAsrWire.transcribePrompt("ja")
        assertTrue("必须点明用日文文字", p.contains("日文"))
        assertTrue("必须禁止中文谐音", p.contains("不要用中文汉字谐音"))
        assertTrue("必须禁止解释（否则会回一大段说明且慢几十秒）", p.contains("不要解释"))
    }

    @Test
    fun japanese_prompt_matches_language_tag_case_insensitively() {
        assertEquals(ArkAsrWire.transcribePrompt("ja"), ArkAsrWire.transcribePrompt("JA"))
        assertEquals(ArkAsrWire.transcribePrompt("ja"), ArkAsrWire.transcribePrompt(" ja "))
    }

    @Test
    fun english_and_unknown_languages_are_handled() {
        assertTrue(ArkAsrWire.transcribePrompt("en").contains("English"))
        // 未知语种也要给出「只输出转写」的兜底提示，不能返回空串
        assertTrue(ArkAsrWire.transcribePrompt("ko").isNotBlank())
    }

    // ---------- 请求体 ----------

    @Test
    fun chat_body_has_audio_then_text_and_thinking_disabled() {
        val root = JSONObject(ArkAsrWire.buildChatBody("file-123", "ja", "m"))
        assertEquals("m", root.getString("model"))
        // 关思考是这条路能用于实时字幕的前提：开着实测要 36~137 秒
        assertEquals("disabled", root.getJSONObject("thinking").getString("type"))

        val content = root.getJSONArray("messages")
            .getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("input_audio", content.getJSONObject(0).getString("type"))
        assertEquals("file-123",
            content.getJSONObject(0).getJSONObject("input_audio").getString("file_id"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getString("text").contains("日文"))
    }

    @Test
    fun chat_body_role_is_user() {
        val root = JSONObject(ArkAsrWire.buildChatBody("f", "ja", "m"))
        assertEquals("user",
            root.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    // ---------- 响应解析 ----------

    @Test
    fun parses_file_id_from_upload_response() {
        val body = """{"id":"file-20260928174425-8dsn8","object":"file",
            "status":"processing","purpose":"user_data"}"""
        assertEquals("file-20260928174425-8dsn8", ArkAsrWire.parseFileId(body))
    }

    @Test
    fun missing_or_blank_file_id_gives_null() {
        assertNull(ArkAsrWire.parseFileId("{}"))
        assertNull(ArkAsrWire.parseFileId("""{"id":""}"""))
        assertNull(ArkAsrWire.parseFileId("not json"))
    }

    @Test
    fun parses_chat_text() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant",
            "content":"おはようございます"}}]}"""
        assertEquals("おはようございます", ArkAsrWire.parseChatText(body))
    }

    @Test
    fun malformed_chat_response_gives_null() {
        assertNull(ArkAsrWire.parseChatText("not json"))
        assertNull(ArkAsrWire.parseChatText("{}"))
        assertNull(ArkAsrWire.parseChatText("""{"choices":[]}"""))
    }

    // ---------- MIME ----------

    @Test
    fun mime_type_matches_extension() {
        assertEquals("audio/wav", ArkAsrWire.mimeTypeFor("a.wav"))
        assertEquals("audio/mp4", ArkAsrWire.mimeTypeFor("a.m4a"))
        assertEquals("audio/mpeg", ArkAsrWire.mimeTypeFor("a.mp3"))
        assertEquals("application/octet-stream", ArkAsrWire.mimeTypeFor("a.xyz"))
    }

    // ---------- 常量 ----------

    @Test
    fun paths_and_default_model_are_stable() {
        assertEquals("/files", ArkAsrWire.FILES_PATH)
        assertEquals("/chat/completions", ArkAsrWire.CHAT_PATH)
        assertEquals("doubao-seed-2-0-mini-260428", ArkAsrWire.DEFAULT_MODEL)
    }
}
