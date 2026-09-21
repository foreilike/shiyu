package com.shiyu.app.core.realtime

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** RealtimeProtocol（stepfun realtime 线协议）锚点 */
class RealtimeProtocolTest {

    @Test
    fun session_update_shape() {
        val json = RealtimeProtocol.sessionUpdate(
            instructions = "你是日语老师",
            voice = "wenrounansheng",
            serverVad = true,
            silenceDurationMs = 300,
            energyAwakenessThreshold = 2000,
            prefixPaddingMs = 400
        )
        val root = JSONObject(json)
        assertEquals("session.update", root.getString("type"))
        assertTrue(root.getString("event_id").isNotEmpty())
        val s = root.getJSONObject("session")
        assertEquals("你是日语老师", s.getString("instructions"))
        assertEquals("wenrounansheng", s.getString("voice"))
        assertEquals("pcm16", s.getString("input_audio_format"))
        assertEquals("pcm16", s.getString("output_audio_format"))
        assertEquals(2, s.getJSONArray("modalities").length())
        assertEquals("text", s.getJSONArray("modalities").getString(0))
        assertEquals("audio", s.getJSONArray("modalities").getString(1))
        val vad = s.getJSONObject("turn_detection")
        assertEquals("server_vad", vad.getString("type"))
        assertEquals(400, vad.getInt("prefix_padding_ms"))
        assertEquals(300, vad.getInt("silence_duration_ms"))
        assertEquals(2000, vad.getInt("energy_awakeness_threshold"))
    }

    @Test
    fun session_update_without_vad_omits_turn_detection() {
        val json = RealtimeProtocol.sessionUpdate(
            instructions = "x", voice = "v", serverVad = false,
            silenceDurationMs = 100, energyAwakenessThreshold = 2500, prefixPaddingMs = 500
        )
        val s = JSONObject(json).getJSONObject("session")
        assertFalse(s.has("turn_detection"))
    }

    @Test
    fun append_audio_is_base64_pcm() {
        val pcm = byteArrayOf(1, 2, 3, -1, -2, 0, 127, -128)
        val json = RealtimeProtocol.appendAudio(pcm)
        val root = JSONObject(json)
        assertEquals("input_audio_buffer.append", root.getString("type"))
        val decoded = Base64.getDecoder().decode(root.getString("audio"))
        assertArrayEquals(pcm, decoded)
    }

    @Test
    fun control_events_have_type_and_id() {
        for (json in listOf(
            RealtimeProtocol.commit(),
            RealtimeProtocol.clear(),
            RealtimeProtocol.responseCreate(),
            RealtimeProtocol.responseCancel()
        )) {
            val root = JSONObject(json)
            assertTrue(root.getString("type").isNotEmpty())
            assertTrue(root.getString("event_id").isNotEmpty())
        }
    }

