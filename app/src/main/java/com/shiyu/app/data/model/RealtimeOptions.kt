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
     * 实测定的值：**500**。原先把 2500 一路降到 200 才让服务端听见说话，但 200 又太低 ——
     * 它压在环境底噪之上（实测底噪在 -45 dBFS 上下浮动），屋里有点动静就被判成"有人在说话"，
     * 出现「没人开口 AI 却自己聊起来」的情况（识别出的是噪音，写出来是乱码）。
     *
     * 换算参考（16 位幅度）：底噪 -45dBFS ≈ 184，正常说话 -34~-21dBFS ≈ 650~2900。
     * 500 卡在两者之间 —— 说话够得着，底噪够不着。
     *
     * 这个值很吃设备和环境，所以留成了设置项（设置页「唤醒能量阈值」）：
     * 太迟钝就调低，太灵敏（老是自动触发）就调高。
     */
    val energyAwakenessThreshold: Int = DEFAULT_ENERGY_THRESHOLD,
    val prefixPaddingMs: Int = 500
) {
    companion object {
        /** 服务端 VAD 默认能量阈值，见 [energyAwakenessThreshold] 的注释 */
        const val DEFAULT_ENERGY_THRESHOLD = 500
    }
}
