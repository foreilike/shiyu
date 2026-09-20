package com.shiyu.app.core.context

import com.shiyu.app.data.model.ChatMessage

/**
 * 上下文裁剪（§5.9）。system 恒定在首位；history 只保留 user/assistant；
 * 超过 MAX_HISTORY 时从最前面每次成对移除 2 条；不改动入参。
 */
object ContextTrimmer {

    const val MAX_HISTORY = 24

    fun trim(system: String, history: List<ChatMessage>): List<ChatMessage> {
        val filtered = history.filter { it.role == "user" || it.role == "assistant" }
        var kept = filtered
        while (kept.size > MAX_HISTORY) {
            kept = kept.drop(2)
        }
        val result = mutableListOf(ChatMessage("system", system))
        result.addAll(kept)
        return result
    }
}
