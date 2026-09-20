package com.shiyu.app.data.model

/**
 * 一天的学习活动记录（拾语「今日 / 我的」两屏的真实数据来源）。
 *
 * 口径说明（避免与既有统计混淆）：
 * - [studySeconds]：复习页处于前台且未被遮挡的秒数，只在真正看卡片时累加；
 * - [voiceSeconds]：实时语音「开始对话 → 结束对话」之间的秒数；
 * - [reviewedCards]：完成一次 SM-2 评分算一张，不是看过几张；
 * - [chatTurns]：回合制对话里用户发出的一条算一轮，与 SessionStore 口径一致。
 */
data class DayStat(
    val epochDay: Long = 0L,
    val studySeconds: Long = 0L,
    val voiceSeconds: Long = 0L,
    val reviewedCards: Int = 0,
    val chatTurns: Int = 0
) {
    /** 这一天投入的总秒数 = 看卡 + 开口 */
    val totalSeconds: Long get() = studySeconds + voiceSeconds

    val totalMinutes: Int get() = (totalSeconds / 60L).toInt()

    /** 这一天是否算「学过」：任何一项有量就算，避免开了 App 不做事也计入连续天数 */
    val active: Boolean
        get() = totalSeconds > 0L || reviewedCards > 0 || chatTurns > 0
}
