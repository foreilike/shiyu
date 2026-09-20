package com.langpartner.app.ai.mock

import com.langpartner.app.ai.AiResult
import com.langpartner.app.ai.ChatProvider
import com.langpartner.app.ai.ChatRequest
import com.langpartner.app.ai.SpeechToTextProvider
import com.langpartner.app.ai.TextToSpeechProvider
import java.io.File

/**
 * Mock 对话 Provider（§M2-6 / §M3-2）：未配置凭证时返回预置回复，
 * 保证对话链路仍能完整跑完一轮。预置文案由 core/lang 提供（含主标签/[ZH]/[FIX]，正文带 (mock)）。
 */
class MockChatProvider(private val reply: String) : ChatProvider {
    override suspend fun chat(request: ChatRequest): AiResult<String> = AiResult.Ok(reply)
}

/** Mock 语音识别：返回预置文本（真实链路走不到这里，仅为接口完整） */
class MockSttProvider(private val transcript: String) : SpeechToTextProvider {
    override suspend fun transcribe(audioFile: File, language: String): AiResult<String> =
        AiResult.Ok(transcript)
}

/** Mock 语音合成：返回空音频，调用方静默跳过朗读 */
class MockTtsProvider : TextToSpeechProvider {
    override suspend fun synthesize(text: String, voice: String?): AiResult<ByteArray> =
        AiResult.Ok(ByteArray(0))
}
