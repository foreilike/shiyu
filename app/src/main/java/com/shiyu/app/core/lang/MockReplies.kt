package com.shiyu.app.core.lang

/**
 * Mock 降级用的预置文案。收口在 core/lang（语言分支只允许出现在这里），
 * ai/ 层只拿最终字符串，不感知语言。
 */
object MockReplies {

    /** MockChatProvider 的预置回复：含主标签 / [ZH] / [FIX] 三段，正文带 (mock) 字样 */
    fun chat(lang: LangId): String = when (lang) {
        LangId.JA -> "[JA] こんにちは！(mock) 一緒に日本語を練習しましょう。\n" +
            "[ZH] 你好！(mock) 我们一起练习日语吧。\n" +
            "[FIX] 无"
        LangId.EN -> "[EN] Hello! (mock) Let's practice English together.\n" +
            "[ZH] 你好！(mock) 我们一起练习英语吧。\n" +
            "[FIX] 无"
    }

    /** MockSttProvider 的预置转写文本 */
    fun stt(lang: LangId): String = when (lang) {
        LangId.JA -> "これはモックの音声認識です"
        LangId.EN -> "This is a mock transcription."
    }
}
