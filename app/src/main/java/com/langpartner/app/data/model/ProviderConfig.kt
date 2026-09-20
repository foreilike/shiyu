package com.langpartner.app.data.model

/**
 * 一组 AI Provider 配置（对话 / 语音识别 / 语音合成 共用同一结构）。
 * voice 仅语音合成组使用。
 */
data class ProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    val enabled: Boolean = false
)
