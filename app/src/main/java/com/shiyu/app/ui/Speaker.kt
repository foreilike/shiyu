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
     * 该语言**只有联网音色**时的回调。
     *
     * 实测踩过：英语的默认音色是 `en-us-x-iog-lstm-**embedded**`（本地合成，随时能响），
     * 而日语默认给的是 `ja-jp-x-jab-**server**`（**要联网去 Google 取**）。
     * 网络不通时它**不报错、不回调**，就是一声不响 —— 用户看到的现象是「日语卡片点喇叭没声音」。
     * 所以这里要主动告诉 UI。
     */
    var onNetworkVoiceOnly: ((Locale) -> Unit)? = null

    /** 合成失败（引擎 onError）时的回调，参数为引擎错误码 */
    var onSpeechError: ((Int) -> Unit)? = null

    /** 抑制开关：录音期间置 true，阻断一切朗读，从根上避免「外放被录进麦克风」的回环。 */
    private var suppressed = false

    /**
     * 单词朗读语速。系统默认 1.0，对学习者偏快 —— 音节糊在一起，跟不上也听不清。
     * 0.5 是实测听感调出来的值（0.7 仍偏快）。
     */
    private var rate = SLOW_RATE

    /** 当前朗读语言；例句语速要按语言分，所以得记住 */
    private var currentLang: LangId? = null

    /**
     * 三个回调统一从**主线程**发出。
     *
     * 必须这么做：`onSpeechError` 是 TTS 引擎从 **Binder 线程**回调上来的，
     * 调用方在里面碰 UI（比如弹 Toast）会抛
     * `Can't toast on a thread that has not called Looper.prepare()` ——
     * 而且那个异常被系统吞掉，表现是「提示根本没出现」，比崩溃还难查（真机上踩过）。
     * 在源头收口，所有调用方就不必各自记住这件事。
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun postToMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

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
            // 合成失败要能看见：默认的静默失败会让人以为「这个按钮坏了」
            try {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit

                    @Deprecated("引擎仍会调旧签名", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        postToMain { onSpeechError?.invoke(-1) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        postToMain { onSpeechError?.invoke(errorCode) }
                    }
                })
            } catch (e: Exception) {
                // 忽略：拿不到错误回调不影响朗读本身
            }
            applyRate(rate)
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
            postToMain { onLanguageUnavailable?.invoke(target) }
            return
        }
        preferEmbeddedVoice(target)
    }

    /**
     * **优先挑本地（非联网）音色。**
     *
     * 同一个语言在系统里可能同时有联网音色和本地音色，默认给的不一定是本地那个 ——
     * 实测日语默认就给到联网音色（见 [onNetworkVoiceOnly] 的注释），网络不通时一声不响。
     * 本地音色合成是瞬时的、离线可用，学习场景明显更合适，所以只要存在就换过去。
     *
     * 一个本地音色都没有时，保留系统默认（联网音色）并回调通知 UI ——
     * 能响就响，响不了也要让人知道是「语音包/网络」的问题，而不是以为按钮坏了。
     */
    private fun preferEmbeddedVoice(target: Locale) {
        val voices = try {
            tts?.voices
        } catch (e: Exception) {
            null
        } ?: return
        val sameLang = voices.filter { it.locale.language == target.language }
        if (sameLang.isEmpty()) return
        val embedded = sameLang.firstOrNull { !it.isNetworkConnectionRequired }
        if (embedded == null) {
            postToMain { onNetworkVoiceOnly?.invoke(target) }
            return
        }
        try {
            tts?.voice = embedded
        } catch (e: Exception) {
            // 换不过去就用默认的，不该影响已有的朗读能力
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
        try {
            tts?.setSpeechRate(rate)
        } catch (e: Exception) {
            // 忽略：设不上按引擎当前语速读，不该打断朗读
        }
        if (ready) speakNow(text) else pendingText = text
    }

    private fun speakNow(text: String) {
        val params: Bundle? = null
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance-${text.hashCode()}")
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

        private fun exampleRateFor(langId: LangId?): Float =
            if (langId == LangId.JA) EXAMPLE_RATE_JA else EXAMPLE_RATE_EN
    }
}
