package com.shiyu.app.data.model

/** 对话消息。role ∈ {"system", "user", "assistant"} */
data class ChatMessage(val role: String, val content: String)
