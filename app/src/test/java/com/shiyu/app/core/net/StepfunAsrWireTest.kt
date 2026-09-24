package com.shiyu.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StepfunAsrWire（stepfun 原生 ASR 协议，§17.21.5）。
 *
 * 存在的缘由：stepfun 没有 OpenAI 的 /audio/transcriptions（实测 404），
 * 得走自己的 /audio/asr/sse —— 请求体是「JSON + base64」、响应是「SSE 文本流」，
 * 与 multipart + JSON 那套完全不同。这里把两侧都钉住。
 */
class StepfunAsrWireTest {

    // ---------- 请求体 ----------

    @Test
    fun body_has_expected_nesting() {
        val body = StepfunAsrWire.buildAsrBody("QUJD", "ja", "stepaudio-2.5-asr", "wav")
        val root = org.json.JSONObject(body)
        val audio = root.getJSONObject("audio")
        assertEquals("QUJD", audio.getString("data"))
        val input = audio.getJSONObject("input")
        val tr = input.getJSONObject("transcription")
        assertEquals("ja", tr.getString("language"))
        assertEquals("stepaudio-2.5-asr", tr.getString("model"))
        assertEquals("wav", input.getJSONObject("format").getString("type"))
    }

    @Test
    fun body_omits_pcm_only_fields() {
        // rate/bits/channel 只在 type=pcm 时必填；wav/m4a 等带头的容器不该传
        val body = StepfunAsrWire.buildAsrBody("QUJD", "ja", "m", "wav")
        assertTrue("wav 不应带 rate", !body.contains("\"rate\""))
        assertTrue("wav 不应带 bits", !body.contains("\"bits\""))
        assertTrue("wav 不应带 channel", !body.contains("\"channel\""))
    }

    // ---------- 扩展名 → format.type ----------

    @Test
    fun format_type_for_known_extensions() {
        assertEquals("wav", StepfunAsrWire.formatTypeFor("wav"))
        assertEquals("wav", StepfunAsrWire.formatTypeFor(".WAV"))
        assertEquals("mp3", StepfunAsrWire.formatTypeFor("mp3"))
        assertEquals("ogg", StepfunAsrWire.formatTypeFor("ogg"))
        assertEquals("m4a", StepfunAsrWire.formatTypeFor("m4a"))
        assertEquals("flac", StepfunAsrWire.formatTypeFor("flac"))
    }

    @Test
    fun format_type_falls_back_to_m4a_for_unknown() {
        assertEquals("m4a", StepfunAsrWire.formatTypeFor("3gp"))
        assertEquals("m4a", StepfunAsrWire.formatTypeFor(""))
    }

    // ---------- SSE 解析 ----------

    private fun sse(vararg events: String): String =
        events.joinToString("\n\n") { "data: $it" }

    private fun done(text: String) =
        """{"type":"transcript.text.done","meta":{"session_id":"s1"},"text":"$text"}"""

    private fun delta(text: String) =
        """{"type":"transcript.text.delta","meta":{"session_id":"s1"},"delta":"$text"}"""

    @Test
    fun parses_done_event_text() {
        val body = sse(delta("こんにちは"), done("こんにちは。"))
        assertEquals("こんにちは。", StepfunAsrWire.parseSseTranscript(body))
    }

    @Test
    fun falls_back_to_deltas_when_no_done_event() {
        // 流被截断时没有 done，拼接 delta 至少能救回一部分
        val body = sse(delta("こん"), delta("にちは"))
        assertEquals("こんにちは", StepfunAsrWire.parseSseTranscript(body))
    }

    @Test
    fun empty_result_gives_empty_string_not_null() {
        // 「识别完了但没内容」返回空串，由 Provider 决定怎么报错，不能返回 null
        assertEquals("", StepfunAsrWire.parseSseTranscript(sse(done(""))))
        assertEquals("", StepfunAsrWire.parseSseTranscript(""))
    }

    @Test
    fun malformed_lines_are_skipped() {
        val body = "data: not json\n\ndata: ${done("大丈夫")}\n\n"
        assertEquals("大丈夫", StepfunAsrWire.parseSseTranscript(body))
    }

    // ---------- SSE 错误事件 ----------

    @Test
    fun detects_error_event() {
        val body = sse("""{"type":"error","meta":{"session_id":"s1"},"message":"model_invalid"}""")
        assertEquals("model_invalid", StepfunAsrWire.parseSseError(body))
    }

    @Test
    fun no_error_event_returns_null() {
        assertNull(StepfunAsrWire.parseSseError(sse(done("おはよう"))))
    }

    @Test
    fun error_without_message_gives_placeholder() {
        val body = sse("""{"type":"error","meta":{"session_id":"s1"}}""")
        assertEquals("unknown ASR error", StepfunAsrWire.parseSseError(body))
    }

    // ---------- host 判定 ----------

    @Test
    fun detects_stepfun_host() {
        assertTrue(StepfunAsrWire.isStepfunHost("https://api.stepfun.com/step_plan/v1"))
        assertTrue(StepfunAsrWire.isStepfunHost("https://API.STEPFUN.COM/v1"))
    }

    @Test
    fun other_hosts_are_not_stepfun() {
        assertTrue(!StepfunAsrWire.isStepfunHost("https://api.openai.com/v1"))
        assertTrue(!StepfunAsrWire.isStepfunHost("https://my-stepfun-mirror.example.com/v1"))
        assertTrue(!StepfunAsrWire.isStepfunHost(""))
    }
}
