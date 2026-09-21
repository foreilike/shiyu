package com.shiyu.app.data.store

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.MODE_CHAT
import com.shiyu.app.data.model.SessionRecord
import com.shiyu.app.data.model.SessionTurn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * 会话记录存储（filesDir/sessions/<sessionId>.json）。
 * 每次进入场景新建 sessionId（s_<epochMillis>），每轮对话追加 turn 并立即落盘。
 * 兼容：langId 缺失视为 "ja"；坏 JSON 隔离为 .corrupt-* 并跳过。
 */
class SessionStore(private val sessionsDir: File) {

    fun createSession(
        lang: LangId,
        scenarioId: String,
        mode: String = MODE_CHAT
    ): SessionRecord {
        val now = System.currentTimeMillis()
        val record = SessionRecord(
            sessionId = "s_$now",
            langId = lang.tag,
            scenarioId = scenarioId,
            startedAt = now,
            mode = mode,
            turns = emptyList()
        )
        save(record)
        return record
    }

    /**
     * 实时语音用：一次性写入整轮对话与时长。
     *
     * 实时语音没有「逐轮落盘」的必要（中途崩了也没法续），
     * 所以攒到结束再写一条，比每轮 IO 更划算。
     */
    @Synchronized
    fun saveRealtimeSession(
        lang: LangId,
        scenarioId: String,
        turns: List<SessionTurn>,
        durationSeconds: Long
    ): SessionRecord? {
        if (turns.none { it.role == "user" }) return null // 一句话没说就别留记录
        val now = System.currentTimeMillis()
        val record = SessionRecord(
            sessionId = "s_$now",
            langId = lang.tag,
            scenarioId = scenarioId,
            startedAt = now,
            mode = com.shiyu.app.data.model.MODE_REALTIME,
            durationSeconds = durationSeconds,
            turns = turns
        )
        save(record)
        return record
    }

    /** 全部会话，按开始时间倒序（历史对话列表用） */
    fun allSessionsNewestFirst(): List<SessionRecord> =
        allSessions().sortedByDescending { it.startedAt }

    @Synchronized
    fun appendTurn(sessionId: String, role: String, text: String): SessionRecord? {
        val record = load(sessionId) ?: return null
        val updated = record.copy(turns = record.turns + SessionTurn(role, text))
        save(updated)
        return updated
    }

    fun load(sessionId: String): SessionRecord? = loadFrom(sessionFile(sessionId))

    private fun trashDir(): File = File(sessionsDir, "trash")

