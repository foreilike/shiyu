package com.shiyu.app.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.Scenario
import com.shiyu.app.databinding.ActivityChatListBinding
import com.shiyu.app.databinding.ItemScenarioBinding
import com.shiyu.app.ui.chart.ChartActivity
import com.shiyu.app.ui.profile.ProfileActivity
import com.shiyu.app.ui.study.StudyActivity
import com.shiyu.app.ui.voice.VoiceChatActivity

/**
 * 场景列表页（对话 Tab 首页）：当前语言下的场景卡片（目标语言标题 + 中文副标题 + 已完成轮数）。
 */
class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private lateinit var scenarios: List<Scenario>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scenarioList.layoutManager = LinearLayoutManager(this)

        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val lang = selectedLang()
            if (lang != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(lang)
                loadScenarios()
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
                R.id.nav_chart -> {
                    startActivity(
                        Intent(this, ChartActivity::class.java)
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

        // 实时语音入口：取当前语言第一个场景
        binding.btnVoiceRealtime.setOnClickListener {
            val first = scenarios.firstOrNull() ?: return@setOnClickListener
            startActivity(VoiceChatActivity.intent(this, first.id))
        }
    }

    override fun onResume() {
        super.onResume()
        syncLangBar()
        loadScenarios()
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
                loadScenarios()
            }
        }
    }

    private fun loadScenarios() {
        val module = LanguageRegistry.module(AppGraph.settingsRepository.activeLang())
        scenarios = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
        val turnsByScenario = AppGraph.sessionStore.allSessions()
            .filter { it.langId == module.id.tag }
            .groupBy { it.scenarioId }
            .mapValues { entry -> entry.value.sumOf { record -> record.turns.count { it.role == "user" } } }
        binding.scenarioList.adapter = ScenarioAdapter(scenarios, turnsByScenario) { scenario ->
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_SCENARIO_ID, scenario.id)
            )
        }
    }
}

private class ScenarioAdapter(
    private val scenarios: List<Scenario>,
    private val turnsByScenario: Map<String, Int>,
    private val onClick: (Scenario) -> Unit
) : RecyclerView.Adapter<ScenarioAdapter.VH>() {

    class VH(val binding: ItemScenarioBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScenarioBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val scenario = scenarios[position]
        holder.binding.scenarioTitle.text = scenario.titleTarget
        holder.binding.scenarioTitleZh.text = scenario.titleZh
        val turns = turnsByScenario[scenario.id] ?: 0
        holder.binding.scenarioTurns.text =
            holder.itemView.context.getString(R.string.chat_completed_turns, turns)
        holder.itemView.setOnClickListener { onClick(scenario) }
    }

    override fun getItemCount(): Int = scenarios.size
}
