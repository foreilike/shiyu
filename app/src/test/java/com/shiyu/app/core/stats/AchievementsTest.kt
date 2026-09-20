package com.shiyu.app.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成就判定：条目固定 6 条、顺序固定、进度可算，全部来自真实统计输入 */
class AchievementsTest {

    private fun badge(id: Achievements.Id, input: Achievements.Input): Achievements.Badge =
        Achievements.evaluate(input).first { it.id == id }

    @Test
    fun evaluate_returns_six_badges_in_fixed_order() {
        val badges = Achievements.evaluate(Achievements.Input())
        assertEquals(6, badges.size)
        assertEquals(
            listOf(
                Achievements.Id.FIRST_VOICE,
                Achievements.Id.STREAK_7,
                Achievements.Id.WORDS_100,
                Achievements.Id.WORDS_200,
                Achievements.Id.HOURS_10,
                Achievements.Id.TURNS_100
            ),
            badges.map { it.id }
        )
    }

    @Test
    fun empty_input_unlocks_nothing() {
        assertTrue(Achievements.evaluate(Achievements.Input()).none { it.unlocked })
    }

    @Test
    fun first_voice_unlocks_on_any_recorded_second() {
        assertTrue(badge(Achievements.Id.FIRST_VOICE, Achievements.Input(voiceSeconds = 1L)).unlocked)
        assertFalse(badge(Achievements.Id.FIRST_VOICE, Achievements.Input(voiceSeconds = 0L)).unlocked)
    }

    @Test
    fun streak_badge_needs_seven_days() {
        assertFalse(badge(Achievements.Id.STREAK_7, Achievements.Input(streakDays = 6)).unlocked)
        assertTrue(badge(Achievements.Id.STREAK_7, Achievements.Input(streakDays = 7)).unlocked)
    }

    @Test
    fun word_badges_are_staged() {
        val input = Achievements.Input(masteredWords = 150)
        assertTrue(badge(Achievements.Id.WORDS_100, input).unlocked)
        assertFalse(badge(Achievements.Id.WORDS_200, input).unlocked)
    }

    @Test
    fun hours_badge_counts_seconds_against_ten_hours() {
        val almost = Achievements.Input(totalSeconds = 10L * 3600L - 1L)
        assertFalse(badge(Achievements.Id.HOURS_10, almost).unlocked)
        val exact = Achievements.Input(totalSeconds = 10L * 3600L)
        assertTrue(badge(Achievements.Id.HOURS_10, exact).unlocked)
    }

    @Test
    fun turns_badge_needs_one_hundred_user_turns() {
        assertFalse(badge(Achievements.Id.TURNS_100, Achievements.Input(totalTurns = 99)).unlocked)
        assertTrue(badge(Achievements.Id.TURNS_100, Achievements.Input(totalTurns = 100)).unlocked)
    }

    @Test
    fun percent_is_clamped_between_zero_and_hundred() {
        assertEquals(0, badge(Achievements.Id.STREAK_7, Achievements.Input(streakDays = 0)).percent)
        assertEquals(42, badge(Achievements.Id.STREAK_7, Achievements.Input(streakDays = 3)).percent)
        assertEquals(100, badge(Achievements.Id.STREAK_7, Achievements.Input(streakDays = 99)).percent)
    }
}
