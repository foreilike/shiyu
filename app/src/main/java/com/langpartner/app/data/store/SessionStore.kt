package com.langpartner.app.data.store

import com.langpartner.app.core.lang.LangId
import com.langpartner.app.data.model.SessionRecord
import com.langpartner.app.data.model.SessionTurn
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

    fun createSession(lang: LangId, scenarioId: String): SessionRecord {
        val now = System.currentTimeMillis()
        val record = SessionRecord(
            sessionId = "s_$now",
            langId = lang.tag,
            scenarioId = scenarioId,
            startedAt = now,
            turns = emptyList()
        )
        save(record)
        return record
    }

    fun appendTurn(sessionId: String, role: String, text: String): SessionRecord? {
        val record = load(sessionId) ?: return null
        val updated = record.copy(turns = record.turns + SessionTurn(role, text))
        save(updated)
        return updated
    }

    fun load(sessionId: String): SessionRecord? {
        val file = sessionFile(sessionId)
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

    /** 累计对话轮数（按语言统计 user 发言数） */
    fun turnsCountByLang(lang: LangId): Int =
        allSessions()
            .filter { it.langId == lang.tag }
            .sumOf { record -> record.turns.count { it.role == "user" } }

    /** 清空会话记录（「我的」页清空数据时调用） */
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
        val turnsArr = JSONArray()
        for (turn in record.turns) {
            val o = JSONObject()
            o.put("role", turn.role)
            o.put("text", turn.text)
            turnsArr.put(o)
        }
        root.put("turns", turnsArr)
        val file = sessionFile(record.sessionId)
        val tmp = File(sessionsDir, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun quarantine(file: File) {
        try {
            val target = File(
                file.parentFile,
                file.name + ".corrupt-" + System.currentTimeMillis()
            )
            file.copyTo(target, overwrite = true)
            file.delete()
        } catch (e: Exception) {
            // 隔离失败也不得影响调用方
        }
    }
}
