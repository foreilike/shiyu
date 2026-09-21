package com.shiyu.app.data.model

/**
 * 一天的学习活动记录（拾语「今日 / 我的」两屏的真实数据来源）。
 *
 * 口径说明（避免与既有统计混淆）：
 * - [studySeconds]：复习页处于前台且未被遮挡的秒数，只在真正看卡片时累加；
 * - [voiceSeconds]：实时语音「开始对话 → 结束对话」之间的秒数；
 * - [reviewedCards]：**当天全部语言**完成 SM-2 评分的张数，用于首页的活动小结；
 * - [reviewedByLang]：同上但按语言分桶（tag → 张数），用于「今日待复习」的配额计算；
 * - [chatTurns]：回合制对话里用户发出的一条算一轮，与 SessionStore 口径一致。
 *
 * [reviewedCards] 与 [reviewedByLang] 是**两个用途**，不是冗余：前者是"今天做了多少"的活动
 * 总量（跨语言），后者是"这门语言今天用掉了多少配额"。混用会导致切语言时待复习张数互相影响。
 */
data class DayStat(
    val epochDay: Long = 0L,
    val studySeconds: Long = 0L,
    val voiceSeconds: Long = 0L,
    val reviewedCards: Int = 0,
    val reviewedByLang: Map<String, Int> = emptyMap(),
    val chatTurns: Int = 0
) {
    /** 这一天投入的总秒数 = 看卡 + 开口 */
    val totalSeconds: Long get() = studySeconds + voiceSeconds

    val totalMinutes: Int get() = (totalSeconds / 60L).toInt()

    /** 这一天是否算「学过」：任何一项有量就算，避免开了 App 不做事也计入连续天数 */
    val active: Boolean
        get() = totalSeconds > 0L || reviewedCards > 0 || chatTurns > 0

    /**
     * 某门语言今天已复习的张数。
     * 老文件（v1 早期）没有 reviewedByLang 字段时返回 0 —— 本 App 刚发布，
     * 不存在需要精确回填的历史数据。
     */
    fun reviewedIn(langTag: String): Int = reviewedByLang[langTag] ?: 0
}
