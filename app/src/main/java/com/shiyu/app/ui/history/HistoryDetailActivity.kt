package com.shiyu.app.ui.history

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.MODE_REALTIME
import com.shiyu.app.data.model.SessionTurn
import com.shiyu.app.databinding.ActivityHistoryDetailBinding
import com.shiyu.app.databinding.ItemHistoryTurnBinding
import com.shiyu.app.ui.BaseActivity

/**
 * 历史对话详情：把一条会话的原始轮次按气泡渲染出来。
 *
 * 这里**不做**语言处理（不切句、不判题、不拆分三段式）—— 历史是已经发生过的记录，
 * 原样展示即可；当年那条 [JA]/[ZH]/[FIX] 的内容已经作为整体存下来了，
 * 再拆一次既没有上下文也可能拆错。
 */
class HistoryDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInset(binding.turnList)
        binding.headerBar.btnBack.setOnClickListener { finish() }

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val record = sessionId?.let { AppGraph.sessionStore.load(it) }
        if (record == null) {
            finish()
            return
        }

        binding.headerBar.subTitle.text = getString(R.string.history_detail_title)
        val userTurns = record.turns.count { it.role == "user" }
        val meta = mutableListOf(
            LangId.fromTag(record.langId).displayName,
            getString(R.string.history_turns, userTurns)
        )
        if (record.mode == MODE_REALTIME) {
            meta.add(0, getString(R.string.history_mode_realtime))
            if (record.durationSeconds > 0) {
                meta.add(
                    2,
                    getString(
                        R.string.history_duration,
                        record.durationSeconds / 60,
                        record.durationSeconds % 60
                    )
                )
            }
        }
        binding.detailMeta.text = meta.joinToString(" · ")
        binding.turnList.layoutManager = LinearLayoutManager(this)
        binding.turnList.adapter = TurnAdapter(record.turns)
    }

    private class TurnAdapter(private val turns: List<SessionTurn>) :
        RecyclerView.Adapter<TurnAdapter.VH>() {

        class VH(val binding: ItemHistoryTurnBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemHistoryTurnBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val turn = turns[position]
            val ctx = holder.itemView.context
            val isUser = turn.role == "user"
            val b = holder.binding
            b.turnRole.text = ctx.getString(if (isUser) R.string.history_role_me else R.string.history_role_ai)
            b.turnRole.gravity = if (isUser) Gravity.END else Gravity.START
            b.turnText.text = turn.text
            b.turnText.setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai)
            b.turnText.setTextColor(
                ContextCompat.getColor(ctx, if (isUser) R.color.cream else R.color.ink)
            )
            // 用户气泡靠右：靠改外层 LinearLayout 的 gravity 实现，
            // 注意别去动 item 根节点的 LayoutParams（那是 RecyclerView 的，强转必崩）
            val lp = b.turnText.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.gravity = if (isUser) Gravity.END else Gravity.START
                b.turnText.layoutParams = lp
            }
        }

        override fun getItemCount(): Int = turns.size
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
