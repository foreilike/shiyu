package com.shiyu.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import com.shiyu.app.core.lang.LangId
import java.util.Locale

/**
 * 系统 TTS 朗读助手（图表页与逐句朗读用，不占用 AI Provider）。
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingText: String? = null

    /** 抑制开关：录音期间置 true，阻断一切朗读，从根上避免「外放被录进麦克风」的回环。 */
    private var suppressed = false

    fun setLanguage(langId: LangId) {
        val locale = if (langId == LangId.JA) Locale.JAPANESE else Locale.ENGLISH
        tts?.language = locale
    }

    fun setSuppressed(suppressed: Boolean) {
        this.suppressed = suppressed
        if (suppressed) tts?.stop()
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val text = pendingText
            pendingText = null
            if (text != null) speakNow(text)
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (suppressed) return
        if (ready) speakNow(text) else pendingText = text
    }

    private fun speakNow(text: String) {
        val params: android.os.Bundle? = null
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance-${text.hashCode()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
