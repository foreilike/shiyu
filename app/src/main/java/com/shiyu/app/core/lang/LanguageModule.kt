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

    /** 资产文件名三元组：(chart, deck, scenarios)。deck 为该语言的**默认词书**，多词书见 [books]。 */
    fun assetNames(): Triple<String, String, String>

    /** 该语言的可选词书/级别列表（第一本为默认）。UI 按当前书加载牌堆。 */
    fun books(): List<WordBook>

    /** 从资产 JSON 文本构建各语料（纯函数，输入是 JSON 字符串，便于单测） */
    fun buildChart(json: String): List<ChartEntry>
    fun buildDeck(json: String): List<Card>
    fun buildScenarios(json: String): List<Scenario>

    /** 分句（两种语言共用逐句朗读 UI；英语为 EnSentenceSplitter，日语按 。！？ 切分） */
    fun splitSentences(text: String): List<String>

    /**
     * 拼写考试（进阶模式）里**可接受**的作答，任一命中即算对。
     *
     * 默认只有 [Card.front]；日语覆写为「假名读音 + 汉字写法」都可——
     * 初学者记的是读音，认汉字的人写汉字也该算对，不该硬卡一种写法。
     */
    fun examAnswers(card: Card): List<String> = listOf(card.front)
}
