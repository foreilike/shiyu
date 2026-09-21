package com.shiyu.app.data.model

/** 会话记录（filesDir/sessions/<sessionId>.json） */
data class SessionTurn(val role: String, val text: String)

/** 会话形态：[MODE_CHAT] 回合制场景对话，[MODE_REALTIME] 实时语音 */
const val MODE_CHAT = "chat"
const val MODE_REALTIME = "realtime"

data class SessionRecord(
    val version: Int = 2,
    val sessionId: String,
    val langId: String,
    val scenarioId: String,
    val startedAt: Long,
    /** 老文件没有这个字段，按 [MODE_CHAT] 处理 */
    val mode: String = MODE_CHAT,
    /** 实时语音才有：本轮时长（秒），用于历史列表展示 */
    val durationSeconds: Long = 0L,
    /**
     * 用户自己起的名字。**空串表示没起过**，列表回退到场景名。
     * 后加字段，老文件缺失即空串，不需要迁移。
     */
    val title: String = "",
    val turns: List<SessionTurn> = emptyList()
)
