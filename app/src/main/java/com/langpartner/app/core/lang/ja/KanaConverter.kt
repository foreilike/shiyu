package com.langpartner.app.core.lang.ja

/**
 * 罗马字 → 平假名转换结果。unparsed 记录未能识别的字符（原样透传但计入）。
 */
data class ConvertResult(val kana: String, val unparsed: List<String>)

/**
 * 罗马字 → 假名转换器（§5.6.2）。
 *
 * 规则优先级（命中即处理并推进指针）：
 * 1. 促音（双辅音，n 不在辅音集合内）
 * 2. 促音特例 tch
 * 3. 拨音 n（nn 只消耗一个 n；n' 显式分隔；母音/y 前交给规则 4）
 * 4. 最长匹配查表（3 → 2 → 1）
 * 5. 未识别字符原样输出并计入 unparsed；半角连字符 - 输出 ー
 *
 * 明确不做：助词特殊化（は/へ/を 读音例外）、外来语扩展音节（ファ/ティ/ウィ/シェ）。
 */
object KanaConverter {

    private val CONSONANTS = setOf(
        'b', 'c', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'm',
        'p', 'r', 's', 't', 'v', 'w', 'x', 'y', 'z'
    )
    private val VOWELS = setOf('a', 'i', 'u', 'e', 'o')

    private val SYLLABLES: Map<String, String> = buildMap {
        // 母音
        put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")
        // か行
        put("ka", "か"); put("ki", "き"); put("ku", "く"); put("ke", "け"); put("ko", "こ")
        // さ行
        put("sa", "さ"); put("su", "す"); put("se", "せ"); put("so", "そ")
        put("shi", "し"); put("si", "し")
        // た行
        put("ta", "た"); put("te", "て"); put("to", "と")
        put("chi", "ち"); put("ti", "ち")
        put("tsu", "つ"); put("tu", "つ")
        // な行
        put("na", "な"); put("ni", "に"); put("nu", "ぬ"); put("ne", "ね"); put("no", "の")
        // は行
        put("ha", "は"); put("hi", "ひ"); put("he", "へ"); put("ho", "ほ")
        put("fu", "ふ"); put("hu", "ふ")
        // ま行
        put("ma", "ま"); put("mi", "み"); put("mu", "む"); put("me", "め"); put("mo", "も")
        // や行
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ")
        // ら行
        put("ra", "ら"); put("ri", "り"); put("ru", "る"); put("re", "れ"); put("ro", "ろ")
        // わ行
        put("wa", "わ"); put("wo", "を")
        // が行
        put("ga", "が"); put("gi", "ぎ"); put("gu", "ぐ"); put("ge", "げ"); put("go", "ご")
        // ざ行
        put("za", "ざ"); put("zu", "ず"); put("ze", "ぜ"); put("zo", "ぞ")
        put("ji", "じ"); put("zi", "じ")
        // だ行
        put("da", "だ"); put("de", "で"); put("do", "ど")
        put("di", "ぢ"); put("du", "づ")
        // ば行
        put("ba", "ば"); put("bi", "び"); put("bu", "ぶ"); put("be", "べ"); put("bo", "ぼ")
        // ぱ行
        put("pa", "ぱ"); put("pi", "ぴ"); put("pu", "ぷ"); put("pe", "ぺ"); put("po", "ぽ")
        // 拗音 き
        put("kya", "きゃ"); put("kyu", "きゅ"); put("kyo", "きょ")
        // 拗音 し
        put("sha", "しゃ"); put("sya", "しゃ")
        put("shu", "しゅ"); put("syu", "しゅ")
        put("sho", "しょ"); put("syo", "しょ")
        // 拗音 ち
        put("cha", "ちゃ"); put("tya", "ちゃ")
        put("chu", "ちゅ"); put("tyu", "ちゅ")
        put("cho", "ちょ"); put("tyo", "ちょ")
        // 拗音 に
        put("nya", "にゃ"); put("nyu", "にゅ"); put("nyo", "にょ")
        // 拗音 ひ
        put("hya", "ひゃ"); put("hyu", "ひゅ"); put("hyo", "ひょ")
        // 拗音 み
        put("mya", "みゃ"); put("myu", "みゅ"); put("myo", "みょ")
        // 拗音 り
        put("rya", "りゃ"); put("ryu", "りゅ"); put("ryo", "りょ")
        // 拗音 ぎ
        put("gya", "ぎゃ"); put("gyu", "ぎゅ"); put("gyo", "ぎょ")
        // 拗音 じ
        put("ja", "じゃ"); put("zya", "じゃ")
        put("ju", "じゅ"); put("zyu", "じゅ")
        put("jo", "じょ"); put("zyo", "じょ")
        // 拗音 び
        put("bya", "びゃ"); put("byu", "びゅ"); put("byo", "びょ")
        // 拗音 ぴ
        put("pya", "ぴゃ"); put("pyu", "ぴゅ"); put("pyo", "ぴょ")
    }

    fun toHiragana(romaji: String): ConvertResult {
        val s = preprocess(romaji)
        val out = StringBuilder()
        val unparsed = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]

            // 规则 1：促音（双辅音）
            if (i + 1 < s.length && c == s[i + 1] && c in CONSONANTS) {
                out.append('っ')
                i += 1
                continue
            }

            // 规则 2：促音特例 tch
            if (c == 't' && i + 2 < s.length && s[i + 1] == 'c' && s[i + 2] == 'h') {
                out.append('っ')
                i += 1
                continue
            }

            // 规则 3：拨音 n
            if (c == 'n') {
                val next = if (i + 1 < s.length) s[i + 1] else null
                when {
                    // nn 位于结尾：合成一个 ん，消耗两个 n
                    next == 'n' && i + 2 >= s.length -> {
                        out.append('ん'); i += 2; continue
                    }
                    // nn 后还有字符：只消耗一个 n，第二个留给后面的音节
                    next == 'n' -> {
                        out.append('ん'); i += 1; continue
                    }
                    // n' 显式分隔
                    next == '\'' -> {
                        out.append('ん'); i += 2; continue
                    }
                    // 词尾 n
                    next == null -> {
                        out.append('ん'); i += 1; continue
                    }
                    // 母音或 y 前：不构成拨音，交给规则 4
                    next in VOWELS || next == 'y' -> Unit
                    // 其它辅音前：拨音
                    else -> {
                        out.append('ん'); i += 1; continue
                    }
                }
            }

            // 规则 4：最长匹配查表（3 → 2 → 1）
            var matched = false
            var len = 3
            while (len >= 1) {
                if (i + len <= s.length) {
                    val kana = SYLLABLES[s.substring(i, i + len)]
                    if (kana != null) {
                        out.append(kana)
                        i += len
                        matched = true
                        break
                    }
                }
                len -= 1
            }
            if (matched) continue

            // 长音：半角连字符 → ー（不计入 unparsed）
            if (c == '-') {
                out.append('ー')
                i += 1
                continue
            }

            // 规则 5：未识别字符。撇号原样透传但不计入 unparsed；其余计入。
            out.append(c)
            if (c != '\'') {
                unparsed.add(c.toString())
            }
            i += 1
        }
        return ConvertResult(out.toString(), unparsed)
    }

    /** trim → 小写 → 全角字母转半角 */
    private fun preprocess(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim()) {
            val code = ch.code
            if (code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A) {
                sb.append((code - 0xFEE0).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().lowercase()
    }
}
