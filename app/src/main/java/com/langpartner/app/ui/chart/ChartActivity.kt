package com.langpartner.app.ui.chart

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.langpartner.app.AppGraph
import com.langpartner.app.R
import com.langpartner.app.core.lang.LanguageRegistry
import com.langpartner.app.core.lang.LangId
import com.langpartner.app.data.model.ChartEntry
import com.langpartner.app.ui.Speaker
import com.langpartner.app.ui.chat.ChatListActivity
import com.langpartner.app.ui.profile.ProfileActivity
import com.langpartner.app.ui.study.StudyActivity
import com.langpartner.app.databinding.ActivityChartBinding

/**
 * 图表页（M1-7）：日语 = 五十音表（平/片假名切换）；英语 = IPA 音素表（按 vowel/consonant 分节）。
 * 点击格子显示说明并朗读（系统 TTS，不占用 AI Provider）。
 */
class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChartBinding
    private var entries: List<ChartEntry> = emptyList()
    private var speaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speaker = Speaker(this)

        binding.scriptSwitch.setOnCheckedChangeListener { _, _ -> buildGrid() }
        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val lang = selectedLang()
            if (lang != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(lang)
                reload()
            }
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
                R.id.nav_chat -> {
                    startActivity(
                        Intent(this, ChatListActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                R.id.nav_profile -> {
                    startActivity(
                        Intent(this, ProfileActivity::class.java)
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
        syncLangBar()
        reload()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    private fun selectedLang(): LangId =
        if (binding.langBar.radioEn.isChecked) LangId.EN else LangId.JA

    private fun syncLangBar() {
        val lang = AppGraph.settingsRepository.activeLang()
        binding.langBar.langGroup.setOnCheckedChangeListener(null)
        binding.langBar.radioJa.isChecked = lang == LangId.JA
        binding.langBar.radioEn.isChecked = lang == LangId.EN
        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val selected = selectedLang()
            if (selected != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(selected)
                reload()
            }
        }
    }

    private fun reload() {
        val module = LanguageRegistry.module(AppGraph.settingsRepository.activeLang())
        speaker?.setLanguage(module.id)
        entries = module.buildChart(AppGraph.assetRepository.chartJson(module))
        val hasAlt = entries.any { it.alt.isNotEmpty() }
        binding.scriptSwitch.visibility = if (hasAlt) View.VISIBLE else View.GONE
        binding.scriptSwitch.isChecked = false
        buildGrid()
    }

    private fun buildGrid() {
        binding.chartGrid.removeAllViews()
        val sections = entries.map { it.section }.distinct().filter { it.isNotEmpty() }
        val grouped: List<Pair<String, List<ChartEntry>>> =
            if (sections.isEmpty()) {
                listOf("" to entries)
            } else {
                val noSection = entries.filter { it.section.isEmpty() }
                val tail = sections.map { section ->
                    section to entries.filter { it.section == section }
                }
                (if (noSection.isEmpty()) emptyList() else listOf("" to noSection)) + tail
            }
        for ((section, list) in grouped) {
            if (section.isNotEmpty()) {
                val header = TextView(this).apply {
                    text = if (section == "vowel") getString(R.string.chart_section_vowel)
                    else getString(R.string.chart_section_consonant)
                    textSize = 15f
                    setTextColor(
                        ContextCompat.getColor(
                            this@ChartActivity,
                            R.color.purple_500
                        )
                    )
                    val lp = GridLayout.LayoutParams().apply {
                        columnSpec = GridLayout.spec(0, 5)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                    }
                    layoutParams = lp
                }
                binding.chartGrid.addView(header)
            }
            for (entry in list) {
                binding.chartGrid.addView(buildCell(entry))
            }
        }
    }

    private fun buildCell(entry: ChartEntry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = 150
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL_HORIZONTAL)
            }
            layoutParams = lp
            setOnClickListener { onCellClick(entry) }
        }
        val display =
            if (binding.scriptSwitch.isChecked && entry.alt.isNotEmpty()) entry.alt else entry.cell
        container.addView(
            TextView(this).apply {
                text = display
                textSize = 26f
                gravity = Gravity.CENTER
            }
        )
        container.addView(
            TextView(this).apply {
                text = entry.sub
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.gray_text))
            }
        )
        return container
    }

    private fun onCellClick(entry: ChartEntry) {
        val desc = buildString {
            append(entry.cell)
            if (entry.alt.isNotEmpty()) append(" / ").append(entry.alt)
            append("　").append(entry.sub)
            if (entry.note.isNotEmpty()) append("　").append(entry.note)
        }
        binding.chartDescription.text = desc
        speaker?.speak(entry.speak)
    }
}
