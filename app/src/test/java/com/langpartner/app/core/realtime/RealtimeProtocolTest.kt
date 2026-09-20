package com.langpartner.app.core.realtime

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
}
