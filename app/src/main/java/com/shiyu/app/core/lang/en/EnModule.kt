package com.shiyu.app.core.lang.en

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.data.model.Card
import com.shiyu.app.data.model.ChartEntry
import com.shiyu.app.data.model.Scenario
import com.shiyu.app.core.lang.ja.ScenarioParser
import com.shiyu.app.core.lang.ja.str
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 英语模块（§5.6.6 映射表）：
 * front = meaningZh；frontSub = level + " · " + pos；back = word；
 * backSub = ipa；meaningZh = 空串（释义已在正面）；speakText = word；
 * exampleBlank = 例句中不区分大小写地把 word 首次出现替换为 ____。
 */
class EnModule : LanguageModule {

    override val id: LangId = LangId.EN
    override val answerHint: String = "拼写英文单词"
    override val replyTag: String = "EN"

    /** EnNormalizer.normalize → 删除所有空白 → 删除所有非字母数字字符（§5.7 英语侧） */
    override fun normalizeForCompare(s: String): String {
        val normalized = EnNormalizer.normalize(s)
        val sb = StringBuilder()
        for (ch in normalized) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    override fun assetNames(): Triple<String, String, String> =
        Triple("en_phonemes.json", "en_words.json", "scenarios_en.json")

    override fun buildChart(json: String): List<ChartEntry> {
        val arr: JSONArray = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = mutableListOf<ChartEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val symbol = o.str("symbol")
            if (symbol.isEmpty()) continue
            val example = o.str("example")
            out.add(
                ChartEntry(
                    cell = symbol,
                    sub = example,
                    speak = example,
                    alt = "",
                    note = o.str("exampleZh"),
                    section = o.str("type")
                )
            )
        }
        return out
    }

    override fun buildDeck(json: String): List<Card> {
        val arr: JSONArray = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = mutableListOf<Card>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("id")
            val word = o.str("word")
            if (id.isEmpty() || word.isEmpty()) continue
            val meaningZh = o.str("meaningZh")
            val example = o.str("example")
            out.add(
                Card(
                    id = id,
                    front = meaningZh,
                    frontSub = o.str("level") + " · " + o.str("pos"),
                    back = word,
                    backSub = o.str("ipa"),
                    meaningZh = "",
                    speakText = word,
                    exampleBlank = blankExample(word, example)
                )
            )
        }
        return out
    }

    override fun buildScenarios(json: String): List<Scenario> = ScenarioParser.parse(json)

    override fun splitSentences(text: String): List<String> = EnSentenceSplitter.split(text)

    companion object {
        /** 把 example 中 word 的首次出现（不区分大小写）替换为 ____ */
        fun blankExample(word: String, example: String): String {
            if (word.isEmpty() || example.isEmpty()) return ""
            val idx = example.indexOf(word, ignoreCase = true)
            if (idx < 0) return example
            return example.substring(0, idx) + "____" + example.substring(idx + word.length)
        }
    }
}
