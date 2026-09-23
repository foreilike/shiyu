package com.shiyu.app.ui

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import com.shiyu.app.AppGraph
import com.shiyu.app.ai.AiResult
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.store.TtsDiskCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /**
     * 云端走不通、系统 TTS 也悄悄失败时的兜底提示（由页面接 Toast）。
     *
     * 这个场景是**静默**的：断网时云端请求秒失败，回落到系统 TTS，而本机日语音色是
     * 「联网语音」—— PHONE 侧既不报错也不出声，用户只看到点了没反应（真机实测确认）。
     */
    var onNeedNetwork: (() -> Unit)? = null

    /**
     * 预取并发闸门：快速翻卡时每张卡都起一个协程，不设上限会同时打出十几个合成请求
     * （容易撞 429、也拖慢用户真正在等的那个请求）。
     * 只管预取，**点播（play）不受限** —— 用户按下喇叭的等待时间优先。
     */
    private val prefetchGate = Semaphore(PREFETCH_CONCURRENCY)

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
        scope.launch(Dispatchers.IO) {
            prefetchGate.withPermit { obtainAudio(lang, text) }
        }
    }

    /**
     * 整表预取（发音表用）：每条各抢一个 [prefetchGate] 许可，并发上限仍是 2 ——
     * 几十个条目一起打过去会撞限流（429），但全串行太慢（46 格实测 53 秒）。
     * 期间用户点某格会走 [obtainAudio] 的 in-flight 去重，不会重复请求。
     */
    fun prefetchAll(scope: CoroutineScope, lang: LangId, texts: List<String>) {
        if (!useCloud(lang)) return
        val distinct = texts.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (t in distinct) {
                // 已在缓存里的会被 obtainAudio 秒过；页面已销毁时子协程随父取消
                launch { prefetchGate.withPermit { obtainAudio(lang, t) } }
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
            if (file != null) {
                playFile(file, fallback)
                return@launch
            }
            // 云端没拿到音频：先回落系统 TTS，再复查它是不是也静默失败了。
            // 失败常常晚于 speak() 返回（utterance 回调），所以得等一小会儿再判定。
            fallback()
            delay(FALLBACK_CHECK_DELAY_MS)
            if (speaker.lastAttemptFailed()) onNeedNetwork?.invoke()
        }
    }

    /**
     * 取音频：缓存命中直接回；未命中（或已有同 key 请求在飞）合成一次并落缓存。
     * 任何失败都返回 null —— 调用方回落系统 [Speaker]，绝不静音。
     */
    private suspend fun obtainAudio(lang: LangId, text: String): File? {
        val config = AppGraph.settingsRepository.configFor(lang).tts
        val key = cache.key(text, config.model, config.voice)
        cache.get(key)?.let {
            android.util.Log.i(TAG, "HIT ${key.take(8)} 0ms ${preview(text)}")
            return it
        }

        val mine = CompletableFuture<File?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            android.util.Log.i(TAG, "WAIT ${key.take(8)} ${preview(text)}（已有同 key 请求在飞，不重复发）")
            return awaitExisting(existing)
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val result = AppGraph.providerRegistry.factory(lang).tts().synthesize(text, null)
            val ms = SystemClock.elapsedRealtime() - startedAt
            val file = when (result) {
                is AiResult.Ok -> cache.put(key, result.value)
                is AiResult.Err -> null // 失败不缓存：下次再试（可能是暂时性网络问题）
            }
            if (file != null) {
                android.util.Log.i(TAG, "MISS ${key.take(8)} ${ms}ms ${file.length()}B ${preview(text)}")
            } else {
                android.util.Log.w(TAG, "FAIL ${key.take(8)} ${ms}ms ${preview(text)}（回落系统 TTS）")
            }
            mine.complete(file)
            return file
        } catch (e: CancellationException) {
            // 页面销毁 / 预取被取消：别吞掉取消信号，交回协程框架
            mine.complete(null)
            throw e
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

    /** 等同一个 in-flight 请求的结果（阻塞 IO 线程直到首个请求完成；阻塞期间取消不生效，最多等到超时）。 */
    private fun awaitExisting(future: CompletableFuture<File?>): File? =
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

    /** 日志里只给前 12 个字，够认是哪一条、又不刷屏（文本含多行例句时尤其有用）。 */
    private fun preview(text: String) = "'${text.replace('\n', ' ').take(12)}'"

    private companion object {
        const val TAG = "ShiyuTts"

        /** 缓存上限。MP3 一条约 20–80KB，64MB ≈ 上千条，够全书高频词常驻。 */
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024

        /** 预取最多同时 2 个请求：够覆盖「当前卡 + 下一张」的预热节奏，又不至于打爆限流。 */
        const val PREFETCH_CONCURRENCY = 2

        /** 回落之后复查「系统 TTS 到底出没出声」的等待窗口（utterance 错误回调会晚到）。 */
        const val FALLBACK_CHECK_DELAY_MS = 1500L
    }
}
