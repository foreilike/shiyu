package com.shiyu.app.core.srs

import java.math.BigDecimal
import java.math.RoundingMode

data class CardState(val easeFactor: Double = 2.5, val reps: Int = 0, val intervalDays: Long = 0)

data class ReviewOutcome(val state: CardState, val dueEpochDay: Long)

/**
 * SM-2 间隔重复（§5.8）。严格两步顺序：
 * 第 1 步用旧 ef 算新 ef'（下限 1.3，保留 2 位小数）；
 * 第 2 步算新间隔与新重复次数（乘法用【旧 ef】）。
 * q < 3 时 n'=0、I=1，但 ef 仍按第 1 步公式更新。
 */
object Sm2Scheduler {

    fun review(state: CardState, quality: Int, todayEpochDay: Long): ReviewOutcome {
        val oldEf = state.easeFactor

        // 第 1 步：新易度因子
        var newEf = oldEf + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
        newEf = round2(newEf)
        if (newEf < 1.3) newEf = 1.3

        // 第 2 步：新间隔与重复次数（乘法用旧 ef）
        val newReps: Int
        val intervalDays: Long
        if (quality < 3) {
            newReps = 0
            intervalDays = 1
        } else {
            newReps = state.reps + 1
            intervalDays = when (newReps) {
                1 -> 1
                2 -> 6
                else -> {
                    val raw = roundHalfUp(state.intervalDays * oldEf)
                    if (raw < 1) 1 else raw
                }
            }
        }

        val newState = CardState(
            easeFactor = newEf,
            reps = newReps,
            intervalDays = intervalDays
        )
        return ReviewOutcome(newState, todayEpochDay + intervalDays)
    }

    private fun round2(x: Double): Double =
        BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun roundHalfUp(x: Double): Long =
        BigDecimal.valueOf(x).setScale(0, RoundingMode.HALF_UP).toLong()
}