    @Test
    fun parse_server_events() {
        assertTrue(
            RealtimeProtocol.parse("""{"type":"session.created","event_id":"e1"}""")
                is RealtimeProtocol.ServerEvent.SessionCreated
        )
        assertTrue(
            RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started"}""")
                is RealtimeProtocol.ServerEvent.SpeechStarted
        )
        assertTrue(
            RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_stopped"}""")
                is RealtimeProtocol.ServerEvent.SpeechStopped
        )
        assertTrue(
            RealtimeProtocol.parse("""{"type":"response.done"}""")
                is RealtimeProtocol.ServerEvent.ResponseDone
        )
    }

    @Test
    fun parse_audio_delta_decodes_pcm() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val event = RealtimeProtocol.parse("""{"type":"response.audio.delta","delta":"$b64"}""")
        assertTrue(event is RealtimeProtocol.ServerEvent.AudioDelta)
        assertArrayEquals(pcm, (event as RealtimeProtocol.ServerEvent.AudioDelta).pcm)
    }

    @Test
    fun parse_transcript_and_user_transcript() {
        val e1 = RealtimeProtocol.parse("""{"type":"response.audio_transcript.delta","delta":"こんにちは"}""")
        assertEquals(
            "こんにちは",
            (e1 as RealtimeProtocol.ServerEvent.TranscriptDelta).text
        )
        val e2 = RealtimeProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello"}"""
        )
        assertEquals("hello", (e2 as RealtimeProtocol.ServerEvent.UserTranscript).text)
    }

    @Test
    fun parse_error_event() {
        val e = RealtimeProtocol.parse(
            """{"type":"error","error":{"message":"quota exceeded","code":"402"}}"""
        )
        assertEquals("quota exceeded", (e as RealtimeProtocol.ServerEvent.ErrorEvent).message)
        assertEquals("402", e.code)
    }

    @Test
    fun parse_unknown_and_malformed() {
        assertTrue(
            RealtimeProtocol.parse("""{"type":"some.future.event"}""")
                is RealtimeProtocol.ServerEvent.Unknown
        )
        assertNull(RealtimeProtocol.parse("not json"))
    }

    // ---------- 连接参数拼装（对应官方文档的必填项） ----------

    @Test
    fun endpoint_appends_model_as_query_param() {
        // 官方文档：model 是必填查询参数
        assertEquals(
            "wss://api.stepfun.com/v1/realtime?model=step-audio-2-mini",
            RealtimeProtocol.endpointWithModel(
                "wss://api.stepfun.com/v1/realtime", "step-audio-2-mini"
            )
        )
    }

    @Test
    fun endpoint_uses_ampersand_when_query_already_present() {
        assertEquals(
            "wss://api.stepfun.com/v1/realtime?a=1&model=m",
            RealtimeProtocol.endpointWithModel("wss://api.stepfun.com/v1/realtime?a=1", "m")
        )
    }

    @Test
    fun endpoint_is_untouched_when_model_blank_or_already_present() {
        val base = "wss://api.stepfun.com/v1/realtime"
        assertEquals(base, RealtimeProtocol.endpointWithModel(base, ""))
        assertEquals(base, RealtimeProtocol.endpointWithModel(base, "  "))
        val withModel = "$base?model=x"
        assertEquals(withModel, RealtimeProtocol.endpointWithModel(withModel, "y"))
    }

    @Test
    fun gender_instruction_matches_the_two_documented_sentences() {
        assertEquals(
            "请使用默认女声与用户交流",
            RealtimeProtocol.genderInstructionFor(RealtimeProtocol.VOICE_FEMALE)
        )
        assertEquals(
            "请使用默认男声与用户交流",
            RealtimeProtocol.genderInstructionFor(RealtimeProtocol.VOICE_MALE)
        )
        assertNull(RealtimeProtocol.genderInstructionFor("some-cloned-voice"))
    }

    @Test
    fun student_voice_ids_are_the_documented_ones() {
        assertEquals("qingchunshaonv", RealtimeProtocol.VOICE_FEMALE)
        assertEquals("wenrounansheng", RealtimeProtocol.VOICE_MALE)
    }

    @Test
    fun gender_instruction_is_appended_at_the_end() {
        val out = RealtimeProtocol.withGenderInstruction(
            "你是一个日语语伴。", RealtimeProtocol.VOICE_FEMALE
        )
        // 别在这里写转义换行：本机跨工具写文件会把 \\n 折成真换行，把 Kotlin 源码写坏。
        // 用常量拼出期望值，既避开这个坑，也把「追加在末尾」这件事讲得更直白。
        val newline = "\n"
        val expected = "你是一个日语语伴。" + newline + "请使用默认女声与用户交流"
        assertEquals(expected, out)
        assertTrue(out.endsWith("请使用默认女声与用户交流"))
    }

    @Test
    fun gender_instruction_is_not_duplicated() {
        val once = RealtimeProtocol.withGenderInstruction(
            "prompt", RealtimeProtocol.VOICE_FEMALE
        )
        assertEquals(once, RealtimeProtocol.withGenderInstruction(once, RealtimeProtocol.VOICE_FEMALE))
    }

    @Test
    fun cloned_voice_leaves_instructions_alone() {
        assertEquals(
            "prompt",
            RealtimeProtocol.withGenderInstruction("prompt", "my-cloned-voice")
        )
    }
}
