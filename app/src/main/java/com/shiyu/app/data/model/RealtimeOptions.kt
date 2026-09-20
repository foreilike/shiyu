package com.shiyu.app.data.model

/**
 * 实时语音（Realtime）附加参数（stepfun realtime 协议，session.turn_detection）。
 * 仅 REALTIME 组使用；默认值对齐官方文档：server_vad 开、静音 100ms 断句、
 * 唤醒能量阈值 2500（0–5000）、前置填充 500ms。
 */
data class RealtimeOptions(
    val serverVad: Boolean = true,
    val silenceDurationMs: Int = 100,
    val energyAwakenessThreshold: Int = 2500,
    val prefixPaddingMs: Int = 500
)
