package com.langpartner.app.core.net

import com.langpartner.app.ai.AiError

/**
 * 传输层的线协议数据结构（§5.2）。网络层单测通过注入假 HttpTransport 完成。
 */
data class MultipartFile(
    val fieldName: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MultipartFile) return false
        return fieldName == other.fieldName && fileName == other.fileName &&
            mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        (fieldName.hashCode() * 31 + fileName.hashCode()) * 31 + bytes.contentHashCode()
}

data class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val jsonBody: String? = null,
    val multipart: MultipartFile? = null,
    /** multipart 的附加文本字段（如 model / language）；非 multipart 请求忽略 */
    val formFields: Map<String, String> = emptyMap()
)

data class HttpRaw(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val ioError: String? = null,
    val binary: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRaw) return false
        return code == other.code && body == other.body && headers == other.headers &&
            ioError == other.ioError && binary.contentEquals(other.binary)
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + body.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (ioError?.hashCode() ?: 0)
        result = 31 * result + (binary?.contentHashCode() ?: 0)
        return result
    }
}

interface HttpTransport {
    suspend fun execute(call: HttpCall): HttpRaw
}

/**
 * HTTP 错误分类（§5.5，纯函数）。返回 null 表示成功（可继续解析业务体）。
 * 判定顺序严格固定：ioError(timeout/其它) → 401/403 → 404 → 429 → 5xx → 其它非 2xx。
 */
object HttpErrors {

    fun classify(raw: HttpRaw): AiError? {
        val io = raw.ioError
        if (!io.isNullOrEmpty()) {
            return if (io.contains("timeout", ignoreCase = true)) {
                AiError.Timeout(30000)
            } else {
                AiError.Network
            }
        }
        return when {
            raw.code == 401 || raw.code == 403 -> AiError.Unauthorized(raw.code)
            raw.code == 404 -> AiError.NotFound(404)
            raw.code == 429 -> AiError.RateLimited(429, parseRetryAfter(raw.headers))
            raw.code in 500..599 -> AiError.ServerError(raw.code, raw.body.take(200))
            raw.code !in 200..299 -> AiError.Unknown(raw.code, raw.body.take(200))
            else -> null
        }
    }

    /** Retry-After 大小写不敏感；能解析为正整数才填入，否则 null */
    private fun parseRetryAfter(headers: Map<String, String>): Long? {
        for ((key, value) in headers) {
            if (key.equals("Retry-After", ignoreCase = true)) {
                val n = value.trim().toLongOrNull()
                if (n != null && n > 0) return n
            }
        }
        return null
    }
}
