package com.shiyu.app.core.srs

/**
 * 今日复习配额（纯逻辑，可单测）。
 *
 * 存在的理由：首页与复习页都要显示「今日待复习 N」，但两者的数据来源天然不同 ——
 * 首页每次进来都重新排卡（未学过的卡会补满配额），复习页则是进页时排好的队列在递减。
 * 不收口的话会出现「首页说 20、复习页说 19」这种自相矛盾的显示。
 *
 * 统一口径：**今日还该复习多少张 = 配额减去今天已复习的，再受当前可排卡数的上限约束。**
 * 两边都调这个函数，重启 App 也算得出同一个数。
 */
object DailyQuota {

    /** 每天排卡上限，同时是 StudyStore.selectToday 的默认 limit */
    const val CARDS_PER_DAY = 20

    /**
     * @param availableCards 当前还能排出来的卡数（首页 = selectToday 的结果数；
     *                       复习页 = 队列剩余数）
     * @param reviewedToday  今天已经完成评分的张数（来自 DailyStatsStore）
     */
    fun remaining(availableCards: Int, reviewedToday: Int): Int {
        val quotaLeft = (CARDS_PER_DAY - reviewedToday).coerceAtLeast(0)
        return minOf(availableCards.coerceAtLeast(0), quotaLeft)
    }
}
