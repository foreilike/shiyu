package com.shiyu.app.core.realtime

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

    /**
     * step-audio-2-mini 只开放这两个音色（官方文档明确列出）。
     * 注意 [VOICE_MALE] 是**温柔男声** —— App 的语伴叫 yoyo / Emma，用它会声形不符，
     * 所以默认音色取 [VOICE_FEMALE]。
     */
    const val VOICE_FEMALE = "qingchunshaonv"  // 青春少女
    const val VOICE_MALE = "wenrounansheng"    // 温柔男声

    private var seq = 0
    private fun nextId(): String = "evt_${System.currentTimeMillis()}_${seq++}"

    // ---------- 连接参数拼装（纯字符串逻辑，单测覆盖） ----------

    /**
     * `model` 是官方文档里的**必填查询参数**，而设置页把它存在 model 字段里、
     * 不体现在 baseUrl 上。不拼上去握手会被拒（或落到非预期的默认模型）。
     * 已经带了 model= 的 URL 原样返回，不重复追加。
     */
    fun endpointWithModel(baseUrl: String, model: String): String {
        if (model.isBlank()) return baseUrl
        if (baseUrl.contains("model=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return baseUrl + sep + "model=" + model.trim()
    }

    /**
     * step-audio-2-mini 要求**在 instructions 结尾追加一句默认性别提示**，
     * 否则模型不知道用哪个性别的声音。原文措辞只有这两句，别自己改写。
     * 其它音色（克隆音色等）不需要，返回 null。
     */
    fun genderInstructionFor(voice: String): String? = when (voice) {
        VOICE_FEMALE -> "请使用默认女声与用户交流"
        VOICE_MALE -> "请使用默认男声与用户交流"
        else -> null
    }

    /** 把性别提示追加到 systemPrompt 末尾；已经有就不重复追加 */
    /**
     * 把性别提示追加到 systemPrompt 末尾；已经有就不重复追加。
     *
     * **只在 step-audio-2-mini 系上追加**：这句要求是官方文档针对该模型写的，
     * 换到别的模型（如 stepaudio-3-realtime-preview）没有这条约束，无脑追加可能反而干扰。
     * 音色 ID 恰好重名，不代表模型规则相同。
     */
    fun withGenderInstruction(instructions: String, voice: String, model: String): String {
        if (!needsVoiceGenderLine(model)) return instructions
        val line = genderInstructionFor(voice) ?: return instructions
        if (instructions.contains(line)) return instructions
        return instructions.trimEnd() + "\n" + line
    }

    /** 需要用「性别提示句」配音色的模型（目前已知只有 step-audio-2-mini 系） */
    private fun needsVoiceGenderLine(model: String): Boolean =
        model.contains("step-audio-2-mini", ignoreCase = true)

    // ---------- 客户端 → 服务端 ----------

    fun sessionUpdate(
        instructions: String,
        voice: String,
        serverVad: Boolean,
        silenceDurationMs: Int,
        energyAwakenessThreshold: Int,
        prefixPaddingMs: Int,
        inputAudioFormat: String = "pcm16",
        outputAudioFormat: String = "pcm16",
        inputLanguage: String? = null
    ): String {
        val session = JSONObject()
        session.put("modalities", JSONArray().put("text").put("audio"))
        session.put("instructions", instructions)
        session.put("voice", voice)
        session.put("input_audio_format", inputAudioFormat)
        session.put("output_audio_format", outputAudioFormat)
        // 用户侧转写（ASR）的语种。**不传就会走服务端默认** —— 中文厂商的默认一般是 zh，
        // 于是「用户说日语、气泡里显示中文」（真机实测）。显式给语种才对得上当前学习语言。
        if (!inputLanguage.isNullOrBlank()) {
            session.put(
                "input_audio_transcription",
                JSONObject().put("language", inputLanguage.trim())
            )
        }
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

    /**
     * 以**文字**提交一条用户消息（打字输入用）。
     *
     * 官方协议的 `conversation.item.create` 支持 `input_text` 内容块，
     * 发完再补一个 `response.create` 触发模型回应 —— 这样打字和说话走的是同一条会话，
     * AI 回复照样带语音和字幕，不需要另开一条纯文字链路。
     */
    fun createUserTextItem(text: String): String = envelope("conversation.item.create") { root ->
        val content = JSONArray().put(
            JSONObject().put("type", "input_text").put("text", text)
        )
        root.put(
            "item",
            JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", content)
        )
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
        /** 新一轮响应开始（用于关闭打断后的音频丢弃窗口） */
        object ResponseStarted : ServerEvent()
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
            "response.created" -> ServerEvent.ResponseStarted
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
