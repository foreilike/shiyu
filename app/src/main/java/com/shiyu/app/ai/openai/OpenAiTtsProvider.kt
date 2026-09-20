package com.shiyu.app.ai.openai

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.TextToSpeechProvider
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpErrors
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.OpenAiWire
import com.shiyu.app.data.model.ProviderConfig

/**
 * OpenAI 兼容语音合成 Provider（§5.3.3）：
 * POST BASE/audio/speech，响应二进制音频（audio/mpeg）；
 * voice 为空回落 "alloy"；200 但 binary 为空 → BadResponse。
 */
class OpenAiTtsProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport
) : TextToSpeechProvider {

    override suspend fun synthesize(text: String, voice: String?): AiResult<ByteArray> {
        val base = BaseUrlNormalizer.normalize(config.baseUrl)
            ?: return AiResult.Err(AiError.BadResponse("invalid baseUrl"))
        val effectiveVoice = voice?.takeIf { it.isNotBlank() } ?: config.voice
        val call = HttpCall(
            method = "POST",
            url = base + "/audio/speech",
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json"
            ),
            jsonBody = OpenAiWire.buildTtsBody(
                model = config.model,
                input = text,
                voice = effectiveVoice
            )
        )
        val raw = transport.execute(call)
        HttpErrors.classify(raw)?.let { return AiResult.Err(it) }
        val binary = raw.binary
        if (binary == null || binary.isEmpty()) {
            return AiResult.Err(AiError.BadResponse("empty audio body"))
        }
        return AiResult.Ok(binary)
    }
}
