package com.shiyu.app.ui

import android.content.Context
import com.shiyu.app.R
import com.shiyu.app.ai.AiError

/**
 * AiError → 中文提示映射（§5.5：每种错误必须可区分，禁止统一成"网络错误"）。
 */
object AiErrorMessages {

    fun describe(context: Context, error: AiError): String = when (error) {
        is AiError.Network -> context.getString(R.string.err_network)
        is AiError.Timeout -> context.getString(R.string.err_timeout)
        is AiError.Unauthorized -> context.getString(R.string.err_unauthorized)
        is AiError.NotFound -> context.getString(R.string.err_not_found)
        is AiError.RateLimited ->
            error.retryAfterSeconds?.let { context.getString(R.string.err_rate_limited_seconds, it.toInt()) }
                ?: context.getString(R.string.err_rate_limited)
        is AiError.ServerError -> context.getString(R.string.err_server, error.code)
        // 带上了服务端原文就显示原文 —— 实时语音连上后服务端报错时，
        // 只显示"响应格式异常"等于什么也没说，排不了障。
        is AiError.BadResponse -> error.detail.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.err_bad_response_detail, it) }
            ?: context.getString(R.string.err_bad_response)
        is AiError.Unknown -> context.getString(R.string.err_unknown, error.code)
    }

    /** 「测试连接」按钮的结果提示（与 §5.5 的 UI 提示口径一致） */
    fun describeTest(context: Context, error: AiError?): String = when (error) {
        null -> context.getString(R.string.test_success)
        is AiError.Network -> context.getString(R.string.test_failed_network)
        is AiError.Timeout -> context.getString(R.string.test_failed_network)
        is AiError.Unauthorized -> context.getString(R.string.test_failed_key)
        is AiError.NotFound -> context.getString(R.string.test_failed_address)
        is AiError.RateLimited -> context.getString(R.string.test_failed_rate)
        is AiError.ServerError -> context.getString(R.string.test_failed_server)
        is AiError.BadResponse -> context.getString(R.string.test_failed_format)
        is AiError.Unknown -> context.getString(R.string.test_failed_generic)
    }
}
