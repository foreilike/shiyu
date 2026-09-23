package com.shiyu.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TTS 磁盘缓存（§17.17 Tier 1）。
 *
 * 锁三件事：
 * 1. 命中/未命中的正确性 —— 命中回文件、空文件不算命中、空字节不落盘；
 * 2. key 三要素 —— model/voice/text 任一变化都是新 key（换音色不能串读旧音频）；
 * 3. LRU 淘汰 —— 超上限从最旧删起，刚 touch 过的活得久。
 */
class TtsDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(maxBytes: Long = Long.MAX_VALUE) =
        TtsDiskCache(File(tmp.root, "tts_cache"), maxBytes)

    // ---------- 基本读写 ----------

    @Test
    fun get_missing_returns_null() {
        assertNull(cache().get("nope"))
    }

    @Test
    fun put_then_get_returns_file_with_content() {
        val c = cache()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val f = c.put("k1", bytes)
        assertNotNull(f)
        val hit = c.get("k1")
        assertNotNull(hit)
        assertTrue(hit!!.readBytes().contentEquals(bytes))
    }

    @Test
    fun put_empty_bytes_stores_nothing() {
        val c = cache()
        assertNull(c.put("k1", ByteArray(0)))
        assertNull(c.get("k1"))
        assertEquals(0L, c.sizeBytes())
    }

    @Test
    fun put_overwrites_existing_key() {
        val c = cache()
        c.put("k1", byteArrayOf(1))
        c.put("k1", byteArrayOf(9, 9, 9))
        assertTrue(c.get("k1")!!.readBytes().contentEquals(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun zero_byte_file_is_not_a_hit() {
        // 模拟「写了一半被杀」留下的 0 字节文件：不该当成命中去播
        val c = cache()
        c.put("k1", byteArrayOf(1, 2))
        c.get("k1")!!.writeBytes(ByteArray(0))
        assertNull(c.get("k1"))
    }

    // ---------- key 三要素 ----------

    @Test
    fun key_is_deterministic() {
        val c = cache()
        assertEquals(c.key("こんにちは", "m", "v"), c.key("こんにちは", "m", "v"))
    }

    @Test
    fun key_differs_by_text_model_or_voice() {
        val c = cache()
        val base = c.key("text", "model", "voice")
        assertNotEquals(base, c.key("text2", "model", "voice"))
        assertNotEquals(base, c.key("text", "model2", "voice"))
        assertNotEquals(base, c.key("text", "model", "voice2"))
    }

    // ---------- LRU 淘汰 ----------

    @Test
    fun evict_removes_oldest_when_over_cap() {
        val c = cache(maxBytes = 10L)
        c.put("a", byteArrayOf(1, 2, 3, 4, 5)) // 5B
        c.put("b", byteArrayOf(1, 2, 3, 4, 5)) // 5B → 共 10B，未超
        // 把 a 摆成最旧
        File(tmp.root, "tts_cache/a.mp3").setLastModified(1000L)
        File(tmp.root, "tts_cache/b.mp3").setLastModified(2000L)
        c.put("c", byteArrayOf(1, 2, 3, 4, 5)) // 15B > 10B → 淘汰 a
        assertNull(c.get("a"))
        assertNotNull(c.get("b"))
        assertNotNull(c.get("c"))
    }

    @Test
    fun touched_file_survives_eviction() {
        val c = cache(maxBytes = 10L)
        c.put("a", byteArrayOf(1, 2, 3, 4, 5))
        c.put("b", byteArrayOf(1, 2, 3, 4, 5))
        File(tmp.root, "tts_cache/a.mp3").setLastModified(1000L)
        File(tmp.root, "tts_cache/b.mp3").setLastModified(2000L)
        // 刚用过 a（get 会 touch），随后超限 —— 被淘汰的应该是没被碰过的 b
        c.get("a")
        c.put("c", byteArrayOf(1, 2, 3, 4, 5))
        assertNotNull("刚用过的不该被删", c.get("a"))
        assertNull(c.get("b"))
    }

    @Test
    fun eviction_keeps_newest_when_all_old() {
        val c = cache(maxBytes = 6L)
        c.put("a", byteArrayOf(1, 2, 3, 4, 5))
        File(tmp.root, "tts_cache/a.mp3").setLastModified(1000L)
        c.put("b", byteArrayOf(1, 2, 3, 4, 5)) // 超限，a 最旧被删
        assertNull(c.get("a"))
        assertNotNull(c.get("b"))
        assertTrue(c.sizeBytes() <= 6L)
    }

    // ---------- tmp 不残留 ----------

    @Test
    fun put_leaves_no_tmp_behind() {
        val c = cache()
        c.put("k1", byteArrayOf(1, 2, 3))
        val leftovers = File(tmp.root, "tts_cache").listFiles()!!
            .filter { it.name.endsWith(".tmp") }
        assertTrue("不该留下 tmp: $leftovers", leftovers.isEmpty())
    }
}
