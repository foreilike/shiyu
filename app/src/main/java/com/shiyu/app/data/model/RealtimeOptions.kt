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
     * **实测定的值：1200。** 两个实测点卡住了区间：
     *
     * | 观测 | 阈值 | 结论 |
     * |---|---|---|
     * | 学习者自己说话 | 2500 | 不触发（太高）|
     * | 约 2 米外的人说话 | 500 | 触发（太低）|
     *
     * 按距离衰减估：2 米外的人声比对着手机说话低约 12 dB（幅度约 4 倍）。
     * 若 2 米外 ≈ 500，则本人 ≈ 2000 —— 正好解释了 2500 为什么连本人都唤不醒。
     * 1200 落在两者中间。
     *
     * ⚠️ **这个参数有原理性上限**：服务端 VAD 只测"有多响"，分不出"是谁在说"。
     * 因此任何能听见你说话的阈值，也都可能听见附近别人说话 —— 想根治需要声纹或波束成形，
     * 不在当前范围内。这里能做的只是把阈值调到"够得着本人、够不着旁人"的窄带里。
     *
     * 环境差异很大，所以留成了设置项（设置页「唤醒能量阈值」）：
     * 自己说话唤不醒就调低，旁人说话老误触发就调高。
     */
    val energyAwakenessThreshold: Int = DEFAULT_ENERGY_THRESHOLD,
    val prefixPaddingMs: Int = 500
) {
    companion object {
        /** 服务端 VAD 默认能量阈值，见 [energyAwakenessThreshold] 的注释 */
        const val DEFAULT_ENERGY_THRESHOLD = 1300
    }
}
