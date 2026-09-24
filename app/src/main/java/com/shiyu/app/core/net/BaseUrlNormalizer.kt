package com.shiyu.app.core.net

/**
 * baseUrl 规范化（§5.4）。按顺序：
 * 1. trim
 * 2. 不含 "://" 补 "https://"
 * 3. 只允许 https / wss（http:// 与 ws:// 返回 null = 失败）
 * 4. 去掉末尾所有的 "/"
 * 5. 以三个 endpoint 后缀结尾的截掉后缀（仅 https 路径；wss 端点保持原样）
 */
object BaseUrlNormalizer {

    private val SUFFIXES = listOf(
        "/chat/completions",
        "/audio/transcriptions",
        "/audio/speech",
        // stepfun 原生 ASR（§17.21.5）：用户常直接把文档里的完整端点粘进来
        "/audio/asr/sse"
    )

    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (!s.contains("://")) {
            s = "https://$s"
        }
        if (s.startsWith("http://", ignoreCase = true)) return null
        if (s.startsWith("ws://", ignoreCase = true)) return null
        val isWss = s.startsWith("wss://", ignoreCase = true)
        if (!isWss && !s.startsWith("https://", ignoreCase = true)) return null
        if (isWss) {
            // scheme 统一小写，避免下游 URL 解析差异
            s = "wss://" + s.substring(6)
        } else if (s.startsWith("https://", ignoreCase = true) && !s.startsWith("https://")) {
            s = "https://" + s.substring(8)
        }
        while (s.endsWith("/")) {
            s = s.dropLast(1)
        }
        if (isWss) return s // 实时端点（wss://…/v1/realtime）原样保留
        for (suffix in SUFFIXES) {
            if (s.endsWith(suffix)) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        while (s.endsWith("/")) {
            s = s.dropLast(1)
        }
        return s
    }
}
