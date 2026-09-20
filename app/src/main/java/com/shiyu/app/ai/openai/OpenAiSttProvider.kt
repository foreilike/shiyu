package com.shiyu.app.ai.openai

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.SpeechToTextProvider
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpErrors
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.MultipartFile
import com.shiyu.app.core.net.OpenAiWire
import com.shiyu.app.data.model.ProviderConfig
import java.io.File
import java.io.IOException

/**
 * OpenAI 兼容语音识别 Provider（§5.3.2）：
 * POST BASE/audio/transcriptions，multipart 三字段：
 * file（audio.m4a / audio/mp4）、model、language（ja / en）。
 * 响应取 text 字段；缺失或非 JSON → BadResponse。
 */
class OpenAiSttProvider(
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
        // 按扩展名推断 mime / 文件名：默认 audio.m4a（§5.3.2），VAD 裁剪产物为 wav 也原生支持
        val ext = audioFile.extension.lowercase()
        val (fileName, mimeType) = when (ext) {
            "wav" -> "audio.wav" to "audio/wav"
            "mp3" -> "audio.mp3" to "audio/mpeg"
            "webm" -> "audio.webm" to "audio/webm"
            "ogg" -> "audio.ogg" to "audio/ogg"
            "flac" -> "audio.flac" to "audio/flac"
            else -> "audio.m4a" to "audio/mp4"
        }
        val call = HttpCall(
            method = "POST",
            url = base + "/audio/transcriptions",
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}"
            ),
            multipart = MultipartFile(
                fieldName = "file",
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes
            ),
            formFields = mapOf(
                "model" to config.model,
                "language" to language
            )
        )
        val raw = transport.execute(call)
        HttpErrors.classify(raw)?.let { return AiResult.Err(it) }
        val text = OpenAiWire.parseTranscriptionText(raw.body)
            ?: return AiResult.Err(AiError.BadResponse("missing text field"))
        return AiResult.Ok(text)
    }
}
