package com.shiyu.app.ai.openai

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.SpeechToTextProvider
import com.shiyu.app.ai.SttContract
import com.shiyu.app.core.net.ArkAsrWire
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpErrors
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.MultipartFile
import com.shiyu.app.data.model.ProviderConfig
import java.io.File
import java.io.IOException

/**
 * 火山方舟语音转写 Provider（§17.21.13）。
 *
 * 面向的场景：**学习者说日语**时 stepfun 的 ASR 会把语种判成中文、输出中文谐音
 * （`おはようございます` → `哦，还有，给我唱野马写`），三条参数路径实测都救不回来。
 * 方舟走的是大模型，能用指令指定输出文字体系，实测同一段录音即得正确的
 * `おはようございます`。
 *
 * **两步协议**（`ArkAsrWire` 有详解）：
 * 1. `POST {base}/files` 传音频 → `file_id`（约 300ms，**无需等处理完成**）
 * 2. `POST {base}/chat/completions` 带 `input_audio` + 语言提示词 → 文本（约 1~2s）
 * 3. `DELETE {base}/files/{id}` best-effort 清掉，避免账号里堆文件
 *
 * **必须关思考模式**：实测开着时同一段音频要 36~137 秒，关掉后 1~3 秒。
 */
class ArkSttProvider(
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
        val model = config.model.ifBlank { ArkAsrWire.DEFAULT_MODEL }

        // ---- 第一步：上传音频拿 file_id ----
        val upload = transport.execute(
            HttpCall(
                method = "POST",
                url = base + ArkAsrWire.FILES_PATH,
                headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                multipart = MultipartFile(
                    fieldName = "file",
                    fileName = audioFile.name,
                    mimeType = ArkAsrWire.mimeTypeFor(audioFile.name),
                    bytes = bytes
                ),
                formFields = mapOf("purpose" to "user_data")
            )
        )
        HttpErrors.classify(upload)?.let { return AiResult.Err(it) }
        val fileId = ArkAsrWire.parseFileId(upload.body)
            ?: return AiResult.Err(AiError.BadResponse("upload returned no file id"))

        // 无论后面成功与否都要清掉：每句话都上传，不清会在账号里越堆越多
        try {
            // ---- 第二步：让多模态模型转写 ----
            val chat = transport.execute(
                HttpCall(
                    method = "POST",
                    url = base + ArkAsrWire.CHAT_PATH,
                    headers = mapOf(
                        "Authorization" to "Bearer ${config.apiKey}",
                        "Content-Type" to "application/json"
                    ),
                    jsonBody = ArkAsrWire.buildChatBody(fileId, language, model)
                )
            )
            HttpErrors.classify(chat)?.let { return AiResult.Err(it) }
            val text = ArkAsrWire.parseChatText(chat.body)?.trim().orEmpty()
            if (text.isEmpty()) {
                return AiResult.Err(AiError.BadResponse(SttContract.EMPTY_TRANSCRIPT))
            }
            return AiResult.Ok(text)
        } finally {
            deleteQuietly(base, fileId)
        }
    }

    /** 删除上传的文件；失败**不影响**转写结果，所以忽略一切异常 */
    private suspend fun deleteQuietly(base: String, fileId: String) {
        try {
            transport.execute(
                HttpCall(
                    method = "DELETE",
                    url = "$base${ArkAsrWire.FILES_PATH}/$fileId",
                    headers = mapOf("Authorization" to "Bearer ${config.apiKey}")
                )
            )
        } catch (e: Exception) {
            // 清理是尽力而为：留一个文件在远端，远好过让整次转写失败
        }
    }
}
