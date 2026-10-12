package com.shiyu.app.core.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时语音提示词（§17.22.1）。
 *
 * **背景**：用户反馈"实时对话过于死板、每次都问名字"。
 * 根因是两处叠加 —— ① 实时语音入口写死取 `scenarios.first()`（= 自我介绍场景）；
 * ② 提示词里写着「自然地创造机会让他说出这些表达」，而该场景目标第一条就是
 * `My name is ...`。于是每次开聊都被问名字。
 *
 * 这里把修法钉住：**实时语音走自由聊天，提示词必须显式禁止"问卷式"提问**。
 */
class VoicePromptsTest {

    private val ja = VoicePrompts.forFreeTalk(LangId.JA)
    private val en = VoicePrompts.forFreeTalk(LangId.EN)

    // ---------- 必须保留的底线 ----------

    @Test
    fun states_the_target_language() {
        assertTrue(ja.contains("日语"))
        assertTrue(en.contains("英语"))
    }

    @Test
    fun keeps_the_use_the_target_language_rule() {
        assertTrue(ja.contains("默认只用日语说话"))
        assertTrue(en.contains("默认只用英语说话"))
    }

    @Test
    fun keeps_the_speak_chinese_when_asked_rule() {
        // 这条是历史踩坑修出来的（学习者要翻译/解释时必须照做，
        // 否则模型会陷入反复反问「可以吗」）
        assertTrue(ja.contains("明确要求你用中文"))
        assertTrue(ja.contains("直接按他说的用中文回答"))
    }

    @Test
    fun forbids_reading_out_format_markers() {
        // 语音是把整段念出来的：[JA]/[ZH]/[FIX] 这类标记会被读成怪声
        assertTrue(ja.contains("不要念出任何格式标记"))
        assertFalse("自由聊天提示词里不该出现三段式标签", ja.contains("[JA]"))
        assertFalse(ja.contains("[ZH]"))
        assertFalse(ja.contains("[FIX]"))
    }

    @Test
    fun keeps_replies_short() {
        assertTrue(ja.contains("每次只说 1~2 句"))
    }

    // ---------- 这次的修法：不许变成问卷 ----------

    @Test
    fun explicitly_forbids_asking_the_name_over_and_over() {
        for ((name, p) in listOf("日语" to ja, "英语" to en)) {
            assertTrue("$name：必须点名「你叫什么名字」这类问题", p.contains("你叫什么名字"))
            assertTrue("$name：必须说明那是问卷不是聊天", p.contains("那是问卷，不是聊天"))
        }
    }

    @Test
    fun asks_for_varied_topics() {
        assertTrue(ja.contains("话题跟着对方走"))
        assertTrue(ja.contains("经常换"))
        assertTrue(ja.contains("不要死守一个话题"))
    }

    @Test
    fun no_longer_steers_toward_scenario_goal_expressions() {
        // 老的场景版提示词里有这两句，正是"死板"的来源，必须彻底去掉
        assertFalse(ja.contains("自然地创造机会让他说出来"))
        assertFalse(ja.contains("这个场景可以引导对方说出这些表达"))
        assertFalse(ja.contains("口语练习"))
    }

    // ---------- 提示语料 ----------

    @Test
    fun free_talk_hints_are_usable_for_both_languages() {
        for (lang in LangId.entries) {
            val hints = VoicePrompts.freeTalkHints(lang)
            assertTrue("$lang 应有提示语", hints.size >= 4)
            for ((target, zh) in hints) {
                assertTrue("目标语言提示不能为空", target.isNotBlank())
                assertTrue("中文释义不能为空", zh.isNotBlank())
                assertFalse("目标和释义不该是同一串", target == zh)
            }
        }
    }

    @Test
    fun japanese_hints_are_japanese_and_english_hints_are_english() {
        for ((target, _) in VoicePrompts.freeTalkHints(LangId.JA)) {
            assertTrue("日语提示里不该出现纯中文句子：$target",
                target.any { it.code in 0x3040..0x30FF })
        }
        for ((target, _) in VoicePrompts.freeTalkHints(LangId.EN)) {
            assertTrue("英语提示应含拉丁字母：$target", target.any { it.isLetter() && it.code < 128 })
        }
    }

    // ---------- 换语言（既有能力，别被改坏）----------

    @Test
    fun target_mode_returns_base_prompt_unchanged() {
        assertEquals(ja, VoicePrompts.withLanguageMode(ja, VoicePrompts.LanguageMode.TARGET))
    }

    @Test
    fun chinese_mode_appends_a_switch_directive() {
        val out = VoicePrompts.withLanguageMode(ja, VoicePrompts.LanguageMode.CHINESE)
        assertTrue(out.startsWith(ja))
        assertTrue(out.contains("临时切换"))
        assertTrue(out.contains("简体中文"))
    }

    @Test
    fun detects_language_switch_requests() {
        assertEquals(VoicePrompts.LanguageMode.CHINESE,
            VoicePrompts.detectLanguageSwitch("请用中文说"))
        assertEquals(VoicePrompts.LanguageMode.TARGET,
            VoicePrompts.detectLanguageSwitch("用日语"))
        assertEquals(null, VoicePrompts.detectLanguageSwitch("今天天气不错"))
        assertEquals(null, VoicePrompts.detectLanguageSwitch(""))
    }
}
