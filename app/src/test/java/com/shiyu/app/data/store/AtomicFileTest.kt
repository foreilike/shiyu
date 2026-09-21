package com.shiyu.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 原子写与「写一半被打断」的恢复。
 *
 * 这组测试锁的是一个真实踩过的坑：原先各 store 的落盘是「先 delete 目标再 rename」，
 * 那个 delete 是多余的，而且**打开了"目标文件不存在"的窗口** ——
 * 进程恰好在 delete 之后、rename 之前被杀，下次启动就找不到文件，
 * load 走「不存在 → 空结构」分支，用户全部进度静默清零，而 tmp 里明明有完整数据。
 */
class AtomicFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String = "data.json") = File(tmp.root, name)

    @Test
    fun write_creates_file_with_content() {
        val target = file()
        AtomicFile.write(target, """{"a":1}""")
        assertEquals("""{"a":1}""", target.readText())
    }

    @Test
    fun write_overwrites_existing_without_a_gap() {
        val target = file()
        AtomicFile.write(target, "first")
        AtomicFile.write(target, "second")
        assertEquals("second", target.readText())
    }

    @Test
    fun write_leaves_no_tmp_behind() {
        val target = file()
        AtomicFile.write(target, "x")
        val leftovers = tmp.root.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("不该留下 tmp: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun write_creates_parent_directories() {
        val target = File(tmp.root, "sub/dir/data.json")
        AtomicFile.write(target, "nested")
        assertEquals("nested", target.readText())
    }

    // ---------- 恢复：写一半被打断的残局 ----------

    @Test
    fun recover_promotes_tmp_when_target_is_missing() {
        // 模拟「写完 tmp、还没 rename 就被杀」：目标不在、tmp 在
        val target = file()
        File(tmp.root, "data.json.tmp").writeText("""{"recovered":true}""")
        AtomicFile.recoverIfNeeded(target)
        assertTrue(target.exists())
        assertEquals("""{"recovered":true}""", target.readText())
    }

    @Test
    fun recover_does_nothing_when_target_exists() {
        val target = file()
        target.writeText("good")
        File(tmp.root, "data.json.tmp").writeText("stale")
        AtomicFile.recoverIfNeeded(target)
        assertEquals("good", target.readText())
    }

    @Test
    fun recover_ignores_empty_tmp() {
        val target = file()
        File(tmp.root, "data.json.tmp").writeText("")
        AtomicFile.recoverIfNeeded(target)
        assertFalse("空 tmp 不该被认领", target.exists())
    }

    @Test
    fun recover_is_noop_when_nothing_exists() {
        val target = file()
        AtomicFile.recoverIfNeeded(target)
        assertFalse(target.exists())
    }

    // ---------- 字节版（加密存储用） ----------

    @Test
    fun write_bytes_round_trip() {
        val target = file("secure.bin")
        val payload = byteArrayOf(1, -1, 0, 127, -128)
        AtomicFile.writeBytes(target, payload)
        assertTrue(target.readBytes().contentEquals(payload))
    }

    // ---------- 隔离 ----------

    @Test
    fun quarantine_preserves_original_under_corrupt_name() {
        val target = file()
        target.writeText("bad json")
        AtomicFile.quarantine(target)
        assertFalse("原文件应被移走", target.exists())
        val corrupt = tmp.root.listFiles()!!.filter { it.name.startsWith("data.json.corrupt-") }
        assertEquals(1, corrupt.size)
        assertEquals("bad json", corrupt.first().readText())
    }

    @Test
    fun quarantine_on_missing_file_is_noop() {
        val target = file()
        AtomicFile.quarantine(target)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }
}
