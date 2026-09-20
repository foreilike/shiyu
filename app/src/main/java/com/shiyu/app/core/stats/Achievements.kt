package com.shiyu.app.core.stats

/**
 * 成就判定（纯逻辑，可单测）。
 *
 * 每一条都由真实数据算出来，没有"演示用"的占位徽章；未达成的条目也给出
 * current/target，让 UI 能画进度而不是只显示一个灰块。
 */
object Achievements {

    enum class Id { FIRST_VOICE, STREAK_7, WORDS_100, WORDS_200, HOURS_10, TURNS_100 }

    data class Badge(
        val id: Id,
        val current: Long,
        val target: Long
    ) {
        val unlocked: Boolean get() = current >= target

        /** 0..100，供进度条使用 */
        val percent: Int
            get() = if (target <= 0L) 100
            else ((current.coerceAtMost(target) * 100L) / target).toInt()
    }

    /** 判定成就所需的全部输入，调用方从三个 store 聚合后传进来 */
    data class Input(
        val voiceSeconds: Long = 0L,
        val streakDays: Int = 0,
        val masteredWords: Int = 0,
        val totalSeconds: Long = 0L,
        val totalTurns: Int = 0
    )

    /** 固定顺序返回全部 6 条，UI 自行决定先展示已解锁的 */
    fun evaluate(input: Input): List<Badge> = listOf(
        Badge(Id.FIRST_VOICE, if (input.voiceSeconds > 0L) 1L else 0L, 1L),
        Badge(Id.STREAK_7, input.streakDays.toLong(), 7L),
        Badge(Id.WORDS_100, input.masteredWords.toLong(), 100L),
        Badge(Id.WORDS_200, input.masteredWords.toLong(), 200L),
        Badge(Id.HOURS_10, input.totalSeconds, 10L * 3600L),
        Badge(Id.TURNS_100, input.totalTurns.toLong(), 100L)
    )
}
