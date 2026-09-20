package com.shiyu.app.data.store

import com.shiyu.app.data.model.DayStat
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 每日学习活动存储（filesDir/daily_stats.json，version 1）。
 *
 * 只记「拾语」首页与我的页要展示的四项真实数据，不跨语言区分 —— 连续天数与学习时长
 * 是账号级概念，切语言不应该把打卡断掉。
 *
 * 自愈与落盘规则与 [StudyStore] 保持一致：
 * - 文件不存在 → 空结构；
 * - JSON 坏 → 隔离为 <原名>.corrupt-<epochMillis> 后返回空结构；
 * - 写盘先 .tmp 再 rename。
 */
class DailyStatsStore(private val statsFile: File) {

    data class StatsData(
        var version: Int = 1,
        val days: MutableMap<Long, DayStat> = mutableMapOf()
    )

    fun load(): StatsData {
        if (!statsFile.exists()) return StatsData()
        val text = try {
            statsFile.readText()
        } catch (e: Exception) {
            return StatsData()
        }
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            quarantine()
            return StatsData()
        }
        val data = StatsData()
        val days = root.optJSONObject("days") ?: return data
        for (key in days.keys()) {
            val epochDay = key.toLongOrNull() ?: continue
            val o = days.optJSONObject(key) ?: continue
            data.days[epochDay] = DayStat(
                epochDay = epochDay,
                studySeconds = o.optLong("studySeconds", 0L).coerceAtLeast(0L),
                voiceSeconds = o.optLong("voiceSeconds", 0L).coerceAtLeast(0L),
                reviewedCards = o.optInt("reviewedCards", 0).coerceAtLeast(0),
                chatTurns = o.optInt("chatTurns", 0).coerceAtLeast(0)
            )
        }
        return data
    }

    fun save(data: StatsData) {
        val root = JSONObject()
        root.put("version", 1)
        val days = JSONObject()
        for ((epochDay, stat) in data.days) {
            val o = JSONObject()
            o.put("studySeconds", stat.studySeconds)
            o.put("voiceSeconds", stat.voiceSeconds)
            o.put("reviewedCards", stat.reviewedCards)
            o.put("chatTurns", stat.chatTurns)
            days.put(epochDay.toString(), o)
        }
        root.put("days", days)
        atomicWrite(root.toString())
    }

    /** 读某一天（没有记录则返回全零，不写盘） */
    fun dayOf(epochDay: Long, data: StatsData = load()): DayStat =
        data.days[epochDay] ?: DayStat(epochDay = epochDay)

    /** 累加一天的各项数值；任何一项为 0 都可以传，立即落盘 */
    fun record(
        epochDay: Long,
        studySeconds: Long = 0L,
        voiceSeconds: Long = 0L,
        reviewedCards: Int = 0,
        chatTurns: Int = 0
    ) {
        if (studySeconds <= 0L && voiceSeconds <= 0L && reviewedCards <= 0 && chatTurns <= 0) return
        val data = load()
        val old = data.days[epochDay] ?: DayStat(epochDay = epochDay)
        data.days[epochDay] = old.copy(
            epochDay = epochDay,
            studySeconds = old.studySeconds + studySeconds.coerceAtLeast(0L),
            voiceSeconds = old.voiceSeconds + voiceSeconds.coerceAtLeast(0L),
            reviewedCards = old.reviewedCards + reviewedCards.coerceAtLeast(0),
            chatTurns = old.chatTurns + chatTurns.coerceAtLeast(0)
        )
        save(data)
    }

    /**
     * 连续学习天数。
     *
     * 口径：今天有活动就从今天往前数；今天还没开始学，则从昨天往前数（当天过完之前
     * 不判断断签，与主流打卡产品一致）。两天都空 → 0。
     */
    fun streak(todayEpochDay: Long, data: StatsData = load()): Int {
        val anchor = when {
            data.days[todayEpochDay]?.active == true -> todayEpochDay
            data.days[todayEpochDay - 1]?.active == true -> todayEpochDay - 1
            else -> return 0
        }
        var count = 0
        var cursor = anchor
        while (data.days[cursor]?.active == true) {
            count++
            cursor--
        }
        return count
    }

    /**
     * 本周（周一 → 周日）7 天的记录，缺的天补全零。
     * 返回长度恒为 7，下标 0 = 周一，便于直接喂柱状图。
     */
    fun currentWeek(todayEpochDay: Long, data: StatsData = load()): List<DayStat> {
        val monday = LocalDate.ofEpochDay(todayEpochDay)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toEpochDay()
        return (0L until 7L).map { offset ->
            val day = monday + offset
            data.days[day] ?: DayStat(epochDay = day)
        }
    }

    /** 累计投入秒数（我的页「累计小时」） */
    fun totalSeconds(data: StatsData = load()): Long =
        data.days.values.sumOf { it.totalSeconds }

    /** 清空全部每日记录（「清空全部数据」时调用） */
    fun clear() {
        try {
            if (statsFile.exists()) statsFile.delete()
        } catch (e: Exception) {
            // 忽略：清空失败不得影响其它两个 store
        }
    }

    private fun atomicWrite(text: String) {
        val parent = statsFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(statsFile.parentFile, statsFile.name + ".tmp")
        tmp.writeText(text)
        if (statsFile.exists()) {
            statsFile.delete()
        }
        if (!tmp.renameTo(statsFile)) {
            statsFile.writeText(text)
            tmp.delete()
        }
    }

    private fun quarantine() {
        try {
            val target = File(
                statsFile.parentFile,
                statsFile.name + ".corrupt-" + System.currentTimeMillis()
            )
            statsFile.copyTo(target, overwrite = true)
            statsFile.delete()
        } catch (e: Exception) {
            // 隔离失败也不得影响调用方
        }
    }
}
