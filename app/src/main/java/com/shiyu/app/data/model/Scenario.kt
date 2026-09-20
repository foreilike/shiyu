package com.shiyu.app.data.model

/** 对话场景（scenarios_*.json） */
data class Scenario(
    val id: String,
    val titleTarget: String,
    val titleZh: String,
    val systemPrompt: String,
    val openingLine: String,
    val goalExpressions: List<String>
)
