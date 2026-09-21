package com.shiyu.app.data.store

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.srs.DailyQuota
import com.shiyu.app.core.srs.ReviewOutcome
import com.shiyu.app.data.model.CardProgress
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * 学习进度存储（filesDir/study_progress.json，version 2 按语言分层）。
 *
 * 向后兼容与自愈（§5.11）：
 * - 文件不存在 → 空结构，不报错
 * - JSON 解析失败 → 重命名为 <原名>.corrupt-<epochMillis>，返回空结构
 * - version 缺失或为 1 → 顶层 cards 整体迁移为 langs.ja.cards，en 为空
 * - 单条记录缺字段 → 默认值（ef=2.5 / reps=0 / intervalDays=0 / dueEpochDay=0 / lastEpochDay=0）
 * - langs 缺某语言 → 该语言为空 Map
 * - 写盘原子化：先写 .tmp 再重命名
 */
class StudyStore(private val progressFile: File) {

    data class LangProgress(val cards: MutableMap<String, CardProgress> = mutableMapOf())

    data class StudyData(
        var version: Int = 2,
        val langs: MutableMap<String, LangProgress> = mutableMapOf(
            LangId.JA.tag to LangProgress(),
            LangId.EN.tag to LangProgress()
        )
    ) {
        fun forLang(tag: String): LangProgress =
            langs.getOrPut(tag) { LangProgress() }
    }

    fun load(): StudyData {
        if (!progressFile.exists()) return StudyData()
        val text = try {
            progressFile.readText()
        } catch (e: Exception) {
            return StudyData()
        }
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            quarantine()
            return StudyData()
        }
        val data = StudyData()
        val version = root.optInt("version", 0)
        if (version >= 2) {
            val langs = root.optJSONObject("langs")
            if (langs != null) {
                for (tag in listOf(LangId.JA.tag, LangId.EN.tag)) {
                    val langObj = langs.optJSONObject(tag)
                    if (langObj != null) {
                        data.forLang(tag).cards.putAll(parseCards(langObj))
                    }
                }
            }
        } else {
            // version 1 或缺失：顶层 cards → langs.ja.cards（不丢旧数据）
            data.forLang(LangId.JA.tag).cards.putAll(parseCards(root))
        }
        return data
    }

    private fun parseCards(container: JSONObject): Map<String, CardProgress> {
        val cards = container.optJSONObject("cards") ?: return emptyMap()
        val out = mutableMapOf<String, CardProgress>()
        for (cardId in cards.keys()) {
            val o = cards.optJSONObject(cardId) ?: continue
            out[cardId] = CardProgress(
                ef = o.optDouble("ef", 2.5),
                reps = o.optInt("reps", 0),
                intervalDays = o.optLong("intervalDays", 0L),
                dueEpochDay = o.optLong("dueEpochDay", 0L),
                lastEpochDay = o.optLong("lastEpochDay", 0L)
            )
        }
        return out
    }

    fun save(data: StudyData) {
        val root = JSONObject()
        root.put("version", 2)
        val langs = JSONObject()
        for ((tag, langProgress) in data.langs) {
            val langObj = JSONObject()
            val cards = JSONObject()
            for ((cardId, p) in langProgress.cards) {
                val o = JSONObject()
                o.put("ef", p.ef)
                o.put("reps", p.reps)
                o.put("intervalDays", p.intervalDays)
                o.put("dueEpochDay", p.dueEpochDay)
                o.put("lastEpochDay", p.lastEpochDay)
                cards.put(cardId, o)
            }
            langObj.put("cards", cards)
            langs.put(tag, langObj)
        }
        root.put("langs", langs)
        atomicWrite(root.toString())
    }

    private fun atomicWrite(text: String) {
        val parent = progressFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(progressFile.parentFile, progressFile.name + ".tmp")
        tmp.writeText(text)
        if (progressFile.exists()) {
            progressFile.delete()
        }
        if (!tmp.renameTo(progressFile)) {
            // 重命名失败时退化为直接写
            progressFile.writeText(text)
            tmp.delete()
        }
    }

    private fun quarantine() {
        try {
            val target = File(
                progressFile.parentFile,
                progressFile.name + ".corrupt-" + System.currentTimeMillis()
            )
            progressFile.copyTo(target, overwrite = true)
            progressFile.delete()
        } catch (e: Exception) {
            // 隔离失败也不得影响调用方
        }
    }

    /** 当前语言的进度 Map（只读视图） */
    fun progressFor(lang: LangId, data: StudyData = load()): Map<String, CardProgress> =
        data.forLang(lang.tag).cards.toMap()

    /** 清空学习进度（「我的」页清空数据时调用） */
    fun clear() {
        try {
            if (progressFile.exists()) progressFile.delete()
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 评分后立刻落盘 */
    fun recordReview(
        lang: LangId,
        cardId: String,
        outcome: ReviewOutcome,
        todayEpochDay: Long,
        data: StudyData = load()
    ) {
        data.forLang(lang.tag).cards[cardId] = CardProgress(
            ef = outcome.state.easeFactor,
            reps = outcome.state.reps,
            intervalDays = outcome.state.intervalDays,
            dueEpochDay = outcome.dueEpochDay,
            lastEpochDay = todayEpochDay
        )
        save(data)
    }

    /**
     * 今日任务选卡：dueEpochDay <= today 的按 dueEpochDay 升序优先；
     * 不足 limit 用未学过的卡补足。
     */
    fun selectToday(
        lang: LangId,
        deckIds: List<String>,
        todayEpochDay: Long,
        limit: Int = DailyQuota.CARDS_PER_DAY,
        data: StudyData = load()
    ): List<String> {
        val progress = data.forLang(lang.tag).cards
        val due = progress.entries
            .filter { it.value.dueEpochDay <= todayEpochDay }
            .sortedWith(compareBy({ it.value.dueEpochDay }, { it.key }))
            .map { it.key }
        val learned = progress.keys
        val result = mutableListOf<String>()
        for (id in due) {
            if (result.size >= limit) break
            result.add(id)
        }
        if (result.size < limit) {
            for (id in deckIds) {
                if (result.size >= limit) break
                if (id !in learned) result.add(id)
            }
        }
        return result
    }
}
