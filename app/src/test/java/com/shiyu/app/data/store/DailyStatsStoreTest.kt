package com.shiyu.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * DailyStatsStore：每日活动的落盘 / 自愈 / 连续天数 / 本周切片 / 复习张数按语言分桶。
 *
 * 首页目标环、连续天数徽标、我的页周柱状图、「今日待复习」的配额扣减全部由这几个方法算出来，
 * 所以口径必须锁死在测试里。
 */
class DailyStatsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): DailyStatsStore =
        DailyStatsStore(File(tmp.root, "daily_stats.json"))

    /** 2026-09-20 是周日，用它做锚点可以顺带验证「周一开头」的切片 */
    private val sunday = LocalDate.of(2026, 9, 20).toEpochDay()

    @Test
    fun missing_file_returns_empty() {
        val data = newStore().load()
        assertTrue(data.days.isEmpty())
    }

    @Test
    fun day_of_missing_day_is_all_zero() {
        val stat = newStore().dayOf(sunday)
        assertEquals(0L, stat.totalSeconds)
        assertEquals(0, stat.reviewedCards)
        assertEquals(0, stat.reviewedIn("ja"))
        assertFalse(stat.active)
    }

    @Test
    fun record_persists_and_accumulates() {
        val store = newStore()
        store.record(sunday, studySeconds = 120L, reviewedCards = 3, langTag = "ja")
        store.record(sunday, voiceSeconds = 60L, chatTurns = 2)

        assertTrue(File(tmp.root, "daily_stats.json").exists())
        val stat = newStore().dayOf(sunday)
        assertEquals(120L, stat.studySeconds)
        assertEquals(60L, stat.voiceSeconds)
        assertEquals(3, stat.reviewedCards)
        assertEquals(2, stat.chatTurns)
        assertEquals(180L, stat.totalSeconds)
        assertEquals(3, stat.totalMinutes)
    }

    // ---------- 复习张数按语言分桶（切语言不能互相扣配额） ----------

    @Test
    fun reviewed_cards_are_bucketed_by_language() {
        val store = newStore()
        store.record(sunday, reviewedCards = 3, langTag = "ja")
        store.record(sunday, reviewedCards = 1, langTag = "en")

        val stat = store.dayOf(sunday)
        assertEquals(4, stat.reviewedCards)   // 当天总量（首页活动小结用）
        assertEquals(3, stat.reviewedIn("ja"))
        assertEquals(1, stat.reviewedIn("en"))
        assertEquals(0, stat.reviewedIn("ko")) // 没学过的语言不受影响
    }

    @Test
    fun reviewed_by_lang_survives_round_trip() {
        newStore().record(sunday, reviewedCards = 2, langTag = "en")
        val stat = newStore().dayOf(sunday)
        assertEquals(2, stat.reviewedIn("en"))
        assertEquals(0, stat.reviewedIn("ja"))
    }

    @Test
    fun reviewing_one_language_does_not_touch_the_other() {
        // 这是本组测试存在的理由：英语做一张卡，不能把日语的今日配额也扣掉一张
        val store = newStore()
        store.record(sunday, reviewedCards = 1, langTag = "en")
        assertEquals(0, store.dayOf(sunday).reviewedIn("ja"))
    }

    @Test
    fun file_without_bucket_field_treats_every_language_as_zero() {
        // 早期文件只有全局 reviewedCards，没有 reviewedByLang
        File(tmp.root, "daily_stats.json").writeText(
            """{"version":1,"days":{"$sunday":{"reviewedCards":5,"studySeconds":100}}}"""
        )
        val stat = newStore().dayOf(sunday)
        assertEquals(5, stat.reviewedCards)
        assertEquals(0, stat.reviewedIn("ja"))
        assertEquals(0, stat.reviewedIn("en"))
    }

    @Test
    fun record_without_lang_tag_still_counts_globally() {
        val store = newStore()
        store.record(sunday, reviewedCards = 2)
        val stat = store.dayOf(sunday)
        assertEquals(2, stat.reviewedCards)
        assertEquals(0, stat.reviewedIn("ja"))
    }

    @Test
    fun zero_reviewed_cards_does_not_create_a_bucket() {
        val store = newStore()
        store.record(sunday, studySeconds = 30L, reviewedCards = 0, langTag = "ja")
        assertTrue(store.dayOf(sunday).reviewedByLang.isEmpty())
    }

    // ---------- 其余行为 ----------

    @Test
    fun record_with_all_zero_does_not_write() {
        val store = newStore()
        store.record(sunday)
        assertFalse(File(tmp.root, "daily_stats.json").exists())
    }

    @Test
    fun negative_values_are_clamped_to_zero() {
        val store = newStore()
        store.record(sunday, studySeconds = 100L, voiceSeconds = -500L, reviewedCards = -3, langTag = "ja")
        val stat = store.dayOf(sunday)
        assertEquals(100L, stat.studySeconds)
        assertEquals(0L, stat.voiceSeconds)
        assertEquals(0, stat.reviewedCards)
        assertTrue(stat.reviewedByLang.isEmpty())
    }

    @Test
    fun broken_json_is_quarantined_and_returns_empty() {
        val file = File(tmp.root, "daily_stats.json")
        file.writeText("{not json")
        val data = newStore().load()
        assertTrue(data.days.isEmpty())
        assertFalse(file.exists())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("daily_stats.json.corrupt-") })
    }

    @Test
    fun unparsable_day_keys_are_skipped() {
        File(tmp.root, "daily_stats.json")
            .writeText("""{"version":1,"days":{"abc":{"studySeconds":10},"$sunday":{"studySeconds":20}}}""")
        val data = newStore().load()
        assertEquals(1, data.days.size)
        assertEquals(20L, data.days.getValue(sunday).studySeconds)
    }

    @Test
    fun streak_counts_back_from_today() {
        val store = newStore()
        for (offset in 0L..3L) store.record(sunday - offset, studySeconds = 60L)
        assertEquals(4, store.streak(sunday))
    }

    @Test
    fun streak_falls_back_to_yesterday_when_today_is_empty() {
        // 今天还没开始学，不应该立刻判断断签
        val store = newStore()
        for (offset in 1L..3L) store.record(sunday - offset, studySeconds = 60L)
        assertEquals(3, store.streak(sunday))
    }

    @Test
    fun streak_stops_at_the_first_gap() {
        val store = newStore()
        store.record(sunday, studySeconds = 60L)
        store.record(sunday - 1, studySeconds = 60L)
        // 跳过 sunday-2
        store.record(sunday - 3, studySeconds = 60L)
        assertEquals(2, store.streak(sunday))
    }

    @Test
    fun streak_is_zero_without_recent_activity() {
        val store = newStore()
        store.record(sunday - 5, studySeconds = 60L)
        assertEquals(0, store.streak(sunday))
    }

    @Test
    fun current_week_always_has_seven_days_starting_monday() {
        val week = newStore().currentWeek(sunday)
        assertEquals(7, week.size)
        assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(week[0].epochDay).dayOfWeek)
        assertEquals(sunday, week[6].epochDay)
    }

    @Test
    fun current_week_fills_missing_days_with_zero() {
        val store = newStore()
        store.record(sunday, studySeconds = 600L)
        val week = store.currentWeek(sunday)
        assertEquals(0L, week[0].totalSeconds)
        assertEquals(600L, week[6].totalSeconds)
        assertEquals(10, week[6].totalMinutes)
    }

    @Test
    fun total_seconds_sums_every_day() {
        val store = newStore()
        store.record(sunday, studySeconds = 100L)
        store.record(sunday - 1, voiceSeconds = 250L)
        store.record(sunday - 40, studySeconds = 50L)
        assertEquals(400L, store.totalSeconds())
    }

    @Test
    fun clear_removes_the_file() {
        val store = newStore()
        store.record(sunday, studySeconds = 60L)
        store.clear()
        assertFalse(File(tmp.root, "daily_stats.json").exists())
        assertTrue(newStore().load().days.isEmpty())
    }
}
