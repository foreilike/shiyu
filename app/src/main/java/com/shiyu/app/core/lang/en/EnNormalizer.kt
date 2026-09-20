package com.shiyu.app.core.lang.en

import java.text.Normalizer
import java.util.Locale

/**
 * 英语归一化（§5.6.3）。严格按顺序执行：
 * 1. NFKC
 * 2. 弯引号/变体引号 → ASCII 直引号
 * 3. 各类连字符/破折号 → '-'
 * 4. 剥离拉丁字母变音符号（分解后首字符是 [A-Za-z0-9] 才输出首字符，否则原样输出）
 * 5. 小写（必须 Locale.ROOT）
 * 6. 空白折叠
 *
 * 明确不做：字母级变体替换（ß 不展开）、词形还原、拼写纠正。
 */
object EnNormalizer {

    private val SINGLE_QUOTE_SOURCES = setOf(
        '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '\u02BC', '\u00B4', '\u0060'
    )
    private val DOUBLE_QUOTE_SOURCES = setOf(
        '\u201C', '\u201D', '\u201E', '\u2033'
    )
    private val DASH_SOURCES = setOf(
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
        '\u2212', '\u2043', '\uFE58', '\uFE63'
    )

    fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKC)
        t = mapQuotes(t)
        t = mapDashes(t)
        t = stripDiacritics(t)
        t = t.lowercase(Locale.ROOT)
        t = collapseWhitespace(t)
        return t
    }

    private fun mapQuotes(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    in SINGLE_QUOTE_SOURCES -> '\''
                    in DOUBLE_QUOTE_SOURCES -> '"'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun mapDashes(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(if (ch in DASH_SOURCES) '-' else ch)
        }
        return sb.toString()
    }

    /**
     * 逐字符 NFD 分解：分解后首字符属于 [A-Za-z0-9] 时只输出首字符（丢弃组合记号），
     * 否则原样输出该字符（这条保证 が/日本語 等混排文本不被破坏）。
     */
    private fun stripDiacritics(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            val first = decomposed.firstOrNull()
            if (first != null && (first in 'A'..'Z' || first in 'a'..'z' || first in '0'..'9')) {
                sb.append(first)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 连续空白（含制表、换行、U+3000）折叠为单个空格，并去首尾空白 */
    private fun collapseWhitespace(s: String): String {
        val sb = StringBuilder()
        var pendingSpace = false
        for (ch in s) {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
                ch == '\u000B' || ch == '\u000C' || ch == '\u3000'
            ) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) sb.append(' ')
                sb.append(ch)
                pendingSpace = false
            }
        }
        return sb.toString()
    }
}
