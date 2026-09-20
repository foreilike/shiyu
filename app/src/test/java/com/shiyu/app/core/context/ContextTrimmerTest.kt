package com.shiyu.app.core.context

import com.shiyu.app.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ContextTrimmer（§5.9） */
class ContextTrimmerTest {

    private fun history(n: Int): List<ChatMessage> =
        (0 until n).map { ChatMessage(if (it % 2 == 0) "user" else "assistant", "m$it") }

    @Test
    fun system_always_first() {
        val trimmed = ContextTrimmer.trim("SYS", history(10))
        assertEquals(11, trimmed.size)
        assertEquals(ChatMessage("system", "SYS"), trimmed[0])
    }

    @Test
    fun size_24_unchanged() {
        val input = history(24)
        val trimmed = ContextTrimmer.trim("SYS", input)
        assertEquals(25, trimmed.size)
        assertEquals(input, trimmed.drop(1))
    }

    @Test
    fun size_25_leaves_23() {
        val trimmed = ContextTrimmer.trim("SYS", history(25))
        // 25 > 24，从最前成对移除 2 → 剩 23
        assertEquals(24, trimmed.size) // 含 system
        assertEquals(23, trimmed.drop(1).size)
    }

    @Test
    fun size_26_leaves_24() {
        val trimmed = ContextTrimmer.trim("SYS", history(26))
        assertEquals(25, trimmed.size)
        assertEquals(24, trimmed.drop(1).size)
    }

    @Test
    fun removes_pairs_from_front() {
        val input = history(26)
        val trimmed = ContextTrimmer.trim("SYS", input)
        // 移除的是最前面两条 m0/m1
        val kept = trimmed.drop(1)
        assertEquals("m2", kept.first().content)
        assertEquals("m25", kept.last().content)
    }

    @Test
    fun filters_unknown_roles() {
        val input = listOf(
            ChatMessage("system", "should be dropped"),
            ChatMessage("user", "u1"),
            ChatMessage("tool", "dropped"),
            ChatMessage("assistant", "a1")
        )
        val trimmed = ContextTrimmer.trim("SYS", input)
        assertEquals(3, trimmed.size)
        assertEquals("user", trimmed[1].role)
        assertEquals("assistant", trimmed[2].role)
    }

    @Test
    fun input_not_mutated() {
        val input = history(30)
        val snapshot = input.toList()
        ContextTrimmer.trim("SYS", input)
        assertEquals(snapshot, input)
    }

    @Test
    fun empty_history_returns_system_only() {
        val trimmed = ContextTrimmer.trim("SYS", emptyList())
        assertEquals(1, trimmed.size)
        assertTrue(trimmed[0].role == "system")
    }

    @Test
    fun large_history_converges_to_24() {
        val trimmed = ContextTrimmer.trim("SYS", history(200))
        assertEquals(24, trimmed.drop(1).size)
        // 保留的是最后 24 条
        assertEquals("m176", trimmed[1].content)
    }

    @Test
    fun max_history_constant() {
        assertEquals(24, ContextTrimmer.MAX_HISTORY)
    }
}
