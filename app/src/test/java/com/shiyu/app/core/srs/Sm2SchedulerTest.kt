package com.shiyu.app.core.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sm2Scheduler（§5.8）：两步顺序、EF 下限、间隔序列 */
class Sm2SchedulerTest {

    private val initial = CardState(easeFactor = 2.5, reps = 0, intervalDays = 0)

    @Test
    fun q4_sequence_is_1_6_15_38() {
        var state = initial
        val intervals = mutableListOf<Long>()
        for (i in 1..4) {
            val outcome = Sm2Scheduler.review(state, 4, 20450L)
            intervals.add(outcome.state.intervalDays)
            state = outcome.state
        }
        assertEquals(listOf(1L, 6L, 15L, 38L), intervals)
    }

    @Test
    fun due_epoch_day_is_today_plus_interval() {
        val outcome = Sm2Scheduler.review(initial, 4, 20450L)
        assertEquals(20451L, outcome.dueEpochDay)
    }

    @Test
    fun q_below_3_resets_reps_and_interval() {
        val state = CardState(easeFactor = 2.4, reps = 5, intervalDays = 30)
        val outcome = Sm2Scheduler.review(state, 2, 20450L)
        assertEquals(0, outcome.state.reps)
        assertEquals(1L, outcome.state.intervalDays)
        assertEquals(20451L, outcome.dueEpochDay)
        // q<3 时 EF 仍按公式更新：2.4 + (0.1 - 3*0.14) = 2.08
        assertEquals(2.08, outcome.state.easeFactor, 1e-9)
    }

    @Test
    fun q0_updates_ef_and_floors_at_1_3() {
        var state = initial
        // e5q0: 2.5 -> 1.7 -> 0.9(floor 1.3) -> 1.3
        state = Sm2Scheduler.review(state, 0, 1L).state
        assertEquals(1.7, state.easeFactor, 1e-9)
        state = Sm2Scheduler.review(state, 0, 2L).state
        assertEquals(1.3, state.easeFactor, 1e-9)
        state = Sm2Scheduler.review(state, 0, 3L).state
        assertEquals(1.3, state.easeFactor, 1e-9)
    }

    @Test
    fun q1_q2_ef_changes() {
        // q=1: 2.5 + (0.1 - 4*(0.08+4*0.02)) = 2.5 + (0.1 - 0.64) = 1.96
        assertEquals(1.96, Sm2Scheduler.review(initial, 1, 1L).state.easeFactor, 1e-9)
        // q=2: 2.5 + (0.1 - 3*(0.08+3*0.02)) = 2.5 + (0.1 - 0.42) = 2.18
        assertEquals(2.18, Sm2Scheduler.review(initial, 2, 1L).state.easeFactor, 1e-9)
    }

    @Test
    fun interval_uses_old_ease_factor() {
        // 第 3 次复习（n'=3）时应 round(intervalDays * 旧 ef)
        // state: reps=2, interval=6, ef=2.5, q=3 → ef'=2.36，I=round(6*2.5)=15（用旧 ef）
        val state = CardState(easeFactor = 2.5, reps = 2, intervalDays = 6)
        val outcome = Sm2Scheduler.review(state, 3, 20450L)
        assertEquals(15L, outcome.state.intervalDays)
        assertEquals(2.36, outcome.state.easeFactor, 1e-9)
        assertEquals(3, outcome.state.reps)
    }

    @Test
    fun interval_half_up_rounding() {
        // 3 * 2.5 = 7.5 → 四舍五入为 8
        val state = CardState(easeFactor = 2.5, reps = 2, intervalDays = 3)
        val outcome = Sm2Scheduler.review(state, 4, 1L)
        assertEquals(8L, outcome.state.intervalDays)
    }

    @Test
    fun second_review_interval_is_six() {
        val state = CardState(easeFactor = 2.5, reps = 1, intervalDays = 1)
        assertEquals(6L, Sm2Scheduler.review(state, 4, 1L).state.intervalDays)
    }

    @Test
    fun ef_rounds_to_two_decimals() {
        // q=5: 2.5 + (0.1 - 0*0.08) = 2.6
        assertEquals(2.6, Sm2Scheduler.review(initial, 5, 1L).state.easeFactor, 1e-9)
        // 非两位小数的中间值会四舍五入到 2 位
        val state = CardState(easeFactor = 2.51, reps = 2, intervalDays = 7)
        // q=3: 2.51 + (0.1 - 2*0.12) = 2.51 - 0.14 = 2.37
        assertEquals(2.37, Sm2Scheduler.review(state, 3, 1L).state.easeFactor, 1e-9)
    }

    @Test
    fun interval_never_below_one() {
        val state = CardState(easeFactor = 1.3, reps = 5, intervalDays = 0)
        val outcome = Sm2Scheduler.review(state, 4, 10L)
        assertTrue(outcome.state.intervalDays >= 1)
    }

    @Test
    fun quality_mapping_2_3_4_5_from_ui() {
        // M1-4 的四个按钮固定映射 quality 2/3/4/5，逐一验证可跑通
        for (q in listOf(2, 3, 4, 5)) {
            val outcome = Sm2Scheduler.review(initial, q, 20450L)
            assertTrue(outcome.state.intervalDays >= 1)
            assertTrue(outcome.dueEpochDay > 20450L)
        }
    }
}
