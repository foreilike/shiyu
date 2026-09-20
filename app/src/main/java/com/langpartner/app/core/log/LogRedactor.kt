package com.langpartner.app.core.log

/**
 * 密钥脱敏（§6.2）：<8 字符 → "****"；否则 take(3) + "****" + takeLast(4)。
 * 所有涉及请求头/请求体的日志必须经过此函数。
 */
object LogRedactor {

    fun mask(secret: String): String {
        if (secret.length < 8) return "****"
        return secret.take(3) + "****" + secret.takeLast(4)
    }
}
