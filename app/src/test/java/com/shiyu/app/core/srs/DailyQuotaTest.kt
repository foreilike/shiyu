package com.shiyu.app.core.srs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今日复习配额。首页与复习页都调它，口径必须锁死 ——
 * 不一致就会出现「首页说 20 张、复习页说 19 张」这种自相矛盾的显示。
 */
class DailyQuotaTest {

    @Test
    fun quota_is_twenty_cards_per_day() {
        assertEquals(20, DailyQuota.CARDS_PER_DAY)
    }

    @Test
    fun nothing_reviewed_yet_returns_full_quota() {
        assertEquals(20, DailyQuota.remaining(availableCards = 20, reviewedToday = 0))
    }

    @Test
    fun quota_shrinks_by_what_was_reviewed_today() {
        // 首页重新排卡仍能排满 20，但今天已经做了 1 张，应显示 19
        assertEquals(19, DailyQuota.remaining(availableCards = 20, reviewedToday = 1))
        assertEquals(5, DailyQuota.remaining(availableCards = 20, reviewedToday = 15))
    }

    @Test
    fun available_cards_is_the_upper_bound() {
        // 牌堆只剩 3 张时，不能因为配额还有 20 就显示 20
        assertEquals(3, DailyQuota.remaining(availableCards = 3, reviewedToday = 0))
    }

    @Test
    fun over_quota_returns_zero_not_negative() {
        assertEquals(0, DailyQuota.remaining(availableCards = 20, reviewedToday = 20))
        assertEquals(0, DailyQuota.remaining(availableCards = 20, reviewedToday = 99))
    }

    @Test
    fun negative_available_is_clamped() {
        // 复习页传的是 queue.size - queueIndex，越界时不能出负数
        assertEquals(0, DailyQuota.remaining(availableCards = -1, reviewedToday = 0))
    }

    @Test
    fun home_and_review_agree_after_one_rating() {
        // 首页：selectToday 补满 20 张；复习页：队列 20 张已过 1 张
        val home = DailyQuota.remaining(availableCards = 20, reviewedToday = 1)
        val review = DailyQuota.remaining(availableCards = 19, reviewedToday = 1)
        assertEquals(home, review)
        assertEquals(19, home)
    }
}
