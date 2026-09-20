package com.shiyu.app.ui.profile

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.shiyu.app.AppGraph
import com.shiyu.app.BuildConfig
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.stats.Achievements
import com.shiyu.app.data.model.DayStat
import com.shiyu.app.databinding.ActivityProfileBinding
import com.shiyu.app.databinding.ItemBadgeBinding
import com.shiyu.app.databinding.ItemWeekBarBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
import com.shiyu.app.ui.nav.BottomNav
import com.shiyu.app.ui.settings.SettingsActivity
import java.time.LocalDate

/**
 * 我的（Tab 5）。
 *
 * 设计稿这一屏的四个模块（三项总览 / 周柱状图 / 成就徽章 / 设置入口）全部保留，
 * 但数字一律现算：连续天数与时长来自 DailyStatsStore，掌握词汇来自 StudyStore，
 * 对话轮数来自 SessionStore。没有任何写死的演示值，也没有"发音评分"这种
 * 当前数据层算不出来的指标。
 */
class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.header)
        fitBottomInset(binding.bottomNavBar.navRow)
        BottomNav.bind(this, binding.bottomNavBar, BottomNav.Tab.PROFILE)

        binding.versionText.text = BuildConfig.VERSION_NAME
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAbout.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.profile_about_text, BuildConfig.VERSION_NAME),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnClearData.setOnClickListener { confirmClear() }
        binding.autoPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            AppGraph.settingsRepository.setAutoPlayTts(isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.autoPlaySwitch.isChecked = AppGraph.settingsRepository.autoPlayTts()
        render()
    }

    private fun render() {
        val active = AppGraph.settingsRepository.activeLang()
        val other = if (active == LangId.JA) LangId.EN else LangId.JA
        val accent = ContextCompat.getColor(this, LangStyle.accentRes(active))
        binding.avatar.text = active.tag.uppercase()
        binding.avatar.backgroundTintList = ColorStateList.valueOf(accent)
        binding.profileSub.text = "主学${active.displayName} · 辅修${other.displayName}"

        val epochDay = LocalDate.now().toEpochDay()
        val stats = AppGraph.dailyStatsStore.load()
        val streak = AppGraph.dailyStatsStore.streak(epochDay, stats)
        val totalSeconds = AppGraph.dailyStatsStore.totalSeconds(stats)
        val mastered = LangId.entries.sumOf { masteredCount(it) }
        val totalTurns = LangId.entries.sumOf { AppGraph.sessionStore.turnsCountByLang(it) }
        val voiceSeconds = stats.days.values.sumOf { it.voiceSeconds }

        binding.statStreak.text = streak.toString()
        binding.statWords.text = mastered.toString()
        binding.statHours.text = String.format("%.1f", totalSeconds / 3600.0)

        renderWeek(AppGraph.dailyStatsStore.currentWeek(epochDay, stats), epochDay)
        renderBadges(
            Achievements.evaluate(
                Achievements.Input(
                    voiceSeconds = voiceSeconds,
                    streakDays = streak,
                    masteredWords = mastered,
                    totalSeconds = totalSeconds,
                    totalTurns = totalTurns
                )
            )
        )
        renderLangStats(epochDay)
    }

    private fun masteredCount(lang: LangId): Int =
        AppGraph.studyStore.progressFor(lang).values.count { it.reps >= MASTERED_REPS }

    private fun renderWeek(week: List<DayStat>, todayEpochDay: Long) {
        binding.weekBars.removeAllViews()
        val total = week.sumOf { it.totalMinutes }
        binding.weekTotal.text = getString(R.string.profile_week_total, total)

        val peak = week.maxOfOrNull { it.totalMinutes } ?: 0
        val density = resources.displayMetrics.density
        val maxBarPx = (74f * density).toInt()
        val minBarPx = (4f * density).toInt()
        val clay = ContextCompat.getColor(this, R.color.clay)
        val claySoft = ContextCompat.getColor(this, R.color.clay_soft)

        for (day in week) {
            val item = ItemWeekBarBinding.inflate(
                LayoutInflater.from(this), binding.weekBars, false
            )
            val heightPx = if (peak <= 0) minBarPx
            else minBarPx + ((maxBarPx - minBarPx) * day.totalMinutes / peak)
            item.bar.layoutParams = item.bar.layoutParams.apply { height = heightPx }

            val isToday = day.epochDay == todayEpochDay
            item.bar.backgroundTintList =
                ColorStateList.valueOf(if (isToday) clay else claySoft)
            item.barValue.text = if (day.totalMinutes > 0) day.totalMinutes.toString() else ""
            item.barLabel.text = WEEKDAYS[(day.epochDay - week[0].epochDay).toInt()]
            if (isToday) {
                item.barLabel.setTextColor(clay)
            }
            binding.weekBars.addView(item.root)
        }
    }

    private fun renderBadges(badges: List<Achievements.Badge>) {
        binding.badgeContainer.removeAllViews()
        badges.chunked(BADGES_PER_ROW).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) topMargin = (18 * resources.displayMetrics.density).toInt()
                }
            }
            for (badge in row) {
                rowView.addView(badgeView(badge))
            }
            // 补齐空位，保证最后一行也按三等分排布
            repeat(BADGES_PER_ROW - row.size) {
                rowView.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                )
            }
            binding.badgeContainer.addView(rowView)
        }
    }

    private fun badgeView(badge: Achievements.Badge): View {
        val item = ItemBadgeBinding.inflate(LayoutInflater.from(this), null, false)
        val meta = BADGE_META.getValue(badge.id)
        item.badgeName.text = getString(meta.nameRes)
        item.badgeDesc.text = getString(meta.descRes)
        item.badgeIcon.setImageResource(meta.iconRes)

        if (badge.unlocked) {
            item.badgeIcon.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, meta.softColorRes))
            item.badgeIcon.setColorFilter(ContextCompat.getColor(this, meta.colorRes))
            item.badgeProgress.visibility = View.GONE
            item.root.alpha = 1f
        } else {
            item.badgeIcon.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.paper_deep))
            item.badgeIcon.setColorFilter(ContextCompat.getColor(this, R.color.mute))
            item.badgeProgress.visibility = View.VISIBLE
            item.badgeProgress.text = getString(
                R.string.profile_badge_progress,
                displayValue(badge.id, badge.current),
                displayValue(badge.id, badge.target)
            )
            item.root.alpha = 0.72f
        }
        return item.root
    }

    /** 时长类成就按小时显示，其它按原值 */
    private fun displayValue(id: Achievements.Id, raw: Long): Int =
        if (id == Achievements.Id.HOURS_10) (raw / 3600L).toInt() else raw.toInt()

    private fun renderLangStats(epochDay: Long) {
        binding.langStats.removeAllViews()
        for (lang in LangId.entries) {
            val progress = AppGraph.studyStore.progressFor(lang)
            val due = progress.values.count { it.dueEpochDay <= epochDay }
            val mastered = progress.values.count { it.reps >= MASTERED_REPS }
            val turns = AppGraph.sessionStore.turnsCountByLang(lang)
            binding.langStats.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.profile_lang_stats,
                        lang.displayName, due, mastered, turns
                    )
                    textSize = 13.5f
                    setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.ink2))
                    setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
                }
            )
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_data)
            .setMessage(R.string.profile_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                AppGraph.studyStore.clear()
                AppGraph.sessionStore.clear()
                AppGraph.dailyStatsStore.clear()
                AppGraph.settingsRepository.clear()
                Toast.makeText(this, R.string.profile_cleared, Toast.LENGTH_SHORT).show()
                binding.autoPlaySwitch.isChecked = true
                render()
            }
            .show()
    }

    private data class BadgeMeta(
        val nameRes: Int,
        val descRes: Int,
        val iconRes: Int,
        val colorRes: Int,
        val softColorRes: Int
    )

    private companion object {
        const val MASTERED_REPS = 5
        const val BADGES_PER_ROW = 3
        val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

        val BADGE_META: Map<Achievements.Id, BadgeMeta> = mapOf(
            Achievements.Id.FIRST_VOICE to BadgeMeta(
                R.string.badge_first_voice, R.string.badge_first_voice_d,
                R.drawable.ic_mic, R.color.ok_green, R.color.ok_green_soft
            ),
            Achievements.Id.STREAK_7 to BadgeMeta(
                R.string.badge_streak_7, R.string.badge_streak_7_d,
                R.drawable.ic_flame, R.color.clay, R.color.clay_soft
            ),
            Achievements.Id.WORDS_100 to BadgeMeta(
                R.string.badge_words_100, R.string.badge_words_100_d,
                R.drawable.ic_nav_review, R.color.lang_en, R.color.violet_soft
            ),
            Achievements.Id.WORDS_200 to BadgeMeta(
                R.string.badge_words_200, R.string.badge_words_200_d,
                R.drawable.ic_nav_langs, R.color.lang_fr, R.color.violet_soft
            ),
            Achievements.Id.HOURS_10 to BadgeMeta(
                R.string.badge_hours_10, R.string.badge_hours_10_d,
                R.drawable.ic_chart, R.color.gold, R.color.clay_soft
            ),
            Achievements.Id.TURNS_100 to BadgeMeta(
                R.string.badge_turns_100, R.string.badge_turns_100_d,
                R.drawable.ic_nav_home, R.color.lang_ja, R.color.clay_soft
            )
        )
    }
}
