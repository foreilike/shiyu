package com.shiyu.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.databinding.ActivityProfileBinding
import com.shiyu.app.ui.chart.ChartActivity
import com.shiyu.app.ui.chat.ChatListActivity
import com.shiyu.app.ui.settings.SettingsActivity
import com.shiyu.app.ui.study.StudyActivity
import java.time.LocalDate

/**
 * 我的页（M5）：按语言分组的统计 + 设置入口 + 清空数据。
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClearData.setOnClickListener { confirmClear() }
        binding.autoPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            AppGraph.settingsRepository.setAutoPlayTts(isChecked)
        }

        binding.bottomNavBar.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_study -> {
                    startActivity(
                        Intent(this, StudyActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                R.id.nav_chart -> {
                    startActivity(
                        Intent(this, ChartActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                R.id.nav_chat -> {
                    startActivity(
                        Intent(this, ChatListActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.autoPlaySwitch.isChecked = AppGraph.settingsRepository.autoPlayTts()
        bindStats(binding.statsJa, LangId.JA)
        bindStats(binding.statsEn, LangId.EN)
    }

    private fun bindStats(container: LinearLayout, lang: LangId) {
        container.removeAllViews()
        val module = LanguageRegistry.module(lang)
        val deck = module.buildDeck(AppGraph.assetRepository.deckJson(module))
        val progress = AppGraph.studyStore.progressFor(lang)
        val today = LocalDate.now().toEpochDay()
        val due = progress.values.count { it.dueEpochDay <= today }
        val mastered = progress.values.count { it.reps >= 5 }
        val turns = AppGraph.sessionStore.turnsCountByLang(lang)

        container.addView(
            TextView(this).apply {
                text = lang.displayName
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.purple_500))
            }
        )
        val rows = listOf(
            getString(R.string.profile_total_cards) to deck.size.toString(),
            getString(R.string.profile_today_due) to due.toString(),
            getString(R.string.profile_mastered) to mastered.toString(),
            getString(R.string.profile_total_turns) to turns.toString()
        )
        for ((label, value) in rows) {
            container.addView(
                TextView(this).apply {
                    text = "$label：$value"
                    textSize = 15f
                    setPadding(0, 8, 0, 0)
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
                AppGraph.settingsRepository.clear()
                Toast.makeText(this, R.string.profile_cleared, Toast.LENGTH_SHORT).show()
                binding.autoPlaySwitch.isChecked = true
                bindStats(binding.statsJa, LangId.JA)
                bindStats(binding.statsEn, LangId.EN)
            }
            .show()
    }
}
