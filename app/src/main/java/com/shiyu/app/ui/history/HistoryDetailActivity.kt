package com.shiyu.app.ui.history

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.chat.ReplyParser
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.MODE_REALTIME
import com.shiyu.app.databinding.ActivityHistoryDetailBinding
import com.shiyu.app.databinding.ItemHistoryTurnBinding
import com.shiyu.app.ui.BaseActivity

/**
 * 历史对话详情：把一条会话的原始轮次渲染成气泡。
 *
 * **助手发言要走 `ReplyParser` 再展示**：回合制对话存下来的是模型原文（含 `[EN]/[ZH]/[FIX]`
 * 标签），当时在对话页里是拆开渲染的。如果这里原样吐出来，用户看到的会是带着方括号标签的
 * 一大坨 —— 同一个对话在两处长得不一样，而且历史这版更难读。
 *
 * 实时语音的助手发言没有标签（提示词里就禁止了），解析后 body 即原文，不会误伤。
 */
class HistoryDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryDetailBinding

    /** 渲染用的一轮：已经把三段式拆好 */
    private data class DisplayTurn(
        val role: String,
        val body: String,
        val translation: String,
        val fix: String
    )

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

        // 起过名字就用名字，否则用「对话详情」；起过名之后列表和详情才叫得一致
        binding.headerBar.subTitle.text = record.title.ifBlank {
            getString(R.string.history_detail_title)
        }
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

        val module = LanguageRegistry.module(LangId.fromTag(record.langId))
        val noFix = getString(R.string.chat_no_fix)
        val display = record.turns.map { turn ->
            if (turn.role == "user") {
                DisplayTurn(turn.role, turn.text, "", "")
            } else {
                val parsed = ReplyParser.parse(module, turn.text)
                DisplayTurn(
                    role = turn.role,
                    body = parsed.body,
                    translation = parsed.translation,
                    fix = parsed.fix.takeIf { it.isNotEmpty() && it != noFix }.orEmpty()
                )
            }
        }

        binding.turnList.layoutManager = LinearLayoutManager(this)
        binding.turnList.adapter = TurnAdapter(display)
    }

    private class TurnAdapter(private val turns: List<DisplayTurn>) :
        RecyclerView.Adapter<TurnAdapter.VH>() {

        class VH(val binding: ItemHistoryTurnBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemHistoryTurnBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val turn = turns[position]
            val ctx = holder.itemView.context
            val isUser = turn.role == "user"
            val b = holder.binding

            b.turnRole.text = ctx.getString(
                if (isUser) R.string.history_role_me else R.string.history_role_ai
            )
            b.turnRole.gravity = if (isUser) Gravity.END else Gravity.START

            b.turnText.text = turn.body
            b.turnBubble.setBackgroundResource(
                if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai
            )
            b.turnText.setTextColor(
                ContextCompat.getColor(ctx, if (isUser) R.color.cream else R.color.ink)
            )
            b.turnTranslation.setTextColor(
                ContextCompat.getColor(ctx, if (isUser) R.color.clay_soft else R.color.mute)
            )

            b.turnTranslation.text = turn.translation
            b.turnTranslation.visibility = if (turn.translation.isBlank()) View.GONE else View.VISIBLE
            b.turnFix.text = turn.fix
            b.turnFix.visibility = if (turn.fix.isBlank()) View.GONE else View.VISIBLE

            // 用户气泡靠右：改的是气泡自己的 LayoutParams（父容器是 LinearLayout，
            // 所以这里拿到的一定是 LinearLayout.LayoutParams）。**别去动 item 根节点** ——
            // 那是 RecyclerView 挂上去的 LayoutParams，强转必崩。
            val lp = b.turnBubble.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.gravity = if (isUser) Gravity.END else Gravity.START
                b.turnBubble.layoutParams = lp
            }
        }

        override fun getItemCount(): Int = turns.size
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
