package com.shiyu.app.core.lang.ja

import java.text.Normalizer

/**
 * 日语假名归一化（§5.6.3）。
 *
 * halfWidthToFullWidth 必须用 NFKC 实现（自动完成半角片假名 → 全角、
 * 半角浊音点/半浊音点与前-假名合并、全角字母数字 → 半角）。
 */
object KanaNormalizer {

    /** 全角字母数字 → 半角 */
    fun toHalfWidthAlnum(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val code = ch.code
            if (code in 0xFF10..0xFF19 || code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A) {
                sb.append((code - 0xFEE0).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 半角 → 全角（含浊音符号并），强制走 NFKC，禁止手写映射表 */
    fun halfWidthToFullWidth(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)

    /** 片假名 → 平假名：U+30A1..U+30F6 减 0x60；其余（含 U+30F7..U+30FA、中点、长音符）保持不变 */
    fun katakanaToHiragana(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val code = ch.code
            if (code in 0x30A1..0x30F6) {
                sb.append((code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 各种横线统一为 ー（U+30FC） */
    fun unifyLongVowelMark(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val code = ch.code
            if (code == 0x002D || code == 0x2010 || code == 0x2011 || code == 0x2212 || code == 0xFF0D) {
                sb.append('ー')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 顺序固定：toHalfWidthAlnum → halfWidthToFullWidth → katakanaToHiragana → unifyLongVowelMark */
    fun normalize(s: String): String =
        unifyLongVowelMark(katakanaToHiragana(halfWidthToFullWidth(toHalfWidthAlnum(s))))
}
