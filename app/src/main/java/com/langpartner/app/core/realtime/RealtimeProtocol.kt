package com.langpartner.app.core.realtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * stepfun realtime 线协议（纯逻辑，JVM 单测覆盖）。
 * 文档：wss://api.stepfun.com/v1/realtime，Authorization: Bearer <key>，
 * 音频 pcm16 + Base64；turn_detection = server_vad。
 *
 * 客户端事件：session.update / input_audio_buffer.append / commit / clear /
 *            conversation.item.create / response.create / response.cancel
 * 服务端事件：session.created / session.updated / input_audio_buffer.speech_started /
 *            speech_stopped / response.audio.delta|done / response.audio_transcript.delta|done /
 *            response.text.delta|done / conversation.item.input_audio_transcription.completed /
 *            response.done / error
 */
object RealtimeProtocol {

    private var seq = 0
    private fun nextId(): String = "evt_${System.currentTimeMillis()}_${seq++}"

    // ---------- 客户端 → 服务端 ----------

    fun sessionUpdate(
        instructions: String,
        voice: String,
        serverVad: Boolean,
        silenceDurationMs: Int,
        energyAwakenessThreshold: Int,
        prefixPaddingMs: Int,
        inputAudioFormat: String = "pcm16",
        outputAudioFormat: String = "pcm16"
    ): String {
        val session = JSONObject()
        session.put("modalities", JSONArray().put("text").put("audio"))
        session.put("instructions", instructions)
        session.put("voice", voice)
        session.put("input_audio_format", inputAudioFormat)
        session.put("output_audio_format", outputAudioFormat)
        if (serverVad) {
            session.put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("prefix_padding_ms", prefixPaddingMs)
                    .put("silence_duration_ms", silenceDurationMs)
                    .put("energy_awakeness_threshold", energyAwakenessThreshold)
            )
        }
        return envelope("session.update") { it.put("session", session) }
    }

    /** audio：原始 pcm16 字节（内部做 Base64；java.util.Base64 需 API 26+，minSdk 已 26） */
    fun appendAudio(pcm: ByteArray): String {
        val audio = Base64.getEncoder().encodeToString(pcm)
        return envelope("input_audio_buffer.append") { it.put("audio", audio) }
    }

    fun commit(): String = envelope("input_audio_buffer.commit") { }

    fun clear(): String = envelope("input_audio_buffer.clear") { }

    fun responseCreate(): String = envelope("response.create") { }

    fun responseCancel(): String = envelope("response.cancel") { }

    private inline fun envelope(type: String, fill: (JSONObject) -> Unit): String {
        val root = JSONObject()
        root.put("event_id", nextId())
        root.put("type", type)
        fill(root)
        return root.toString()
    }

    // ---------- 服务端 → 客户端 ----------

    sealed class ServerEvent {
        object SessionCreated : ServerEvent()
        object SessionUpdated : ServerEvent()
        object SpeechStarted : ServerEvent()
        object SpeechStopped : ServerEvent()
        /** AI 语音增量（pcm16 原始字节） */
        data class AudioDelta(val pcm: ByteArray) : ServerEvent() {
            override fun equals(other: Any?) = other is AudioDelta && other.pcm.contentEquals(pcm)
            override fun hashCode() = pcm.contentHashCode()
        }
        object AudioDone : ServerEvent()
        /** AI 语音转写增量 */
        data class TranscriptDelta(val text: String) : ServerEvent()
        object TranscriptDone : ServerEvent()
        /** 文本增量（部分服务端同时下发） */
        data class TextDelta(val text: String) : ServerEvent()
        /** 用户语音转写完成 */
        data class UserTranscript(val text: String) : ServerEvent()
        object ResponseDone : ServerEvent()
        data class ErrorEvent(val message: String, val code: String?) : ServerEvent()
        data class Unknown(val type: String) : ServerEvent()
    }

    fun parse(json: String): ServerEvent? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        return when (root.optString("type")) {
            "session.created" -> ServerEvent.SessionCreated
            "session.updated" -> ServerEvent.SessionUpdated
            "input_audio_buffer.speech_started" -> ServerEvent.SpeechStarted
            "input_audio_buffer.speech_stopped" -> ServerEvent.SpeechStopped
            "response.audio.delta" -> {
                val b64 = root.optString("delta")
                val pcm = try {
                    Base64.getDecoder().decode(b64)
                } catch (e: Exception) {
                    ByteArray(0)
                }
                ServerEvent.AudioDelta(pcm)
            }
            "response.audio.done" -> ServerEvent.AudioDone
            "response.audio_transcript.delta" -> ServerEvent.TranscriptDelta(root.optString("delta"))
            "response.audio_transcript.done" -> ServerEvent.TranscriptDone
            "response.text.delta" -> ServerEvent.TextDelta(root.optString("delta"))
            "conversation.item.input_audio_transcription.completed" ->
                ServerEvent.UserTranscript(root.optString("transcript"))
            "response.done" -> ServerEvent.ResponseDone
            "error" -> {
                val err = root.optJSONObject("error")
                ServerEvent.ErrorEvent(
                    message = err?.optString("message").takeIf { !it.isNullOrEmpty() }
                        ?: root.optString("message").ifEmpty { "realtime error" },
                    code = err?.optString("code")?.takeIf { it.isNotEmpty() }
                )
            }
            else -> ServerEvent.Unknown(root.optString("type"))
        }
    }
}
