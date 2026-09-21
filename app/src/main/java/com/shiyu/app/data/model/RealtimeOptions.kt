package com.shiyu.app.data.model

/**
 * 实时语音（Realtime）附加参数（stepfun realtime 协议，session.turn_detection）。
 * 仅 REALTIME 组使用；默认值对齐官方文档：server_vad 开、静音 100ms 断句、
 * 唤醒能量阈值 2500（0–5000）、前置填充 500ms。
 */
data class RealtimeOptions(
    val serverVad: Boolean = true,
    val silenceDurationMs: Int = 100,
    /**
     * 服务端 VAD 的能量阈值。**这里刻意不用官方默认的 2500。**
     *
     * 2500 对手机麦克风偏高：实测这台魅族 22 说话时电平在 -45 ~ -21 dBFS，
     * 换算成 16 位幅度约 184 ~ 2900，而 VAD 判的是短时均方根（多数帧只有几百），
     * 2500 意味着服务端**永远判定「没人说话」**——不报错、不回应，表现为
     * 「连上了、麦克风在采集、AI 一声不吭」，极难排查（2026-09-21 实际踩过）。
     *
     * 200 与端上门控的 -45 dBFS 大致同档，两边灵敏度一致。环境嘈杂时可往上调。
     */
    val energyAwakenessThreshold: Int = 200,
    val prefixPaddingMs: Int = 500
)
