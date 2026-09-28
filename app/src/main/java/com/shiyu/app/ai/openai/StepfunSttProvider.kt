package com.shiyu.app.ai.openai

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.SpeechToTextProvider
import com.shiyu.app.ai.SttContract
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpErrors
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.StepfunAsrWire
import com.shiyu.app.data.model.ProviderConfig
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * stepfun 原生语音识别 Provider（§17.21.5）。
 *
 * 与 [OpenAiSttProvider] 的区别：**协议完全不同**。
 * stepfun 没有 OpenAI 的 `/audio/transcriptions`（实测 404），要用它自己的
 * `POST {base}/audio/asr/sse`：请求体是 JSON + base64，响应是 SSE 文本流。
 *
 * 之所以单独开一个类而不是改 [OpenAiSttProvider]：ASR 组对外承诺的是
 * 「Whisper 兼容」，不少服务（Groq / OpenAI / 自建 faster-whisper）都实现
 * `/audio/transcriptions`，那套必须原样保留。本类只在 baseUrl 指向 stepfun 时启用
 * （见 `AiProviderRegistry`）。
 */
class StepfunSttProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport
) : SpeechToTextProvider {

    override suspend fun transcribe(audioFile: File, language: String): AiResult<String> {
        val base = BaseUrlNormalizer.normalize(config.baseUrl)
            ?: return AiResult.Err(AiError.BadResponse("invalid baseUrl"))
        val bytes = try {
            audioFile.readBytes()
        } catch (e: IOException) {
            return AiResult.Err(AiError.Network)
        }
        if (bytes.isEmpty()) {
            return AiResult.Err(AiError.BadResponse("empty audio file"))
        }

        val body = StepfunAsrWire.buildAsrBody(
            base64Audio = Base64.getEncoder().encodeToString(bytes),
            language = language,
            model = config.model,
            formatType = StepfunAsrWire.formatTypeFor(audioFile.extension)
        )
        val call = HttpCall(
            method = "POST",
            url = base + StepfunAsrWire.ASR_SSE_PATH,
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream"
            ),
            jsonBody = body
        )
        val raw = transport.execute(call)
        HttpErrors.classify(raw)?.let { return AiResult.Err(it) }

        // 服务端会以「HTTP 200 + SSE 里带 error」的方式报错（例如 model 无权访问），
        // 只看状态码会漏 —— 必须先查 SSE 错误事件
        StepfunAsrWire.parseSseError(raw.body)?.let { msg ->
            return AiResult.Err(AiError.BadResponse(msg))
        }

        val text = StepfunAsrWire.parseSseTranscript(raw.body)
        if (text.isBlank()) {
            // 识别完了但没内容：多半是音频里没人声，或音频格式服务端解不出来。
            // 明确报错，别让 UI 回填一个空串（那看起来像"识别了个寂寞"）
            return AiResult.Err(AiError.BadResponse(SttContract.EMPTY_TRANSCRIPT))
        }
        return AiResult.Ok(text)
    }
}
