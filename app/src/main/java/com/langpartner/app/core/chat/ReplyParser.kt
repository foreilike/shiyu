package com.langpartner.app.core.chat

import com.langpartner.app.core.lang.LanguageModule

/**
 * 三分段回复解析结果（§M2-4）
 */
data class ParsedReply(val body: String, val translation: String, val fix: String)

/**
 * 按行首标记解析 AI 回复：主标签由语言模块给出（JA / EN），另有 [ZH] 与 [FIX]。
 * 方括号内标签大小写不敏感、标签前后允许空格；缺失段留空；
 * 一个标签都没匹配到时整段当正文、不显示纠错块；任何格式都不抛异常。
 */
object ReplyParser {

    private val TAG_LINE = Regex("""^\s*\[\s*([A-Za-z]+)\s*\]\s*(.*)$""")

    fun parse(module: LanguageModule, raw: String): ParsedReply {
        val mainTag = module.replyTag.uppercase()
        val lines = raw.lines()

        // 是否出现过分段标签
        var hasMain = false
        var hasZh = false
        var hasFix = false
        for (line in lines) {
            val m = TAG_LINE.matchEntire(line) ?: continue
            when (m.groupValues[1].uppercase()) {
                mainTag -> hasMain = true
                "ZH" -> hasZh = true
                "FIX" -> hasFix = true
            }
        }
        if (!hasMain && !hasZh && !hasFix) {
            return ParsedReply(raw.trim(), "", "")
        }

        val body = StringBuilder()
        val zh = StringBuilder()
        val fix = StringBuilder()
        var current: StringBuilder? = null

        for (line in lines) {
            val m = TAG_LINE.matchEntire(line)
            if (m == null) {
                current?.let { appendLine(it, line) }
                continue
            }
            val label = m.groupValues[1].uppercase()
            val rest = m.groupValues[2]
            when (label) {
                mainTag -> {
                    current = body
                    appendLine(body, rest)
                }
                "ZH" -> {
                    current = zh
                    appendLine(zh, rest)
                }
                "FIX" -> {
                    current = fix
                    appendLine(fix, rest)
                }
                else -> current?.let { appendLine(it, line) } // 未知标签行按普通文本续接
            }
        }

        return ParsedReply(
            body = body.toString().trim(),
            translation = zh.toString().trim(),
            fix = fix.toString().trim()
        )
    }

    private fun appendLine(sb: StringBuilder, text: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(text)
    }
}
