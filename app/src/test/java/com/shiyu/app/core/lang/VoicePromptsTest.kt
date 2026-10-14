package com.shiyu.app.core.lang

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
 *
 * **2026-10-14 起这份提示词与语言无关**：说什么语言由模型按用户的发音自己判
 * （两轮真机实测 14 句，中英 6/6、日语 6/7），所以 [VoicePrompts.forFreeTalk] 不再收语言参数。
 */
class VoicePromptsTest {

    private val prompt = VoicePrompts.forFreeTalk()

    // ---------- 必须保留的底线 ----------

    @Test
    fun names_all_three_languages() {
        for (l in listOf("中文", "英语", "日语")) {
            assertTrue("提示词必须点名「$l」", prompt.contains(l))
        }
    }

    @Test
    fun keeps_the_follow_the_speakers_language_rule() {
        // 不再写「默认只用{目标语言}说话」—— 那正是分入口要去掉的东西
        assertFalse(prompt.contains("默认只用日语说话"))
        assertFalse(prompt.contains("默认只用英语说话"))
        assertTrue(prompt.contains("跟随对方这一句话用的语种"))
        assertTrue(prompt.contains("每一句都重新判断一次"))
        // 第一轮实测：「こんにちは、元気ですか」被转写成「可你喜欢」后它改用中文回。
        // 模型判语种必须走音频而不是转写文本，这句是针对那次失败补的。
        assertTrue(prompt.contains("只听他的发音"))
    }

    @Test
    fun keeps_the_speak_chinese_when_asked_rule() {
        // 这条是历史踩坑修出来的（学习者要翻译/解释时必须照做，
        // 否则模型会陷入反复反问「可以吗」）
        assertTrue(prompt.contains("明确要求你用中文"))
        assertTrue(prompt.contains("直接按他说的用中文回答"))
    }

    @Test
    fun forbids_reading_out_format_markers() {
        // 语音是把整段念出来的：[JA]/[ZH]/[FIX] 这类标记会被读成怪声
        assertTrue(prompt.contains("不要念出任何格式标记"))
        assertFalse("自由聊天提示词里不该出现三段式标签", prompt.contains("[JA]"))
        assertFalse(prompt.contains("[ZH]"))
        assertFalse(prompt.contains("[FIX]"))
    }

    @Test
    fun keeps_replies_short() {
        assertTrue(prompt.contains("每次只说 1~2 句"))
    }

    // ---------- 不许变成问卷 ----------

    @Test
    fun explicitly_forbids_asking_the_name_over_and_over() {
        assertTrue("必须点名「你叫什么名字」这类问题", prompt.contains("你叫什么名字"))
        assertTrue("必须说明那是问卷不是聊天", prompt.contains("那是问卷，不是聊天"))
    }

    @Test
    fun asks_for_varied_topics() {
        assertTrue(prompt.contains("话题跟着对方走"))
        assertTrue(prompt.contains("经常换"))
        assertTrue(prompt.contains("不要死守一个话题"))
    }

    @Test
    fun no_longer_steers_toward_scenario_goal_expressions() {
        // 老的场景版提示词里有这两句，正是"死板"的来源，必须彻底去掉
        assertFalse(prompt.contains("自然地创造机会让他说出来"))
        assertFalse(prompt.contains("这个场景可以引导对方说出这些表达"))
        assertFalse(prompt.contains("口语练习"))
    }

    // ---------- 提示语料（这块仍按语言分：卡片得显示某一种语言的例句）----------

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

    // ---------- 换语言：已从"客户端硬切"改为"提示词自动跟随" ----------

    @Test
    fun language_switching_is_prompt_driven_not_client_driven() {
        // 旧实现是 detectLanguageSwitch + withLanguageMode 重发 session.update 把提示改掉。
        // 自动跟随语种后那条路有害（说一句带「用中文」的话就把会话按死在中文），已删除。
        // 这里守住"提示词自己能承担换语言"这件事。
        assertTrue("说什么语言就用什么语言答", prompt.contains("跟随对方这一句话用的语种"))
        assertTrue("明确要求中文时照做", prompt.contains("明确要求你用中文"))
        assertFalse("不该再有把会话按死在中文的硬指令", prompt.contains("临时切换"))
    }
}
