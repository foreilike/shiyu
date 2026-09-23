package com.shiyu.app.ui

import android.content.Context
import android.media.MediaPlayer
import com.shiyu.app.AppGraph
import com.shiyu.app.ai.AiResult
import com.shiyu.app.core.lang.LangId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单词 / 例句朗读的「云优先」播放器（§17）。
 *
 * **为什么要它**：系统 TTS（[Speaker]）念日语时，没装离线语音包的设备会让引擎（多为 Google
 * 文字转语音）走**联网合成**，请求要发到 Google 服务器。国内移动网络（5G/4G）到不了，
 * 于是「点朗读没声音」，且 `setLanguage` 对这种「联网才可用」的语言仍返回可用、不报错，
 * 静默失败。魅族 22 上离线日语包又下不下来（阉割 GMS 卡死、无 root 塞不进）。
 *
 * 解法：日语改走云端 TTS（stepfun `/audio/speech`，返回 MP3），本地播放不依赖 Google。
 * 云端没配或失败时回落系统 [Speaker]，绝不静音。英语系统语音包普遍自带、离线可用，
 * 不必花 API，仍走 Speaker。
 */
class WordTtsPlayer(private val context: Context, private val speaker: Speaker) {

    private var player: MediaPlayer? = null

    /** 该语言是否应走云端 TTS：日语且云端 TTS 已配置可用时才走（英语留给系统离线引擎）。 */
    private fun useCloud(lang: LangId): Boolean =
        lang == LangId.JA && AppGraph.providerRegistry.isTtsEnabled(lang)

    /** 单词朗读。 */
    fun speakWord(scope: CoroutineScope, lang: LangId, text: String) =
        play(scope, lang, text) { speaker.speak(text) }

    /** 例句朗读（回落时按语言分语速，见 [Speaker.speakExample]）。 */
    fun speakExample(scope: CoroutineScope, lang: LangId, text: String) =
        play(scope, lang, text) { speaker.speakExample(text) }

    private fun play(scope: CoroutineScope, lang: LangId, text: String, fallback: () -> Unit) {
        if (text.isBlank()) return
        if (!useCloud(lang)) {
            fallback()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppGraph.providerRegistry.factory(lang).tts().synthesize(text, null)
            }
            when (result) {
                is AiResult.Ok -> playMp3(result.value, fallback)
                is AiResult.Err -> fallback() // 云端失败别静音：回落系统 TTS（离线设备至少还能出声）
            }
        }
    }

    private fun playMp3(bytes: ByteArray, onError: () -> Unit) {
        if (bytes.isEmpty()) {
            onError()
            return
        }
        releasePlayer()
        val file = File(context.cacheDir, "wordtts_${bytes.size}_${bytes.contentHashCode()}.mp3")
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            onError()
            return
        }
        // 构造/设源/prepare 都可能抛（服务端返回截断或非音频字节）—— 全兜住，坏音频不该崩。
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener { releasePlayer(); file.delete() }
                setOnErrorListener { _, _, _ -> releasePlayer(); file.delete(); true }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            releasePlayer()
            file.delete()
            onError()
        }
    }

    /** 释放播放器（页面销毁 / 重新播放前调用）。名字避开 [MediaPlayer.release] 以免 apply 块里解析错。 */
    fun releasePlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // 忽略：未 prepare 就 stop 会抛
        }
        player?.release()
        player = null
    }
}
