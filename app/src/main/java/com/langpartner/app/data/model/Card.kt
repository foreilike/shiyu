package com.langpartner.app.data.model

/**
 * 训练卡片（由语言模块从资产 JSON 构建，字段与语言无关）。
 * exampleBlank 为可选项：英语卡片把例句中的目标词替换为 ____ 后置于此，
 * 未提供时为空串，不影响既有的构造签名。
 */
data class Card(
    val id: String,
    val front: String,
    val frontSub: String,
    val back: String,
    val backSub: String,
    val meaningZh: String,
    val speakText: String,
    val exampleBlank: String = ""
)
