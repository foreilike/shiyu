package com.shiyu.app.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.srs.DailyQuota
import com.shiyu.app.databinding.ActivityHomeBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
import com.shiyu.app.ui.chart.ChartActivity
import com.shiyu.app.ui.nav.BottomNav
import com.shiyu.app.ui.review.ReviewActivity
import com.shiyu.app.ui.voice.VoiceChatActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 今日（首页仪表盘，Tab 1）。
 *
 * 一屏只回答「今天学什么」。这里展示的每一个数字都来自真实存储：
 * 目标进度与三个小标签来自 [com.shiyu.app.data.store.DailyStatsStore]，
 * 待复习/已掌握来自 StudyStore，语伴在线状态来自当前语言 —— 没有写死的演示值。
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.header)
        fitBottomInset(binding.bottomNavBar.navRow)
        BottomNav.bind(this, binding.bottomNavBar, BottomNav.Tab.HOME)

        binding.btnContinue.setOnClickListener { openReview() }
        binding.contCard.setOnClickListener { openReview() }
        binding.entryReview.setOnClickListener { openReview() }
        binding.entryChart.setOnClickListener {
            startActivity(Intent(this, ChartActivity::class.java))
        }
        binding.voiceCard.setOnClickListener { openVoice() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun openReview() {
        startActivity(
            Intent(this, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    /** 实时语音需要一个场景来提供 systemPrompt，取当前语言的第一个场景 */
    private fun openVoice() {
        val module = LanguageRegistry.module(AppGraph.settingsRepository.activeLang())
        val scenarios = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
        val first = scenarios.firstOrNull() ?: return
        startActivity(VoiceChatActivity.intent(this, first.id))
    }

    private fun render() {
        val lang = AppGraph.settingsRepository.activeLang()
        val module = LanguageRegistry.module(lang)
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()

        renderHeader(today, epochDay)
        renderGoal(epochDay)
        renderContinue(module.id, module)
        binding.voicePartner.text = getString(
            R.string.voice_partner_online,
            LangStyle.partnerName(lang)
        )
    }

    private fun renderHeader(today: LocalDate, epochDay: Long) {
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        binding.homeDate.text = "${today.monthValue} 月 ${today.dayOfMonth} 日 · $weekday"

        // 设过名字就带上称呼（"晚上好，林晓"），没设就只留问候语，不硬塞占位名
        val nickname = AppGraph.settingsRepository.nickname()
        binding.homeGreet.text =
            if (nickname.isBlank()) greeting() else "${greeting()}，$nickname"

        val streak = AppGraph.dailyStatsStore.streak(epochDay)
        if (streak > 0) {
            binding.streakText.text = getString(R.string.home_streak, streak)
        } else {
            binding.streakText.text = getString(R.string.home_streak_none)
        }
    }

    private fun greeting(): String = when (LocalTime.now().hour) {
        in 5..10 -> getString(R.string.home_greet_morning)
        in 11..12 -> getString(R.string.home_greet_noon)
        in 13..17 -> getString(R.string.home_greet_afternoon)
        else -> getString(R.string.home_greet_evening)
    }

    private fun renderGoal(epochDay: Long) {
        val stat = AppGraph.dailyStatsStore.dayOf(epochDay)
        val minutes = stat.totalMinutes
        val goal = AppGraph.settingsRepository.dailyGoalMinutes()
        binding.goalMinutes.text = minutes.toString()
        binding.goalRing.progress = minutes.toFloat() / goal
        binding.goalTitle.text = getString(R.string.home_goal_title, goal)
        binding.goalSub.text = when {
            minutes >= goal -> getString(R.string.home_goal_done)
            // 已经学了一点但不足一分钟：说「今天还没开始」会让人以为记录丢了，
            // 目标环只有分钟粒度，这里用文案把"其实记上了"讲清楚
            minutes == 0 && stat.totalSeconds > 0 -> getString(R.string.home_goal_just_started)
            minutes == 0 -> getString(R.string.home_goal_start)
            else -> getString(R.string.home_goal_left, goal - minutes)
        }
        binding.goalStat1.text = getString(R.string.home_stat_reviewed, stat.reviewedCards)
        binding.goalStat2.text = getString(R.string.home_stat_turns, stat.chatTurns)
        binding.goalStat3.text =
            getString(R.string.home_stat_voice, (stat.voiceSeconds / 60L).toInt())
    }

    private fun renderContinue(lang: LangId, module: LanguageModule) {
        val accent = ContextCompat.getColor(this, LangStyle.accentRes(lang))
        binding.langDot.backgroundTintList = ColorStateList.valueOf(accent)
        binding.langTagText.setTextColor(accent)
        binding.langTagText.text = lang.displayName

        val deck = module.buildDeck(AppGraph.assetRepository.deckJson(module))
        val progress = AppGraph.studyStore.progressFor(lang)
        val epochDay = LocalDate.now().toEpochDay()
        // 与复习页同一口径（DailyQuota），且**按当前语言分桶** ——
        // 用当天全局已复习数会让英语做一张卡把日语的待复习也扣掉一张（跨语言串）。
        val due = DailyQuota.remaining(
            availableCards = AppGraph.studyStore
                .selectToday(lang, deck.map { it.id }, epochDay).size,
            reviewedToday = AppGraph.dailyStatsStore.dayOf(epochDay).reviewedIn(lang.tag)
        )
        val mastered = progress.values.count { it.reps >= MASTERED_REPS }
        val pct = if (deck.isEmpty()) 0 else mastered * 100 / deck.size

        if (due == 0) {
            binding.contTitle.text = getString(R.string.home_all_clear)
            binding.contSub.text = getString(R.string.home_all_clear_sub, lang.displayName)
        } else {
            binding.contTitle.text = getString(R.string.home_cont_title, due)
            binding.contSub.text =
                getString(R.string.home_cont_sub, lang.displayName, deck.size)
        }
        binding.contProgress.progress = pct
        binding.contPct.text = getString(R.string.home_cont_progress, pct)
        binding.entryReviewSub.text = getString(R.string.home_entry_review_sub, due)
    }

    private companion object {
        /** 与 ReviewActivity/ProfileActivity 同一口径：复习 5 次以上算掌握 */
        const val MASTERED_REPS = 5
    }
}
