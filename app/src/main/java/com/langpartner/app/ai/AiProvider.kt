package com.langpartner.app.ai

import com.langpartner.app.data.model.ChatMessage

/**
 * AI Provider 抽象层（§5.2）：AiError/AiResult/三个能力接口 + 每语言一个工厂。
 * ChatMessage 定义在 data.model（§5.1 文件布局），此处以类型别名暴露。
 */
typealias ChatMessage = com.langpartner.app.data.model.ChatMessage

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7
)

sealed class AiError {
    /** 连接失败 / DNS / 无网络 */
    object Network : AiError()
    data class Timeout(val ms: Long) : AiError()
    /** 401 / 403 */
    data class Unauthorized(val code: Int) : AiError()
    /** 404 */
    data class NotFound(val code: Int) : AiError()
    /** 429 */
    data class RateLimited(val code: Int, val retryAfterSeconds: Long?) : AiError()
    /** 5xx */
    data class ServerError(val code: Int, val body: String) : AiError()
    /** 2xx 但 JSON 不合预期 */
    data class BadResponse(val detail: String) : AiError()
    data class Unknown(val code: Int, val detail: String) : AiError()
}

sealed class AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>()
    data class Err(val error: AiError) : AiResult<Nothing>()
}

/** 对话 Provider：返回助手正文 */
interface ChatProvider {
    suspend fun chat(request: ChatRequest): AiResult<String>
}

/** 语音识别 Provider。language 取当前语言模块的 sttLanguage（"ja" / "en"） */
interface SpeechToTextProvider {
    suspend fun transcribe(audioFile: java.io.File, language: String): AiResult<String>
}

/** 语音合成 Provider：返回音频字节 */
interface TextToSpeechProvider {
    suspend fun synthesize(text: String, voice: String?): AiResult<ByteArray>
}

/** 每个语言一个实例 */
interface AiProviderFactory {
    fun chat(): ChatProvider
    fun stt(): SpeechToTextProvider
    fun tts(): TextToSpeechProvider

    /**
     * 实时语音 Provider。instructions 为当前场景的 system prompt（realtime 会话指令）。
     * 未配置 REALTIME 凭证时返回 Mock 实现（连接后推一条预置转写，不推流）。
     */
    fun realtime(instructions: String): RealtimeVoiceProvider
}
