package com.shiyu.app.ui.chart

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.ChartEntry
import com.shiyu.app.databinding.ActivityChartBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.Speaker
import com.shiyu.app.ui.WordTtsPlayer

/**
 * 发音表（二级页）：日语 = 五十音表（两套字形切换）；英语 = IPA 音素表（按 vowel/consonant 分节）。
 * 点击格子显示说明并朗读（系统 TTS，不占用 AI Provider 额度）。
 *
 * 改造后从一级 Tab 降为二级页：入口在「复习」页右上角与首页快捷卡，语种跟随全局设置。
 */
class ChartActivity : BaseActivity() {

    private lateinit var binding: ActivityChartBinding
    private var entries: List<ChartEntry> = emptyList()
    private var speaker: Speaker? = null

    /** 云优先朗读：日语走云端 TTS（避开系统 TTS 联网找 Google 在国内网络的静默失败），失败回落 [speaker]。 */
    private var ttsPlayer: WordTtsPlayer? = null

    /** 当前发音表语种；格子点击朗读要按它决定走云端还是系统 TTS。 */
    private var lang: LangId = LangId.JA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInset(binding.chartScroll)

        binding.headerBar.subTitle.text = getString(R.string.review_chart_entry)
        binding.headerBar.btnBack.setOnClickListener { finish() }

        speaker = Speaker(this)
        ttsPlayer = WordTtsPlayer(this, speaker!!)
        ttsPlayer?.onNeedNetwork = {
            Toast.makeText(this, R.string.tts_need_network, Toast.LENGTH_SHORT).show()
        }
        binding.scriptSwitch.setOnCheckedChangeListener { _, _ -> buildGrid() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        ttsPlayer?.releasePlayer()
        ttsPlayer = null
        speaker?.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        val module = LanguageRegistry.module(AppGraph.settingsRepository.activeLang())
        lang = module.id
        speaker?.setLanguage(module.id)
        entries = module.buildChart(AppGraph.assetRepository.chartJson(module))
        // 整表预取（§17.17 Tier 1）：五十音就几十格，进页后台逐条合成落缓存，
        // 点格子秒开。英语走系统 TTS，prefetchAll 内部判空跳过。
        ttsPlayer?.prefetchAll(lifecycleScope, lang, entries.map { it.speak })
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
                binding.chartGrid.addView(buildSectionHeader(section))
            }
            for (entry in list) {
                binding.chartGrid.addView(buildCell(entry))
            }
        }
    }

    private fun buildSectionHeader(section: String): View = TextView(this).apply {
        text = if (section == "vowel") getString(R.string.chart_section_vowel)
        else getString(R.string.chart_section_consonant)
        textSize = 13f
        letterSpacing = 0.06f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@ChartActivity, R.color.mute))
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad * 2, pad, pad)
        layoutParams = GridLayout.LayoutParams().apply {
            columnSpec = GridLayout.spec(0, 5)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
        }
    }

    /** 每个格子是一张小白卡（描边分层，与全站卡片语言一致） */
    private fun buildCell(entry: ChartEntry): View {
        val density = resources.displayMetrics.density
        val margin = (4 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_round12)
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = (64 * density).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL_HORIZONTAL)
                setMargins(margin, margin, margin, margin)
            }
            isClickable = true
            setOnClickListener { onCellClick(entry) }
        }
        val display =
            if (binding.scriptSwitch.isChecked && entry.alt.isNotEmpty()) entry.alt else entry.cell
        container.addView(
            TextView(this).apply {
                text = display
                textSize = 20f
                typeface = android.graphics.Typeface.SERIF
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.ink))
            }
        )
        container.addView(
            TextView(this).apply {
                text = entry.sub
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.mute))
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
        ttsPlayer?.speakWord(lifecycleScope, lang, entry.speak)
    }
}
