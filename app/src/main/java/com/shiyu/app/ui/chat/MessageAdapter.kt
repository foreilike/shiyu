package com.shiyu.app.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.shiyu.app.R
import com.shiyu.app.core.chat.ParsedReply
import com.shiyu.app.databinding.ItemMessageBinding

/** 聊天气泡的数据项。sentences 由语言模块切分后传入，adapter 不做语言判断。 */
data class MsgItem(
    val role: String,
    val raw: String,
    val sentences: List<String>,
    val translation: String,
    val fix: String
)

class MessageAdapter(
    private val items: List<MsgItem>,
    private val onSentenceClick: (String) -> Unit
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val isUser = item.role == "user"

        val lp = holder.binding.root.layoutParams as LinearLayout.LayoutParams
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        holder.binding.root.layoutParams = lp

        holder.binding.roleLabel.text = if (isUser) "我" else "AI"
        holder.binding.bubble.setBackgroundResource(
            if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai
        )
        val textColor = ContextCompat.getColor(
            ctx,
            if (isUser) R.color.cream else R.color.ink
        )
        val subColor = ContextCompat.getColor(
            ctx,
            if (isUser) R.color.gold else R.color.mute
        )

        holder.binding.sentenceContainer.removeAllViews()
        for (sentence in item.sentences) {
            holder.binding.sentenceContainer.addView(
                TextView(ctx).apply {
                    text = sentence
                    textSize = 16f
                    setTextColor(textColor)
                    if (!isUser) {
                        setOnClickListener { onSentenceClick(sentence) }
                    }
                }
            )
        }

        holder.binding.translationText.setTextColor(subColor)
        if (!isUser && item.translation.isNotEmpty()) {
            holder.binding.translationText.text = item.translation
            holder.binding.translationText.visibility = android.view.View.VISIBLE
        } else {
            holder.binding.translationText.visibility = android.view.View.GONE
        }

        val showFix =
            !isUser && item.fix.isNotEmpty() && item.fix != ctx.getString(R.string.chat_no_fix)
        if (showFix) {
            holder.binding.fixToggle.visibility = android.view.View.VISIBLE
            holder.binding.fixText.text = item.fix
            holder.binding.fixText.visibility = android.view.View.GONE
            holder.binding.fixToggle.setOnClickListener {
                val visible = holder.binding.fixText.visibility == android.view.View.VISIBLE
                holder.binding.fixText.visibility =
                    if (visible) android.view.View.GONE else android.view.View.VISIBLE
            }
        } else {
            holder.binding.fixToggle.visibility = android.view.View.GONE
            holder.binding.fixText.visibility = android.view.View.GONE
        }
    }

    override fun getItemCount(): Int = items.size
}
