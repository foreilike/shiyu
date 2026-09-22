package com.shiyu.app.ui.langs

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.databinding.ActivityLanguagesBinding
import com.shiyu.app.databinding.ItemLanguageBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
import com.shiyu.app.ui.nav.BottomNav
import java.time.LocalDate

/**
 * 课程（语种切换，Tab 2）。
 *
 * 设计稿画了五个语种，但工程里真实存在的资产只有日语与英语 —— 韩 / 西 / 法 在这里
 * 明确标为「即将支持」的灰态，点了会说清楚，不做成点了没反应或者切过去一片空白。
 * 等 assets 和 LanguageModule 补齐后，把语种从 [COMING] 挪进 LangId 即可。
 */
class LanguagesActivity : BaseActivity() {

    private lateinit var binding: ActivityLanguagesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.header)
        fitBottomInset(binding.bottomNavBar.navRow)
        BottomNav.bind(this, binding.bottomNavBar, BottomNav.Tab.LANGS)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val active = AppGraph.settingsRepository.activeLang()
        renderCurrent(active)

        binding.langContainer.removeAllViews()
        for (lang in LangId.entries) {
            addRow(
                container = binding.langContainer,
                code = lang.tag.uppercase(),
                name = lang.displayName,
                meta = metaFor(lang),
                accent = ContextCompat.getColor(this, LangStyle.accentRes(lang)),
                badge = if (lang == active) getString(R.string.langs_current) else null,
                enabled = true
            ) { switchTo(lang) }
        }

        binding.comingContainer.removeAllViews()
        for ((tag, nameRes, colorRes) in COMING) {
            val name = getString(nameRes)
            addRow(
                container = binding.comingContainer,
                code = tag,
                name = name,
                meta = getString(R.string.langs_coming),
                accent = ContextCompat.getColor(this, colorRes),
                badge = getString(R.string.langs_coming),
                enabled = false
            ) {
                Toast.makeText(
                    this,
                    getString(R.string.langs_coming_toast, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renderCurrent(lang: LangId) {
        val accent = ContextCompat.getColor(this, LangStyle.accentRes(lang))
        binding.curCode.text = lang.tag.uppercase()
        binding.curCode.backgroundTintList = ColorStateList.valueOf(accent)
        binding.curName.text = lang.displayName
        binding.curMeta.text = metaFor(lang)
    }

    /** 真实条目数：卡片总数 + 场景总数，切之前就能看到这门语言有多少内容 */
    private fun metaFor(lang: LangId): String {
        val module = LanguageRegistry.module(lang)
        val deck = module.buildDeck(AppGraph.activeDeckJson(module))
        val scenarios = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
        return getString(R.string.langs_meta, deck.size, scenarios.size)
    }

    private fun switchTo(lang: LangId) {
        if (lang == AppGraph.settingsRepository.activeLang()) return
        AppGraph.settingsRepository.setActiveLang(lang)
        Toast.makeText(
            this,
            getString(R.string.langs_switched, lang.displayName),
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    private fun addRow(
        container: LinearLayout,
        code: String,
        name: String,
        meta: String,
        accent: Int,
        badge: String?,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val row = ItemLanguageBinding.inflate(LayoutInflater.from(this), container, false)
        row.langCode.text = code
        row.langCode.backgroundTintList = ColorStateList.valueOf(accent)
        row.langName.text = name
        row.langMeta.text = meta
        if (badge == null) {
            row.langBadge.visibility = android.view.View.GONE
        } else {
            row.langBadge.text = badge
            row.langBadge.setTextColor(if (enabled) accent else ContextCompat.getColor(this, R.color.mute))
        }
        row.root.alpha = if (enabled) 1f else 0.55f
        row.root.setOnClickListener { onClick() }
        container.addView(row.root)
    }

    private companion object {
        /** 设计稿里的另外三个语种：资产未就绪，先以灰态出现在「即将支持」分区 */
        val COMING = listOf(
            Triple("KO", R.string.lang_ko, R.color.lang_ko),
            Triple("ES", R.string.lang_es, R.color.lang_es),
            Triple("FR", R.string.lang_fr, R.color.lang_fr)
        )
    }
}
