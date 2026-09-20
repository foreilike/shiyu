package com.langpartner.app.data.store

import com.langpartner.app.core.lang.LangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** SessionStore（§5.11）：会话记录持久化 / 兼容 / 计数 */
class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): SessionStore = SessionStore(File(tmp.root, "sessions"))

    @Test
    fun create_and_reload() {
        val store = newStore()
        val session = store.createSession(LangId.EN, "coffee")
        assertTrue(session.sessionId.startsWith("s_"))
        val file = File(tmp.root, "sessions/${session.sessionId}.json")
        assertTrue(file.exists())
        val loaded = store.load(session.sessionId)
        assertNotNull(loaded)
        assertEquals("en", loaded!!.langId)
        assertEquals("coffee", loaded.scenarioId)
        assertTrue(loaded.turns.isEmpty())
    }

    @Test
    fun append_turn_persists_immediately() {
        val store = newStore()
        val session = store.createSession(LangId.JA, "self_intro")
        store.appendTurn(session.sessionId, "assistant", "こんにちは")
        store.appendTurn(session.sessionId, "user", "こんにちは！")
        val loaded = store.load(session.sessionId)!!
        assertEquals(2, loaded.turns.size)
        assertEquals("assistant", loaded.turns[0].role)
        assertEquals("こんにちは", loaded.turns[0].text)
        assertEquals("user", loaded.turns[1].role)
    }

    @Test
    fun turns_count_by_lang_counts_user_turns() {
        val store = newStore()
        val ja = store.createSession(LangId.JA, "s1")
        store.appendTurn(ja.sessionId, "assistant", "hi")
        store.appendTurn(ja.sessionId, "user", "u1")
        store.appendTurn(ja.sessionId, "user", "u2")
        val en = store.createSession(LangId.EN, "s2")
        store.appendTurn(en.sessionId, "user", "u3")
        assertEquals(2, store.turnsCountByLang(LangId.JA))
        assertEquals(1, store.turnsCountByLang(LangId.EN))
    }

    @Test
    fun missing_session_returns_null() {
        assertNull(newStore().load("s_nonexistent"))
    }

    @Test
    fun missing_lang_id_defaults_to_ja() {
        val store = newStore()
        val session = store.createSession(LangId.JA, "s")
        val file = File(tmp.root, "sessions/${session.sessionId}.json")
        file.writeText(
            """{"version":2,"sessionId":"${session.sessionId}","scenarioId":"s","startedAt":1,"turns":[]}"""
        )
        val loaded = store.load(session.sessionId)!!
        assertEquals("ja", loaded.langId)
    }

    @Test
    fun corrupt_session_quarantined_and_skipped() {
        val store = newStore()
        val session = store.createSession(LangId.EN, "s")
        val file = File(tmp.root, "sessions/${session.sessionId}.json")
        file.writeText("{broken json")
        assertNull(store.load(session.sessionId))
        assertEquals(0, store.allSessions().size)
        val quarantined = File(tmp.root, "sessions").listFiles { _, name ->
            name.startsWith("${session.sessionId}.json.corrupt-")
        }!!
        assertEquals(1, quarantined.size)
    }

    @Test
    fun all_sessions_sorted_by_started_at() {
        val store = newStore()
        val a = store.createSession(LangId.JA, "s")
        val b = store.createSession(LangId.EN, "s")
        val all = store.allSessions()
        assertEquals(2, all.size)
        assertTrue(all[0].startedAt <= all[1].startedAt)
        assertEquals(a.sessionId, all[0].sessionId)
        assertEquals(b.sessionId, all[1].sessionId)
    }

    @Test
    fun clear_removes_sessions_dir() {
        val store = newStore()
        store.createSession(LangId.JA, "s")
        store.clear()
        assertEquals(0, store.allSessions().size)
        assertFalse(File(tmp.root, "sessions").exists())
    }
}
