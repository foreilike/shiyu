package com.shiyu.app.core.net

import org.json.JSONObject

/**
 * 火山方舟（Volcengine Ark）语音转写的线协议（纯 JVM，可单测）。
 *
 * **为什么需要这套**：stepfun 的 ASR 对**带中文口音的日语**会把语种判成中文，
 * 输出中文谐音（`おはようございます` → `哦，还有，给我唱野马写`），
 * 且 `language` / `prompt` / `hotwords` 三种参数实测都无法改变输出文字体系（§17.21.10）。
 *
 * 方舟这条路走的是**大模型**（豆包 Seed 系列多模态），能用指令指定输出文字体系，
 * 实测同一段录音加一句日语提示词即可得到正确的 `おはようございます`（§17.21.12）。
 *
 * **两步协议**（不是 OpenAI 的 `/audio/transcriptions`）：
 * ```
 * 1) POST {base}/files            multipart, purpose=user_data  → file_id（约 300ms）
 * 2) POST {base}/chat/completions input_audio(file_id) + 文本提示词 → 转写文本
 *    用完 best-effort DELETE {base}/files/{id}，否则每句话都在账号里堆一个文件
 * ```
 *
 * **实测（2026-09-28）**：上传后**无需等待文件处理**即可直接使用
 * （`status=processing` 也不影响），端到端约 2 秒。
 */
object ArkAsrWire {

    const val FILES_PATH = "/files"
    const val CHAT_PATH = "/chat/completions"

    /**
     * 默认模型 —— 必须是**支持音频输入**的多模态模型。
     * 账号里可用的三个（经 `/api/v3/models` 枚举）：
     * `doubao-seed-2-0-mini-260428`（实测最快 1.1s）、
     * `doubao-seed-2-0-lite-260428`、`doubao-seed-2-1-lite-260915`。
     */
    const val DEFAULT_MODEL = "doubao-seed-2-0-mini-260428"

    /**
     * **必须关掉思考模式**。实测：开着思考时同一段音频要 36~137 秒，
     * 关掉后降到 1~3 秒 —— 这是这条路能用于实时字幕的前提（§17.21.12）。
     */
    private const val THINKING_DISABLED = "disabled"

    /** 是否指向火山方舟。只认域名后缀，避免把自建/代理域名误判。 */
    fun isArkHost(baseUrl: String): Boolean {
        val host = baseUrl.substringAfter("://", "").substringBefore('/').lowercase()
        return host == "volces.com" || host.endsWith(".volces.com")
    }

    /**
     * 转写提示词：按目标语言要求**用该语言的文字**输出，并压掉一切额外输出。
     *
     * 这几句是实测出来的要点，缺一个都会退化：
     * - 不指定文字体系 → 返回中文谐音（`哦哈哟够咱一妈塞`）
     * - 不禁止解释/注音 → 返回一大段说明文字，还慢几十秒
     */
    fun transcribePrompt(language: String): String = when (language.trim().lowercase()) {
        "ja" -> "音频是日语。只输出日文转写文本本身（平假名、片假名、汉字），" +
            "不要用中文汉字谐音，不要翻译，不要解释、不要注音、不要加小标题、不要重复。" +
            "若音频里没有人声，只输出空字符串。"

        "en" -> "The audio is English. Output ONLY the English transcript itself. " +
            "No translation, no explanation, no headings. Output an empty string if no speech."

        else -> "只输出音频的转写文本本身，不要翻译，不要解释、不要加小标题、不要重复。" +
            "若音频里没有人声，只输出空字符串。"
    }

    /** 按扩展名给 MIME（方舟按 multipart 的 Content-Type 判类型） */
    fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }

    /** 第二步的请求体：音频引用 + 提示词 + 关思考 */
    fun buildChatBody(fileId: String, language: String, model: String): String {
        val content = org.json.JSONArray()
            .put(JSONObject().put("type", "input_audio")
                .put("input_audio", JSONObject().put("file_id", fileId)))
            .put(JSONObject().put("type", "text").put("text", transcribePrompt(language)))
        val message = JSONObject().put("role", "user").put("content", content)
        return JSONObject()
            .put("model", model)
            .put("thinking", JSONObject().put("type", THINKING_DISABLED))
            .put("messages", org.json.JSONArray().put(message))
            .toString()
    }

    /** 从上传响应里取 file_id：`{"id":"file-2026...","status":"processing",...}` */
    fun parseFileId(body: String): String? {
        val node = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val id = node.optString("id", "")
        return id.ifBlank { null }
    }

    /**
     * 从 chat/completions 响应里取转写文本：`{"choices":[{"message":{"content":"..."}}]}`。
     * 解析失败返回 null（调用方按坏响应处理）。
     */
    fun parseChatText(body: String): String? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val choices = root.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val message = first.optJSONObject("message") ?: return null
        return message.optString("content", "")
    }
}
