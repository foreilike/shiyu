package com.langpartner.app.data.store

import com.langpartner.app.core.lang.LangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** StudyStore（§5.11）：持久化 / 向后兼容 / 自愈 / 双语隔离 */
class StudyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): StudyStore = StudyStore(File(tmp.root, "study_progress.json"))

    @Test
    fun missing_file_returns_empty() {
        val data = newStore().load()
        assertTrue(data.forLang("ja").cards.isEmpty())
        assertTrue(data.forLang("en").cards.isEmpty())
    }

    @Test
    fun round_trip_round_and_version() {
        val store = newStore()
        store.recordReview(LangId.JA, "v_001", com.langpartner.app.core.srs.Sm2Scheduler.review(
            com.langpartner.app.core.srs.CardState(), 4, 20450L), 20450L)
        val file = File(tmp.root, "study_progress.json")
        assertTrue(file.exists())
        val data = newStore().load()
        assertEquals(2, data.version)
        val p = data.forLang("ja").cards["v_001"]!!
        assertEquals(2.5, p.ef, 1e-9)
        assertEquals(1, p.reps)
        assertEquals(1L, p.intervalDays)
        assertEquals(20451L, p.dueEpochDay)
        assertEquals(20450L, p.lastEpochDay)
        assertTrue(data.forLang("en").cards.isEmpty())
        // 写盘原子化：不留 .tmp
        assertFalse(File(tmp.root, "study_progress.json.tmp").exists())
    }

    @Test
    fun version1_migrates_to_ja_without_loss() {
        val file = File(tmp.root, "study_progress.json")
        file.writeText(
            """
            {"version":1,"cards":{
              "v_001":{"ef":2.2,"reps":3,"intervalDays":15,"dueEpochDay":20450,"lastEpochDay":20435},
              "v_002":{"ef":2.5,"reps":0,"intervalDays":0,"dueEpochDay":0,"lastEpochDay":0}
            }}
            """.trimIndent()
        )
        val data = newStore().load()
        val ja = data.forLang("ja").cards
        assertEquals(2, ja.size)
        assertEquals(2.2, ja["v_001"]!!.ef, 1e-9)
        assertEquals(3, ja["v_001"]!!.reps)
        assertEquals(15L, ja["v_001"]!!.intervalDays)
        assertTrue(data.forLang("en").cards.isEmpty())
    }

    @Test
    fun version_missing_treated_as_v1() {
        val file = File(tmp.root, "study_progress.json")
        file.writeText("""{"cards":{"v_001":{"ef":2.0,"reps":1,"intervalDays":1,"dueEpochDay":1,"lastEpochDay":0}}}""")
        val data = newStore().load()
        assertEquals(2.0, data.forLang("ja").cards["v_001"]!!.ef, 1e-9)
    }

    @Test
    fun missing_fields_use_defaults() {
        val file = File(tmp.root, "study_progress.json")
        file.writeText("""{"version":2,"langs":{"ja":{"cards":{"v_001":{}}}}}""")
        val p = newStore().load().forLang("ja").cards["v_001"]!!
        assertEquals(2.5, p.ef, 1e-9)
        assertEquals(0, p.reps)
        assertEquals(0L, p.intervalDays)
        assertEquals(0L, p.dueEpochDay)
        assertEquals(0L, p.lastEpochDay)
    }

    @Test
    fun corrupt_json_quarantined_and_empty() {
        val file = File(tmp.root, "study_progress.json")
        file.writeText("{this is not valid json")
        val data = newStore().load()
        assertTrue(data.forLang("ja").cards.isEmpty())
        // 原文件被隔离为 .corrupt-*
        val quarantined = tmp.root.listFiles { f, name -> name.startsWith("study_progress.json.corrupt-") }!!
        assertEquals(1, quarantined.size)
        assertFalse(file.exists())
    }

    @Test
    fun missing_lang_key_does_not_affect_other() {
        val file = File(tmp.root, "study_progress.json")
        file.writeText(
            """{"version":2,"langs":{"ja":{"cards":{"v_001":{"ef":2.0,"reps":2,"intervalDays":3,"dueEpochDay":10,"lastEpochDay":5}}}}}"""
        )
        val data = newStore().load()
        assertTrue(data.forLang("ja").cards.isNotEmpty())
        assertTrue(data.forLang("en").cards.isEmpty())
    }

    @Test
    fun same_card_id_isolated_per_language() {
        val store = newStore()
        val sm2 = com.langpartner.app.core.srs.Sm2Scheduler
        store.recordReview(LangId.JA, "dup", sm2.review(com.langpartner.app.core.srs.CardState(), 5, 100L), 100L)
        store.recordReview(LangId.EN, "dup", sm2.review(com.langpartner.app.core.srs.CardState(), 2, 100L), 100L)
        val data = newStore().load()
        val ja = data.forLang("ja").cards["dup"]!!
        val en = data.forLang("en").cards["dup"]!!
        assertEquals(1, ja.reps)
        assertEquals(2.6, ja.ef, 1e-9)
        assertEquals(0, en.reps)
        assertEquals(2.18, en.ef, 1e-9)
    }

    @Test
    fun select_today_due_first_then_unlearned() {
        val store = newStore()
        val sm2 = com.langpartner.app.core.srs.Sm2Scheduler
        // 学过的卡：due 20450（到期）与 20490（未到期）
        store.recordReview(LangId.EN, "e_old", sm2.review(com.langpartner.app.core.srs.CardState(), 4, 20440L), 20440L)
        store.recordReview(LangId.EN, "e_future", sm2.review(com.langpartner.app.core.srs.CardState(), 4, 20460L), 20460L)
        val deckIds = (1..5).map { "e_new$it" }
        val selected = store.selectToday(LangId.EN, deckIds, todayEpochDay = 20450L, data = newStore().load())
        // due 的排最前（升序），未学过的补足
        assertEquals("e_old", selected.first())
        assertEquals(6, selected.size) // 1 due + 5 unlearned
        assertFalse(selected.contains("e_future"))
    }

    @Test
    fun select_today_limit_20() {
        val store = newStore()
        val deckIds = (1..40).map { "e_$it" }
        val selected = store.selectToday(LangId.EN, deckIds, 20450L)
        assertEquals(20, selected.size)
    }

    @Test
    fun select_today_sorted_by_due_day() {
        val store = newStore()
        val sm2 = com.langpartner.app.core.srs.Sm2Scheduler
        store.recordReview(LangId.JA, "v_b", sm2.review(com.langpartner.app.core.srs.CardState(), 4, 20430L), 20430L)
        store.recordReview(LangId.JA, "v_a", sm2.review(com.langpartner.app.core.srs.CardState(), 4, 20420L), 20420L)
        val selected = store.selectToday(LangId.JA, emptyList(), 20450L, data = newStore().load())
        assertEquals(listOf("v_a", "v_b"), selected)
    }

    @Test
    fun clear_removes_file() {
        val store = newStore()
        val sm2 = com.langpartner.app.core.srs.Sm2Scheduler
        store.recordReview(LangId.JA, "v_001", sm2.review(com.langpartner.app.core.srs.CardState(), 4, 1L), 1L)
        store.clear()
        assertTrue(newStore().load().forLang("ja").cards.isEmpty())
    }
}
