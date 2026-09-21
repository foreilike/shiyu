package com.shiyu.app.ui.history

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.MODE_REALTIME
import com.shiyu.app.data.model.SessionRecord
import com.shiyu.app.data.store.SessionStore
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
 * 同一个页面承载两种模式，靠 [EXTRA_TRASH] 切换 —— 列表和行布局完全复用，
 * 只是每行的动作不同：
 *
 * | | 普通模式 | 回收站模式 |
 * |---|---|---|
 * | 点整行 | 看详情 | 恢复 |
 * | 铅笔 | 重命名 | （隐藏）|
 * | 垃圾桶 | 移入回收站 | 彻底删除 |
 *
 * 删除做成「先移入回收站」而不是真删：对话是攒出来的东西，误删一次就没了太可惜。
 */
class HistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryBinding

    private val trashMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_TRASH, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInset(binding.historyList)
        binding.headerBar.subTitle.text = getString(
            if (trashMode) R.string.history_trash else R.string.history_title
        )
        binding.historySub.text = getString(
            if (trashMode) R.string.history_trash_sub else R.string.history_sub
        )
        binding.headerBar.btnBack.setOnClickListener { finish() }
        binding.historyList.layoutManager = LinearLayoutManager(this)
        setupHeaderAction()
    }

    /** 页头右侧：普通模式进回收站，回收站模式清空回收站 */
    private fun setupHeaderAction() {
        // 复用 view_sub_header 预留的 subAction 槽位，不用再动布局
        val action = TextView(this).apply {
            setText(if (trashMode) R.string.history_empty_trash else R.string.history_trash)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.clay))
            setPadding(28, 20, 28, 20)
            setOnClickListener {
                if (trashMode) confirmEmptyTrash() else openTrash()
            }
        }
        binding.headerBar.subAction.addView(action)
    }

    private fun openTrash() {
        startActivity(
            Intent(this, HistoryActivity::class.java).putExtra(EXTRA_TRASH, true)
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // 普通模式只列有用户发言的会话：模型开场白之后一次都没接话的空会话
        // （进错场景就退出、或早先版本在绑定阶段崩溃留下的）不该在历史里冒充一条记录。
        val records = if (trashMode) {
            AppGraph.sessionStore.trashedSessions()
        } else {
            AppGraph.sessionStore.allSessionsNewestFirst()
                .filter { record -> record.turns.any { it.role == "user" } }
        }
        binding.emptyHint.text = getString(
            if (trashMode) R.string.history_trash_empty else R.string.history_empty
        )
        binding.emptyHint.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        val titles = buildScenarioTitles(records)
        binding.historyList.adapter = HistoryAdapter(
            records = records,
            titles = titles,
            trashMode = trashMode,
            onClick = { record -> if (trashMode) confirmRestore(record) else openDetail(record) },
            onRename = { record -> renameDialog(record, titles) },
            onDelete = { record ->
                if (trashMode) confirmPurge(record, titles) else confirmTrash(record, titles)
            }
        )
    }

    private fun openDetail(record: SessionRecord) {
        startActivity(
            Intent(this, HistoryDetailActivity::class.java)
                .putExtra(HistoryDetailActivity.EXTRA_SESSION_ID, record.sessionId)
        )
    }

    /** 有自定义名就用自定义名，否则回退到场景名 */
    private fun nameOf(record: SessionRecord, titles: Map<String, String>): String =
        record.title.ifBlank { titles["${record.langId}/${record.scenarioId}"] ?: record.scenarioId }

    // ---------- 重命名 ----------

    private fun renameDialog(record: SessionRecord, titles: Map<String, String>) {
        val input = EditText(this).apply {
            setText(record.title)
            setHint(titles["${record.langId}/${record.scenarioId}"] ?: record.scenarioId)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(SessionStore.MAX_TITLE_LENGTH))
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_rename_title)
            .setView(wrapper)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                AppGraph.sessionStore.rename(record.sessionId, input.text.toString())
                refresh()
            }
            .show()
    }

    // ---------- 删除 / 恢复 ----------

    /** 移入回收站（可恢复，文案说清楚"之后还能找回"） */
    private fun confirmTrash(record: SessionRecord, titles: Map<String, String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(getString(R.string.history_trash_confirm, nameOf(record, titles)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                AppGraph.sessionStore.trash(record.sessionId)
                toast(R.string.history_trashed)
                refresh()
            }
            .show()
    }

    private fun confirmRestore(record: SessionRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_restore_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                AppGraph.sessionStore.restore(record.sessionId)
                toast(R.string.history_restored)
                refresh()
            }
            .show()
    }

    private fun confirmPurge(record: SessionRecord, titles: Map<String, String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(getString(R.string.history_purge_confirm, nameOf(record, titles)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                AppGraph.sessionStore.purge(record.sessionId)
                toast(R.string.history_purged)
                refresh()
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_empty_trash)
            .setMessage(R.string.history_empty_trash_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val n = AppGraph.sessionStore.emptyTrash()
                Toast.makeText(this, getString(R.string.history_trash_cleared, n), Toast.LENGTH_SHORT)
                    .show()
                refresh()
            }
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
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
        private val titles: Map<String, String>,
        private val trashMode: Boolean,
        private val onClick: (SessionRecord) -> Unit,
        private val onRename: (SessionRecord) -> Unit,
        private val onDelete: (SessionRecord) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = records[position]
            val ctx = holder.itemView.context
            val b = holder.binding
            val realtime = record.mode == MODE_REALTIME

            b.modeBadge.text = ctx.getString(
                if (realtime) R.string.history_mode_realtime else R.string.history_mode_chat
            )
            b.historyTitle.text =
                record.title.ifBlank { titles["${record.langId}/${record.scenarioId}"] ?: record.scenarioId }

            val userTurns = record.turns.count { it.role == "user" }
            val parts = mutableListOf(
                LangId.fromTag(record.langId).displayName,
                relativeTime(record.startedAt),
                ctx.getString(R.string.history_turns, userTurns)
            )
            if (realtime && record.durationSeconds > 0) {
                parts.add(
                    ctx.getString(
                        R.string.history_duration,
                        record.durationSeconds / 60,
                        record.durationSeconds % 60
                    )
                )
            }
            b.historyMeta.text = parts.joinToString(" · ")

            // 用第一句 AI 的话当预览
            b.historyPreview.text = record.turns.firstOrNull()?.text.orEmpty().replace("\n", " ")

            // 回收站里不给改名；垃圾桶换成警示红，和"移入回收站"区分开
            b.btnRename.visibility = if (trashMode) View.GONE else View.VISIBLE
            if (trashMode) {
                b.btnDelete.setColorFilter(ContextCompat.getColor(ctx, R.color.bad_red))
            }

            b.btnRename.setOnClickListener { onRename(record) }
            b.btnDelete.setOnClickListener { onDelete(record) }
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

    companion object {
        const val EXTRA_TRASH = "extra_trash"
    }
}
