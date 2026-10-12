package com.shiyu.app.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.Scenario
import com.shiyu.app.databinding.ActivityChatListBinding
import com.shiyu.app.databinding.ItemScenarioBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
import com.shiyu.app.ui.nav.BottomNav
import com.shiyu.app.ui.voice.VoiceChatActivity

/**
 * 对话中心（Tab 3）：置顶的实时语音 hero + 当前语言的场景练习列表。
 *
 * 语言切换条已经移到「课程」Tab，这里只跟随全局当前语言。
 */
class ChatListActivity : BaseActivity() {

    private lateinit var binding: ActivityChatListBinding
    private var scenarios: List<Scenario> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.header)
        fitBottomInset(binding.bottomNavBar.navRow)
        BottomNav.bind(this, binding.bottomNavBar, BottomNav.Tab.TALK)

        binding.scenarioList.layoutManager = LinearLayoutManager(this)

        binding.btnVoiceRealtime.setOnClickListener {
            // 实时语音 = 自由聊天，不绑场景（原因见 VoicePrompts 注释）
            startActivity(VoiceChatActivity.intent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        loadScenarios()
    }

    private fun loadScenarios() {
        val lang = AppGraph.settingsRepository.activeLang()
        val module = LanguageRegistry.module(lang)
        scenarios = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))

        binding.voicePartner.text =
            getString(R.string.voice_partner_online, LangStyle.partnerName(lang))

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
