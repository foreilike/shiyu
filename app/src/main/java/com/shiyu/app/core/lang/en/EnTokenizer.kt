package com.shiyu.app.core.lang.en

import com.shiyu.app.core.lang.Token

/**
 * 英语分词（§5.6.4）。
 * 字符分类用 JDK 的 Character 方法；偏移量按 UTF-16 code unit 计。
 */
object EnTokenizer {

    /** 引号类集合：永不与其他标点合并，单独成 token */
    private val QUOTE_CHARS = setOf(
        '\'', '\u2019', '\u2018', '\u02BC',
        '"', '\u201C', '\u201D',
        '\u00AB', '\u00BB',
        '\u300C', '\u300D', '\u300E', '\u300F',
        '\u2039', '\u203A'
    )

    /** 撇号集合：仅当后一个字符是字母时吸收进词 */
    private val APOSTROPHE_CHARS = setOf('\'', '\u2019', '\u2018', '\u02BC')

    /** 连字符集合（'-' 及 §5.6.3 步骤 3 映射到 '-' 的那些） */
    private val HYPHEN_CHARS = setOf(
        '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
        '\u2212', '\u2043', '\uFE58', '\uFE63'
    )

    fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (isWhitespace(c)) {
                i++
                continue
            }
            val kind = when {
                Character.isLetter(c) -> "WORD"
                Character.isDigit(c) -> "NUMBER"
                else -> "PUNCT"
            }
            if (kind == "PUNCT") {
                if (c in QUOTE_CHARS) {
                    tokens.add(Token("PUNCT", c.toString(), i, i + 1))
                    i += 1
                } else {
                    var j = i
                    while (j < n) {
                        val d = text[j]
                        if (isWhitespace(d) || Character.isLetter(d) ||
                            Character.isDigit(d) || d in QUOTE_CHARS
                        ) break
                        j++
                    }
                    tokens.add(Token("PUNCT", text.substring(i, j), i, j))
                    i = j
                }
            } else {
                var j = i
                while (j < n) {
                    val d = text[j]
                    when {
                        Character.isLetter(d) || Character.isDigit(d) -> j++

                        // 撇号：仅当后一个字符是字母时吸收
                        d in APOSTROPHE_CHARS && j + 1 < n && Character.isLetter(text[j + 1]) -> j++

                        // 连字符：后字符是字母/数字 且 前字符是字母/数字 时吸收
                        d in HYPHEN_CHARS && j + 1 < n && j > i &&
                            (Character.isLetter(text[j + 1]) || Character.isDigit(text[j + 1])) &&
                            (Character.isLetter(text[j - 1]) || Character.isDigit(text[j - 1])) -> j++

                        // 仅 NUMBER：. 或 , 两侧都是数字时吸收
                        kind == "NUMBER" && (d == '.' || d == ',') &&
                            j + 1 < n && j > i &&
                            Character.isDigit(text[j - 1]) && Character.isDigit(text[j + 1]) -> j++

                        else -> break
                    }
                }
                tokens.add(Token(kind, text.substring(i, j), i, j))
                i = j
            }
        }
        return tokens
    }

    private fun isWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
            c == '\u000B' || c == '\u000C' || c == '\u3000'
}
