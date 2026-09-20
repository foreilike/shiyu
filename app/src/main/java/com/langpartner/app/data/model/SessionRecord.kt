package com.langpartner.app.data.model

/** 会话记录（filesDir/sessions/<sessionId>.json） */
data class SessionTurn(val role: String, val text: String)

data class SessionRecord(
    val version: Int = 2,
    val sessionId: String,
    val langId: String,
    val scenarioId: String,
    val startedAt: Long,
    val turns: List<SessionTurn> = emptyList()
)
