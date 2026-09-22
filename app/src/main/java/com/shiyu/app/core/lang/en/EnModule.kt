package com.shiyu.app.core.lang.en

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.WordBook
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

    override fun books(): List<WordBook> = listOf(
        WordBook("basic", LangId.EN, "基础", "en_words.json"),
        WordBook("cet4", LangId.EN, "CET4", "en_cet4.json"),
        WordBook("cet6", LangId.EN, "CET6", "en_cet6.json"),
        WordBook("kaoyan", LangId.EN, "考研", "en_kaoyan.json"),
        WordBook("toefl", LangId.EN, "托福", "en_toefl.json"),
        WordBook("ielts", LangId.EN, "雅思", "en_ielts.json"),
        WordBook("gre", LangId.EN, "GRE", "en_gre.json")
    )

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
                    // 与日语统一的字段约定（跬步模式）：front=外语词、back=读音、meaningZh=中文释义。
                    // 原先英语是反的（front 放释义、back 放单词），只有打字作答时讲得通；
                    // 改成「看词回忆释义」的自评模式后必须与日语一致。
                    front = word,
                    frontSub = listOf(o.str("level"), o.str("pos"))
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    back = o.str("ipa"),
                    backSub = "",
                    meaningZh = meaningZh,
                    speakText = word,
                    exampleBlank = blankExample(word, example),
                    example = example,
                    exampleZh = o.str("exampleZh")
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
