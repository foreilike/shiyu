package com.shiyu.app.ui

import android.content.Context
import android.media.MediaPlayer
import com.shiyu.app.AppGraph
import com.shiyu.app.ai.AiResult
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.store.TtsDiskCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
 *
 * **磁盘缓存（§17.17 Tier 1）**：云端音频按 `sha256(model|voice|text)` 落
 * `cacheDir/tts_cache`，命中直接播 —— 重复朗读秒开且省 API；总量上限 64MB 超限 LRU 淘汰。
 * **预取**：复习页拿到卡（[prefetch]）与发音表整表（[prefetchAll]）后台预热，
 * 等用户点喇叭时音频已就绪。
 *
 * **in-flight 去重**：预取进行中用户就点了喇叭（或快速连点），同一 key 只发一次网络请求，
 * 后来者等第一个结果 —— 否则预取反而把延迟翻倍。
 */
class WordTtsPlayer(context: Context, private val speaker: Speaker) {

    private var player: MediaPlayer? = null

    private val cache = TtsDiskCache(File(context.cacheDir, "tts_cache"), MAX_CACHE_BYTES)

    /** key → 正在合成中的请求；同 key 的后来者 await 同一个结果。 */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File?>>()

    /** 该语言是否应走云端 TTS：日语且云端 TTS 已配置可用时才走（英语留给系统离线引擎）。 */
    private fun useCloud(lang: LangId): Boolean =
        lang == LangId.JA && AppGraph.providerRegistry.isTtsEnabled(lang)

    /** 单词朗读。 */
    fun speakWord(scope: CoroutineScope, lang: LangId, text: String) =
        play(scope, lang, text) { speaker.speak(text) }

    /** 例句朗读（回落时按语言分语速，见 [Speaker.speakExample]）。 */
    fun speakExample(scope: CoroutineScope, lang: LangId, text: String) =
        play(scope, lang, text) { speaker.speakExample(text) }

    /** 后台预取：只合成落缓存、不播放。复习页 showNext 后调，等用户点喇叭时已就绪。 */
    fun prefetch(scope: CoroutineScope, lang: LangId, text: String) {
        if (text.isBlank() || !useCloud(lang)) return
        scope.launch(Dispatchers.IO) { obtainAudio(lang, text) }
    }

    /**
     * 整表预取（发音表用）：**顺序**逐条合成 —— 几十个条目并发打过去容易撞限流（429），
     * 顺序慢一点但稳；期间用户点某格会走 [obtainAudio] 的 in-flight 去重，不会重复请求。
     */
    fun prefetchAll(scope: CoroutineScope, lang: LangId, texts: List<String>) {
        if (!useCloud(lang)) return
        val distinct = texts.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (t in distinct) {
                obtainAudio(lang, t)
            }
        }
    }

    private fun play(scope: CoroutineScope, lang: LangId, text: String, fallback: () -> Unit) {
        if (text.isBlank()) return
        if (!useCloud(lang)) {
            fallback()
            return
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { obtainAudio(lang, text) }
            if (file != null) playFile(file, fallback) else fallback()
        }
    }

    /**
     * 取音频：缓存命中直接回；未命中（或已有同 key 请求在飞）合成一次并落缓存。
     * 任何失败都返回 null —— 调用方回落系统 [Speaker]，绝不静音。
     */
    private suspend fun obtainAudio(lang: LangId, text: String): File? {
        val config = AppGraph.settingsRepository.configFor(lang).tts
        val key = cache.key(text, config.model, config.voice)
        cache.get(key)?.let { return it }

        val mine = CompletableFuture<File?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return suspendCoroutineFor(existing)
        try {
            val result = AppGraph.providerRegistry.factory(lang).tts().synthesize(text, null)
            val file = when (result) {
                is AiResult.Ok -> cache.put(key, result.value)
                is AiResult.Err -> null // 失败不缓存：下次再试（可能是暂时性网络问题）
            }
            mine.complete(file)
            return file
        } catch (e: Exception) {
            mine.complete(null)
            return null
        } finally {
            // complete() 对已完成的 future 是 no-op：正常路径已 complete，这里兜住协程被取消的情况，
            // 否则等同一个 in-flight 的后来者会永远挂起。
            mine.complete(null)
            inFlight.remove(key, mine)
        }
    }

    /** 把 [CompletableFuture] 的结果桥接成挂起（阻塞 IO 线程直到首个请求完成）。 */
    private fun suspendCoroutineFor(future: CompletableFuture<File?>): File? =
        try {
            future.get()
        } catch (e: Exception) {
            null
        }

    /** 播放缓存/合成产物。文件由缓存管理，播完不删。 */
    private fun playFile(file: File, onError: () -> Unit) {
        releasePlayer()
        // 构造/设源/prepare 都可能抛（音频被系统清理或坏字节）—— 全兜住，坏音频不该崩。
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener { releasePlayer() }
                setOnErrorListener { _, _, _ -> releasePlayer(); true }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            releasePlayer()
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

    private companion object {
        /** 缓存上限。MP3 一条约 20–80KB，64MB ≈ 上千条，够全书高频词常驻。 */
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
