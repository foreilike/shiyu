package com.shiyu.app.ui

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shiyu.app.core.lang.LangId
import java.util.Locale

/**
 * 系统 TTS 朗读助手（发音表与逐句朗读用，不占用 AI Provider）。
 *
 * **为什么要记住 locale**：`TextToSpeech(context, listener)` 是异步的 —— 构造返回时引擎还没连上，
 * 此时赋值 `tts.language` 会被引擎初始化覆盖掉。调用方 invariably 在 onCreate 里
 * `Speaker(this)` 紧接着 `setLanguage(...)`，如果不缓存、等 [onInit] 再应用，
 * 朗读就会用系统默认语言（中文 ROM 上就是中文）去念英文/日文句子。
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingText: String? = null

    /** 期望的朗读语言；引擎就绪前先存着，就绪后再应用 */
    private var locale: Locale? = null

    /** 语音包缺失时的回调，供 UI 提示（可为空） */
    var onLanguageUnavailable: ((Locale) -> Unit)? = null

    /**
     * 本句朗读**实际发声失败**时回调（缺语音包、引擎离线合成失败等），供 UI 兜底提示。
     *
     * 为什么需要它：这类失败是**静默**的——`speak()` 返回 OK 但一个字都不出，UI 无从判断。
     * 典型场景：日语音色是「联网语音」，断网时既不报错也不出声。
     */
    var onSpeakFailed: ((Int) -> Unit)? = null

    /** 最近这一次朗读是否已被判定失败（每次 [speak] 前清零，供调用方延迟复查） */
    private var failed = false

    /** 最近这一次是否真的开过口（[speakNow] 置 true，[onStart] 回调再确认） */
    private var requested = false
    private var started = false

    /** 抑制开关：录音期间置 true，阻断一切朗读，从根上避免「外放被录进麦克风」的回环。 */
    private var suppressed = false

    /**
     * 单词朗读语速。系统默认 1.0，对学习者偏快 —— 音节糊在一起，跟不上也听不清。
     * 0.5 是实测听感调出来的值（0.7 仍偏快）。
     */
    private var rate = SLOW_RATE

    /** 当前朗读语言；例句语速要按语言分，所以得记住 */
    private var currentLang: LangId? = null

    fun setLanguage(langId: LangId) {
        currentLang = langId
        val target = if (langId == LangId.JA) Locale.JAPANESE else Locale.ENGLISH
        locale = target
        if (ready) applyLanguage(target)
    }

    fun setSuppressed(suppressed: Boolean) {
        this.suppressed = suppressed
        if (suppressed) tts?.stop()
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            applyRate(rate)
            attachProgressListener()
            // 引擎连上后才真正生效：把调用方早先设过的语言补上
            locale?.let { applyLanguage(it) }
            val text = pendingText
            pendingText = null
            if (text != null) speakNow(text)
        }
    }

    /** 语速：引擎就绪后才能设，设不上也不影响朗读（失败静默按默认走） */
    private fun applyRate(value: Float) {
        rate = value
        try {
            tts?.setSpeechRate(value)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun applyLanguage(target: Locale) {
        val result = try {
            tts?.setLanguage(target)
        } catch (e: Exception) {
            null
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 设备没装这门语言的语音包：保留系统默认，并通知 UI。
            // 不抛异常 —— 朗读失败不该影响主链路。
            onLanguageUnavailable?.invoke(target)
        }
    }

    fun speak(text: String) = speak(text, rate)

    /**
     * 整句例句朗读：语速**按语言分**（英语 0.3、日语 0.4，均为实测听感调出的值）。
     * 整句更长、连读更多，跟单词一个速度来不及听清。
     */
    fun speakExample(text: String) = speak(text, exampleRateFor(currentLang))

    /**
     * 指定语速朗读。
     *
     * 每次都显式 setSpeechRate：引擎的语速是「状态」而非「每句参数」，
     * 不重设的话上一句的语速会漏到下一句（读完慢速例句，单词也跟着变慢）。
     */
    fun speak(text: String, rate: Float) {
        if (text.isBlank()) return
        if (suppressed) return
        failed = false
        requested = false
        started = false
        try {
            tts?.setSpeechRate(rate)
        } catch (e: Exception) {
            // 忽略：设不上按引擎当前语速读，不该打断朗读
        }
        if (ready) speakNow(text) else pendingText = text
    }

    /**
     * 最近一次朗读是否「没出声」（供调用方延迟复查）。
     *
     * 两个判据取或：显式报错（缺语音包 / 联网合成失败），或者**要它读了却从没回调 onStart** ——
     * 有些引擎缺语音包时不报错、直接沉默，只有「没开始」能兜住。
     * 引擎还没就绪（pendingText）时不算失败，避免误报。
     */
    fun lastAttemptFailed(): Boolean = failed || (requested && !started)

    /** 挂 utterance 监听：缺语音包 / 联网合成失败时 `speak()` 仍返回 SUCCESS，只有这里能拿到。 */
    private fun attachProgressListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    started = true
                }
                override fun onDone(utteranceId: String?) {}
                override fun onStop(utteranceId: String?, interrupted: Boolean) {}
                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed = true
                    onSpeakFailed?.invoke(errorCode)
                }
                // API 26 上旧签名也会被调用，一并兜住
                override fun onError(utteranceId: String?) {
                    failed = true
                    onSpeakFailed?.invoke(-1)
                }
            })
        } catch (e: Exception) {
            // 忽略：拿不到失败回调不应影响朗读本身
        }
    }

    private fun speakNow(text: String) {
        val params: Bundle? = null
        requested = true
        val result = try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            failed = true
            onSpeakFailed?.invoke(result ?: TextToSpeech.ERROR)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        /** 单词朗读语速：0.5 = 系统默认的一半。默认 1.0 对初学者太快，0.7 仍偏快（实测反馈） */
        const val SLOW_RATE = 0.5f

        /** 例句语速——英语。整句更长、连读更多，比单词再慢一档才听得清 */
        const val EXAMPLE_RATE_EN = 0.3f

        /** 例句语速——日语。日语音节短、语速本身快，0.3 太拖，0.4 更自然（实测听感） */
        const val EXAMPLE_RATE_JA = 0.4f

        private const val UTTERANCE_ID = "shiyu-utterance"

        private fun exampleRateFor(langId: LangId?): Float =
            if (langId == LangId.JA) EXAMPLE_RATE_JA else EXAMPLE_RATE_EN
    }
}
