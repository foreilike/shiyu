package com.shiyu.app.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.MODE_REALTIME
import com.shiyu.app.data.model.SessionRecord
import com.shiyu.app.databinding.ActivityHistoryBinding
import com.shiyu.app.databinding.ItemHistoryBinding
import com.shiyu.app.ui.BaseActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史对话（二级页，入口在「我的」）。
 *
 * 两类会话都在这里：回合制场景对话（每轮即时落盘的）和实时语音（结束时整条落盘的）。
 * 会话文件里只存了 scenarioId，场景标题要按 langId 找回对应的语言模块去查。
 */
class HistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInset(binding.historyList)
        binding.headerBar.subTitle.text = getString(R.string.history_title)
        binding.headerBar.btnBack.setOnClickListener { finish() }
        binding.historyList.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        // 只列有用户发言的会话：模型开场白之后一次都没接话的空会话
        // （比如进错场景就退出、或早先版本在绑定阶段崩溃留下的）留在磁盘上没问题，
        // 但不该出现在历史列表里冒充一条记录。
        val records = AppGraph.sessionStore.allSessionsNewestFirst()
            .filter { record -> record.turns.any { it.role == "user" } }
        binding.emptyHint.visibility = if (records.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
        binding.historyList.adapter = HistoryAdapter(records, buildScenarioTitles(records)) { record ->
            startActivity(
                Intent(this, HistoryDetailActivity::class.java)
                    .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, record.sessionId)
            )
        }
    }

    /**
     * 会话文件里只存了 langId + scenarioId，标题要按语言查回场景。
     * 每门语言只解析一次，算成一张表交给 adapter —— 别让它逐行去查。
     */
    private fun buildScenarioTitles(records: List<SessionRecord>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (langTag in records.map { it.langId }.distinct()) {
            val module = LanguageRegistry.module(LangId.fromTag(langTag))
            val scenarios = try {
                module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
            } catch (e: Exception) {
                continue
            }
            for (s in scenarios) {
                out["$langTag/${s.id}"] = s.titleZh.ifBlank { s.titleTarget }
            }
        }
        return out
    }

    private class HistoryAdapter(
        private val records: List<SessionRecord>,
        /** 预先算好的 scenarioId → 场景标题，别在 onBindViewHolder 里现查 ——
         *  那样每滚一行都要重新读一次 assets 并解析场景 JSON。 */
        private val scenarioTitles: Map<String, String>,
        private val onClick: (SessionRecord) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = records[position]
            val ctx = holder.itemView.context
            val realtime = record.mode == MODE_REALTIME
            holder.binding.modeBadge.text = ctx.getString(
                if (realtime) R.string.history_mode_realtime else R.string.history_mode_chat
            )
            holder.binding.historyTitle.text =
                scenarioTitles["${record.langId}/${record.scenarioId}"] ?: record.scenarioId

            val userTurns = record.turns.count { it.role == "user" }
            val parts = mutableListOf(
                LangId.fromTag(record.langId).displayName,
                relativeTime(record.startedAt),
                ctx.getString(R.string.history_turns, userTurns)
            )
            if (realtime && record.durationSeconds > 0) {
                parts.add(ctx.getString(R.string.history_duration, record.durationSeconds / 60, record.durationSeconds % 60))
            }
            holder.binding.historyMeta.text = parts.joinToString(" · ")

            // 用第一句 AI 的话当预览
            val preview = record.turns.firstOrNull()?.text.orEmpty().replace("\n", " ")
            holder.binding.historyPreview.text = preview
            holder.itemView.setOnClickListener { onClick(record) }
        }

        override fun getItemCount(): Int = records.size

        private fun relativeTime(epochMillis: Long): String {
            if (epochMillis <= 0L) return ""
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            val today = LocalDate.now(zone)
            return when (day) {
                today -> "今天 $time"
                today.minusDays(1) -> "昨天 $time"
                else -> "${day.monthValue}月${day.dayOfMonth}日 $time"
            }
        }
    }
}
