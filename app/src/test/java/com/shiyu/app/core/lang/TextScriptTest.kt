package com.shiyu.app.core.lang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这句是不是已经是中文」的判定（§自动跟随语种）。
 *
 * **为什么有这个类**：自动跟随语种后模型经常直接用中文回答，而字幕下面那行中文小字是
 * 另起一次翻译贴上去的 —— 把中文再"翻译"成中文，气泡里就会出现同一句话印两遍。
 * 真机实测连着五个气泡都是重复的，这组用例就是守住那个修法。
 */
class TextScriptTest {

    @Test
    fun plain_chinese_is_chinese() {
        assertTrue(TextScript.isChineseOnly("今天天气确实不错，我这边也挺好的。你打算出去走走吗？"))
        assertTrue(TextScript.isChineseOnly("哦还行，周末都干了些啥？"))
        assertTrue(TextScript.isChineseOnly("会一点，你想聊日语吗？"))
    }

    @Test
    fun japanese_is_not_chinese() {
        assertFalse(TextScript.isChineseOnly("どういたしまして。何か他に質問ある？"))
        assertFalse(TextScript.isChineseOnly("はい、わかりました。日本語で話しましょう。"))
        assertFalse(TextScript.isChineseOnly("ありがとう"))
        // 片假名
        assertFalse(TextScript.isChineseOnly("コーヒーが好きです"))
    }

    @Test
    fun english_is_not_chinese() {
        assertFalse(TextScript.isChineseOnly("Yeah, I like music a lot."))
        // 中英混排也要翻（英文部分需要译文）
        assertFalse(TextScript.isChineseOnly("我喜欢 jazz 这种音乐"))
    }

    @Test
    fun text_without_any_han_is_not_treated_as_chinese() {
        // 纯标点/数字/空串都没有可译的东西，但也不该被当成中文走"跳过"那条分支之外的逻辑
        assertFalse(TextScript.isChineseOnly(""))
        assertFalse(TextScript.isChineseOnly("？？？"))
        assertFalse(TextScript.isChineseOnly("123"))
    }

    @Test
    fun punctuation_and_digits_do_not_break_the_chinese_verdict() {
        assertTrue(TextScript.isChineseOnly("好的，我明白了！"))
        assertTrue(TextScript.isChineseOnly("我今天跑了 5 公里。"))
    }

    // ---------- hasKana：日语字幕修正的两道闸 ----------

    @Test
    fun detects_kana_in_japanese() {
        assertTrue(TextScript.hasKana("どういたしまして。何か他に質問ある？"))
        assertTrue(TextScript.hasKana("おはようございます"))
        assertTrue(TextScript.hasKana("コーヒー"))   // 片假名
        assertTrue(TextScript.hasKana("今日はいい天気ですね。"))
    }

    @Test
    fun chinese_and_english_have_no_kana() {
        // 模型用中文/英语回答时不该触发日语字幕修正
        assertFalse(TextScript.hasKana("今天天气确实不错，你打算出去走走吗？"))
        assertFalse(TextScript.hasKana("Yeah, I like music a lot."))
        // 实时通道把日语转成的中文谐音同样没有假名 —— 正因为这样，它才需要被修
        assertFalse(TextScript.hasKana("是密码谁？"))
        assertFalse(TextScript.hasKana("阿里嘎多"))
    }
}