    private fun loadFrom(file: File): SessionRecord? {
        AtomicFile.recoverIfNeeded(file)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return null
        }
        return try {
            parse(JSONObject(text))
        } catch (e: JSONException) {
            quarantine(file)
            null
        }
    }

    fun allSessions(): List<SessionRecord> {
        if (!sessionsDir.exists()) return emptyList()
        val out = mutableListOf<SessionRecord>()
        val files = sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        for (file in files) {
            val sessionId = file.nameWithoutExtension
            load(sessionId)?.let { out.add(it) }
        }
        return out.sortedBy { it.startedAt }
    }

    /**
     * 某天某语言的用户发言轮数。
     *
     * 首页原先用 `DayStat.chatTurns`（只增不减），而「我的」页用会话文件实时重算 ——
     * 两套真相：**删掉一条会话后，首页的数字会永久高于「我的」页**。
     * 现在两边都从会话文件派生，口径一致。
     */
    fun userTurnsOn(lang: LangId, epochDay: Long): Int =
        allSessions()
            .filter { it.langId == lang.tag }
            .filter {
                java.time.Instant.ofEpochMilli(it.startedAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toEpochDay() == epochDay
            }
            .sumOf { record -> record.turns.count { it.role == "user" } }

    /** 累计对话轮数（按语言统计 user 发言数） */
    fun turnsCountByLang(lang: LangId): Int =
        allSessions()
            .filter { it.langId == lang.tag }
            .sumOf { record -> record.turns.count { it.role == "user" } }

    /**
     * 删除单条会话记录 —— **不真删，移进回收站**。
     *
     * 对话是攒出来的东西，误删一次就没了太可惜。回收站是 `sessions/trash/` 子目录：
     * `allSessions` 只扫 sessionsDir 下的**文件**（子目录 isFile 为 false），
     * 所以放进去就自然从列表里消失了，不用额外加标记位。
     */
    @Synchronized
    fun trash(sessionId: String): Boolean {
        val file = sessionFile(sessionId)
        if (!file.exists()) return false
        val dir = trashDir()
        if (!dir.exists()) dir.mkdirs()
        return try {
            file.renameTo(File(dir, file.name))
        } catch (e: Exception) {
            false
        }
    }

    /** 回收站里的会话，按删除时间（文件修改时间）倒序 */
    fun trashedSessions(): List<SessionRecord> {
        val files = trashDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { loadFrom(it) }
    }

    fun trashedCount(): Int =
        trashDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0

    /** 从回收站恢复 */
    @Synchronized
    fun restore(sessionId: String): Boolean {
        val file = File(trashDir(), "$sessionId.json")
        if (!file.exists()) return false
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        return try {
            file.renameTo(sessionFile(sessionId))
        } catch (e: Exception) {
            false
        }
    }

    /** 从回收站彻底删除单条 */
    @Synchronized
    fun purge(sessionId: String): Boolean {
        val file = File(trashDir(), "$sessionId.json")
        return try {
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    /** 清空回收站，返回删掉几条 */
    @Synchronized
    fun emptyTrash(): Int {
        val files = trashDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return 0
        var n = 0
        for (f in files) {
            try { if (f.delete()) n++ } catch (e: Exception) { /* 跳过 */ }
        }
        return n
    }

    /**
     * 给会话起个名字（留空 = 恢复成场景名）。回收站里的也能改名，
     * 所以两个目录都试一下。
     */
    @Synchronized
    fun rename(sessionId: String, title: String): Boolean {
        val trimmed = title.trim().take(MAX_TITLE_LENGTH)
        val record = load(sessionId)
            ?: loadFrom(File(trashDir(), "$sessionId.json"))
            ?: return false
        save(record.copy(title = trimmed))
        return true
    }

    /** 清空会话记录（「我的」页清空数据时调用）——连同回收站一起 */
    @Synchronized
    fun clear() {
        try {
            if (sessionsDir.exists()) sessionsDir.deleteRecursively()
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun sessionFile(sessionId: String): File = File(sessionsDir, "$sessionId.json")

    private fun parse(root: JSONObject): SessionRecord {
        val turnsArr = root.optJSONArray("turns") ?: JSONArray()
        val turns = mutableListOf<SessionTurn>()
        for (i in 0 until turnsArr.length()) {
            val o = turnsArr.optJSONObject(i) ?: continue
            val role = o.optString("role", "user")
            val text = if (o.isNull("text")) "" else o.optString("text", "")
            turns.add(SessionTurn(role, text))
        }
        return SessionRecord(
            version = root.optInt("version", 2),
            sessionId = root.optString("sessionId"),
            langId = if (root.isNull("langId") || root.optString("langId").isEmpty()) {
                LangId.JA.tag
            } else {
                root.optString("langId")
            },
            scenarioId = root.optString("scenarioId"),
            startedAt = root.optLong("startedAt", 0L),
            mode = root.optString("mode", MODE_CHAT).ifEmpty { MODE_CHAT },
            durationSeconds = root.optLong("durationSeconds", 0L),
            title = root.optString("title", ""),
            turns = turns
        )
    }

    private fun save(record: SessionRecord) {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        val root = JSONObject()
        root.put("version", 2)
        root.put("sessionId", record.sessionId)
        root.put("langId", record.langId)
        root.put("scenarioId", record.scenarioId)
        root.put("startedAt", record.startedAt)
        root.put("mode", record.mode)
        root.put("durationSeconds", record.durationSeconds)
        root.put("title", record.title)
        val turnsArr = JSONArray()
        for (turn in record.turns) {
            val o = JSONObject()
            o.put("role", turn.role)
            o.put("text", turn.text)
            turnsArr.put(o)
        }
        root.put("turns", turnsArr)
        // 就地更新：记录若在回收站里，别因为一次改名把它挪出来。
        // 两个目录都查一遍，在哪个目录就在哪个目录重写。
        val inTrash = File(trashDir(), "${record.sessionId}.json")
        val dir = if (!sessionFile(record.sessionId).exists() && inTrash.exists()) trashDir()
        else sessionsDir
        AtomicFile.write(File(dir, "${record.sessionId}.json"), root.toString())
    }

    private fun quarantine(file: File) = AtomicFile.quarantine(file)

    companion object {
        /** 自定义名称长度上限 */
        const val MAX_TITLE_LENGTH = 24
    }
}
