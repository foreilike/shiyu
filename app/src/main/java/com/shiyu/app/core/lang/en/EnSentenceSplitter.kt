package com.shiyu.app.core.lang.en

/**
 * 英语分句（§5.6.5）。两种语言共用逐句朗读 UI。
 *
 * 终止符合并后逐段判定是否真的句末：
 * - ! ? ！ ？ … 。 → 直接终止
 * - . → 点后非空白且非 CLOSER 则不终止；否则回看 core 判缩写表 / 全大写缩写
 */
object EnSentenceSplitter {

    private val TERM = setOf('.', '!', '?', '。', '！', '？', '…')
    private val CLOSER = setOf('"', '\'', '\u2019', '\u201D', ')', ']', '}', '」', '』')

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "a.m", "p.m",
        "fig", "vol", "approx", "dept", "est", "inc", "ltd", "corp", "blvd", "ave", "rd", "apt", "univ",
        "gen", "gov", "sen", "rep", "capt", "sgt", "maj", "lt", "col"
    )

    fun split(text: String): List<String> {
        val result = mutableListOf<String>()
        val n = text.length
        var start = 0
        var i = 0
        while (i < n) {
            if (text[i] !in TERM) {
                i++
                continue
            }
            // 合并连续终止符
            var j = i
            while (j < n && text[j] in TERM) j++
            if (isTerminal(text, i, j, n)) {
                // 向右吸收收尾符
                var e = j
                while (e < n && text[e] in CLOSER) e++
                val sentence = text.substring(start, e).trim()
                if (sentence.isNotEmpty()) result.add(sentence)
                start = e
                i = e
            } else {
                i = j
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    /** 判定从 i 开始、j 结束的终止符段是否句末 */
    private fun isTerminal(text: String, i: Int, j: Int, n: Int): Boolean {
        val first = text[i]
        if (first != '.') return true // ! ? 。 ！ ？ …
        if (j >= n) return true          // 点就是文本最后一个字符
        val after = text[j]
        if (!after.isWhitespace() && after !in CLOSER) return false
        // 回看：从 i 往左取连续非空白字符作为 core，去掉末尾所有的 .
        val sb = StringBuilder()
        var k = i - 1
        while (k >= 0 && !text[k].isWhitespace()) {
            sb.insert(0, text[k])
            k--
        }
        val core = sb.toString().trimEnd('.')
        if (core.lowercase() in ABBREVIATIONS) return false
        val stripped = core.replace(".", "")
        if (core.contains(".") && stripped.isNotEmpty() &&
            stripped.length in 1..4 && stripped.all { it in 'A'..'Z' }
        ) return false
        return true
    }
}
