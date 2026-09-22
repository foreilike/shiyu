package com.shiyu.app.data.model

/**
 * 训练卡片（由语言模块从资产 JSON 构建，字段与语言无关）。
 *
 * **字段约定（英日统一，跬步模式：看词回忆释义 → 点开揭示 → 自评）**：
 * - [front]   正面展示的**外语词**（英语单词 / 日语汉字或假名）
 * - [frontSub]副标题（"A1 · n." / "JLPT N5"）
 * - [back]    读音（英语音标 / 日语假名）
 * - [backSub] 罗马音（日语）；英语为空
 * - [meaningZh] 中文释义，**揭示后**才显示
 * - [example] / [exampleZh] 例句与中文译文，揭示后显示
 * - [speakText] 点喇叭朗读的文本
 * - [exampleBlank] 例句挖空（历史字段，当前学习模式已不展示）
 */
data class Card(
    val id: String,
    val front: String,
    val frontSub: String,
    val back: String,
    val backSub: String,
    val meaningZh: String,
    val speakText: String,
    val exampleBlank: String = "",
    val example: String = "",
    val exampleZh: String = ""
)
