package com.shiyu.app.core.lang

import com.shiyu.app.data.model.Card
import com.shiyu.app.data.model.ChartEntry
import com.shiyu.app.data.model.Scenario

/**
 * 分词 token。start/end 是以 UTF-16 code unit 计的半开区间 [start, end)。
 */
data class Token(val kind: String, val text: String, val start: Int, val end: Int)

/**
 * 语言模块接口：所有语言相关差异收口在这里。
 * ui/ / ai/ / data/ 只允许持有 LanguageModule 引用，不允许出现语言专有分支。
 */
interface LanguageModule {
    val id: LangId

    /** 输入框占位提示，如「输入假名读法」/「拼写英文单词」 */
    val answerHint: String

    /** AI 回复的主标签，如 "JA" / "EN" */
    val replyTag: String

    /** 归一化后用于判题比较（§5.7：先各自归一化再比较） */
    fun normalizeForCompare(s: String): String

    /** 资产文件名三元组：(chart, deck, scenarios) */
    fun assetNames(): Triple<String, String, String>

    /** 从资产 JSON 文本构建各语料（纯函数，输入是 JSON 字符串，便于单测） */
    fun buildChart(json: String): List<ChartEntry>
    fun buildDeck(json: String): List<Card>
    fun buildScenarios(json: String): List<Scenario>

    /** 分句（两种语言共用逐句朗读 UI；英语为 EnSentenceSplitter，日语按 。！？ 切分） */
    fun splitSentences(text: String): List<String>
}
