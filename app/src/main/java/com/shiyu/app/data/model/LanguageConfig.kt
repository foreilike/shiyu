package com.shiyu.app.data.model

/**
 * 一个语言下的 Provider 配置：对话 / 语音识别 / 语音合成 / 实时语音 四组。
 * realtime 组使用 ProviderConfig 的 baseUrl（wss://…/v1/realtime）/ apiKey / model / voice。
 */
data class LanguageConfig(
    val chat: ProviderConfig = ProviderConfig(),
    val asr: ProviderConfig = ProviderConfig(),
    val tts: ProviderConfig = ProviderConfig(),
    val realtime: ProviderConfig = ProviderConfig(),
    val realtimeOptions: RealtimeOptions = RealtimeOptions()
)
