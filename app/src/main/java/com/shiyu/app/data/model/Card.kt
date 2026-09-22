package com.shiyu.app.data.model

/**
 * 训练卡片（由语言模块从资产 JSON 构建，字段与语言无关）。
 * - exampleBlank：正面提示用。英语把例句中的目标词替换为 ____ 置于此；日语一般为空。
 * - example / exampleZh：翻面后展示的**完整例句 + 中文译文**（参照跬步的
 *   word/phonetic/meaning/example/exampleCn 模式）。缺省空串，不影响既有构造签名。
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
