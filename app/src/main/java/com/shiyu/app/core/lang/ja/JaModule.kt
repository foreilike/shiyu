package com.shiyu.app.core.lang.ja

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.WordBook
import com.shiyu.app.data.model.Card
import com.shiyu.app.data.model.ChartEntry
import com.shiyu.app.data.model.Scenario
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 日语模块（§5.6.6 映射表）：
 * front = kanji（空则 kana）；frontSub = "JLPT " + jlpt；back = kana；
 * backSub = romaji；meaningZh = meaningZh；speakText = kana；不做例句挖空。
 */
class JaModule : LanguageModule {

    override val id: LangId = LangId.JA
    override val answerHint: String = "输入假名读法"
    override val replyTag: String = "JA"

    /** KanaNormalizer.normalize → 删除空白 → 删除标点（§5.7 日语侧） */
    override fun normalizeForCompare(s: String): String {
        val normalized = KanaNormalizer.normalize(s)
        val sb = StringBuilder()
        for (ch in normalized) {
            if (ch in WHITESPACE) continue
            if (ch in PUNCT) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    override fun assetNames(): Triple<String, String, String> =
        Triple("kana.json", "n5_vocab.json", "scenarios_ja.json")

    /** 日语考试作答：假名读音（back）或汉字写法（front，有汉字时）都算对。 */
    override fun examAnswers(card: Card): List<String> =
        listOf(card.back, card.front).filter { it.isNotBlank() }.distinct()

    // 日语词书：OpenJLPT N5–N1 共 8334 词，中文释义/例句译文由外部模型翻译后按 id 合并
    // （见 docs/ja-translation/）。旧的 138 词 n5_vocab.json 已被完整 N5(662 词) 取代。
    override fun books(): List<WordBook> = listOf(
        WordBook("n5", LangId.JA, "N5", "ja_n5.json"),
        WordBook("n4", LangId.JA, "N4", "ja_n4.json"),
        WordBook("n3", LangId.JA, "N3", "ja_n3.json"),
        WordBook("n2", LangId.JA, "N2", "ja_n2.json"),
        WordBook("n1", LangId.JA, "N1", "ja_n1.json")
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
            val hira = o.str("hiragana")
            val kata = o.str("katakana")
            if (hira.isEmpty() && kata.isEmpty()) continue
            val cell = if (hira.isNotEmpty()) hira else kata
            out.add(
                ChartEntry(
                    cell = cell,
                    sub = o.str("romaji"),
                    speak = cell,
                    alt = if (kata.isNotEmpty() && kata != cell) kata else "",
                    note = ""
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
            val kanji = o.str("kanji")
            val kana = o.str("kana")
            if (id.isEmpty() || kana.isEmpty()) continue
            out.add(
                Card(
                    id = id,
                    front = if (kanji.isNotEmpty()) kanji else kana,
                    frontSub = "JLPT " + o.str("jlpt"),
                    back = kana,
                    backSub = o.str("romaji"),
                    meaningZh = o.str("meaningZh"),
                    speakText = kana,
                    exampleBlank = "",
                    example = o.str("example"),
                    exampleZh = o.str("exampleZh")
                )
            )
        }
        return out
    }

    override fun buildScenarios(json: String): List<Scenario> = ScenarioParser.parse(json)

    /** 日语按 。！？ 切分，保留终止符 */
    override fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？') {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) result.add(s)
                sb.clear()
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    companion object {
        private val WHITESPACE = setOf(' ', '\u3000', '\t', '\n', '\r')
        private val PUNCT = setOf('。', '、', '，', '．', '.', '！', '？', '!', '?', ',')
    }
}
