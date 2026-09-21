package com.shiyu.app.audio

import com.shiyu.app.core.audio.VadPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时语音音频控制器（app.audio：依赖 android.media，属平台层）。
 *
 * 上行（采集）：AudioRecord 16kHz/mono/PCM16 → 20ms 帧能量 VAD 门控
 *   —— 连续静音超过 [SILENCE_GATE_MS] 暂停 append（静音段推流照计费，必须掐断），
 *   人声恢复立即续传。通过门控的帧回调 [onAudioFrame]。
 * 下行（播放）：AudioTrack 播放 pcm16 delta；[interrupt] 用于 barge-in
 *   （服务端 speech_started → 立即停播，避免录到自己的声音）。
 */
class RealtimeAudioController(
    private val onAudioFrame: (ByteArray) -> Unit,
    private val onSpeechStateChanged: (Boolean) -> Unit = {},
    private val onAmplitude: (Int) -> Unit = {}
) {

    companion object {
        /**
         * **24000 Hz —— 这个值踩过坑，别改回 16000。**
         *
         * StepFun 的实时接口对 `pcm16` 约定的是 24kHz/单声道/小端（官方文档的「实时对话开发指南」
         * 里 PCM 推流示例写死了 mono + 24000）。注意别被模型内部结构误导：
         * Step-Audio 的 S3Tokenizer 确实在 **16kHz** 上工作，但那是**模型内部**的事；
         * 走 WebSocket 送进 `input_audio_buffer.append` 的必须是 24kHz。
         *
         * 按 16kHz 推流时服务端不报错、也不回任何事件 —— 它拿到的是半速音频，
         * VAD 永远判定为「没人说话」，表现为「连上了、麦克风在采集、但 AI 一声不吭」。
         */
        const val SAMPLE_RATE = 24000

        /** 20ms 一帧；官方建议 20–30ms 一包，太小会碎、太大增加延迟 */
        private const val FRAME_SAMPLES = 480 // 20ms @24k
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        /** stopCapture 等采集线程退出最多等这么久 */
        private const val CAPTURE_JOIN_TIMEOUT_MS = 500L
        private const val SILENCE_GATE_MS = 800
        private const val SILENCE_GATE_FRAMES = SILENCE_GATE_MS / 20
    }

    // 采集线程会读它、stopCapture 会写它 —— 必须 volatile，否则可能读到过期的非 null 值
    @Volatile private var recorder: android.media.AudioRecord? = null
    private var captureThread: Thread? = null
    private val capturing = AtomicBoolean(false)

    @Volatile private var player: android.media.AudioTrack? = null
    private val playing = AtomicBoolean(false)

    /** 只保护 player / playing 的读写；**不要用它包住 track.write**（会阻塞打断） */
    private val playerLock = Any()

    @Volatile var speechActive: Boolean = false
        private set

    /** 打断后到新一轮响应开始之间，丢弃下行的残留音频 */
    @Volatile private var droppingAudio = false

    /**
     * 「按住说话」模式：只有 [pushing] 为真时才把真实麦克风数据往上推，
     * 松手时推**等长静音**而不是干脆不推。
     *
     * 为什么松手要推静音：服务端 VAD 靠"听到声音停了"来断句（speech_stopped）。
     * 如果松手就不推流，服务端收不到"结束"的信号，只能干等超时，回复会明显变慢。
     * 推静音能让它照常断句，同时保证旁人说话进不了服务端。
     */
    @Volatile var pushToTalkMode = false
    @Volatile var pushing = false

    /** 端上音频效果器（回声消除 / 降噪），best-effort，设备不支持时为 null */
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null

    // ---------- 采集 ----------

    /**
     * 开始采集。调用方必须已经拿到 RECORD_AUDIO 授权
     * （VoiceChatActivity 在进入前申请，拒绝则直接 finish）。
     * 这里显式标注契约，让 lint 不再把它误报为漏检权限。
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (capturing.get()) return
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val bufSize = maxOf(minBuf, FRAME_BYTES * 8)
        val rec = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            return
        }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        recorder = rec
        attachAudioEffects(rec.audioSessionId)
        try {
            rec.startRecording()
        } catch (e: Exception) {
            // 权限竞态、麦克风被别的 App 占用等都可能在这里抛。
            // 不接住的话异常会从主线程冒出去直接崩，而且刚挂上的效果器和 AudioRecord 全泄漏。
            releaseAudioEffects()
            try { rec.release() } catch (ignored: Exception) { /* 忽略 */ }
            recorder = null
            return
        }
        capturing.set(true)
        captureThread = Thread({ captureLoop() }, "rt-capture").apply { start() }
    }

    /**
     * 挂上系统自带的回声消除与降噪。
     *
     * 免提外放时麦克风会收到自己的声音，服务端 VAD 会把它当成「用户在说话」，
     * 于是一直自己跟自己对话。之前的策略只是「录音时切断外放 + 用户开口就停播」，
     * 那是逻辑规避；这里补上真正的端上处理。
     *
     * **刻意不开 AutomaticGainControl**：它会改变采集电平，而端上门控的 -45 dBFS 与
     * 服务端 VAD 的 200 都是按当前电平标定的，开了就得重新标定一遍（见交接文档 §13.2）。
     *
     * 全部是 best-effort：设备/ROM 不支持时静默跳过，绝不影响主链路。
     */
    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId == 0) return
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                echoCanceler = android.media.audiofx.AcousticEchoCanceler
                    .create(sessionId)?.also { it.enabled = true }
            }
        } catch (e: Exception) {
            echoCanceler = null
        }
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor
                    .create(sessionId)?.also { it.enabled = true }
            }
        } catch (e: Exception) {
            noiseSuppressor = null
        }
    }

    private fun releaseAudioEffects() {
        try { echoCanceler?.release() } catch (e: Exception) { /* 忽略 */ }
        try { noiseSuppressor?.release() } catch (e: Exception) { /* 忽略 */ }
        echoCanceler = null
        noiseSuppressor = null
    }

    private var silent: ByteArray? = null

    private fun silentFrame(): ByteArray =
        silent ?: ByteArray(FRAME_BYTES).also { silent = it }

    private fun captureLoop() {
        val buf = ByteArray(FRAME_BYTES)
        val shorts = ShortArray(FRAME_SAMPLES)
        var silenceFrames = 0
        var lastLoud = false
        while (capturing.get()) {
            val rec = recorder ?: break
            var read = 0
            while (read < FRAME_BYTES) {
                // read 可能在 stop/release 的瞬间抛 IllegalStateException，必须接住 ——
                // 这是采集线程，异常冒出去就是整个 App 崩
                val n = try {
                    rec.read(buf, read, FRAME_BYTES - read)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) break
                read += n
            }
            if (read < FRAME_BYTES) continue
            // 能量判定（复用 VadPolicy 帧电平）
            for (i in 0 until FRAME_SAMPLES) {
                val lo = buf[i * 2].toInt() and 0xFF
                val hi = buf[i * 2 + 1].toInt()
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            val db = VadPolicy.frameDb(shorts, 0, FRAME_SAMPLES)
            val loud = db >= DEFAULT_THRESHOLD_DB
            if (loud) {
                silenceFrames = 0
                if (!speechActive) {
                    speechActive = true
                    onSpeechStateChanged(true)
                }
            } else {
                silenceFrames++
                if (speechActive && silenceFrames >= SILENCE_GATE_FRAMES) {
                    speechActive = false
                    onSpeechStateChanged(false)
                    continue // 静音门控：本帧及后续静音帧都不推流
                }
            }
            if (pushToTalkMode) {
                // 按住说话：流保持连续，没按的时候推静音帧
                onAudioFrame(if (pushing) buf.copyOf() else silentFrame())
            } else if (speechActive) {
                onAudioFrame(buf.copyOf())
            }
            // 只在「响/静」翻转时记一笔。之前每秒一条，排「服务端听不见」时有用，
            // 但平时就是噪音；翻转日志已经足够回答「麦克风到底有没有在收声音」。
            if (loud != lastLoud) {
                android.util.Log.i(
                    "ShiyuRealtime",
                    "mic ${if (loud) "speech" else "silence"} db=${"%.1f".format(db)} active=$speechActive"
                )
                lastLoud = loud
            }
            val amp = if (db <= -100) 0 else ((db + 60.0) / 60.0 * 100).toInt().coerceIn(0, 100)
            onAmplitude(amp)
        }
    }

    fun stopCapture() {
        capturing.set(false)
        // **必须等采集线程退出后再释放 AudioRecord**。
        // 否则采集线程可能正阻塞在 rec.read()，我们这边 stop()+release() 之后它再调用一次
        // read()，就会在已释放的对象上抛 IllegalStateException —— 采集线程没有捕获，直接崩。
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(CAPTURE_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseAudioEffects()
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // 忽略
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            // 忽略
        }
        recorder = null
        speechActive = false
    }

    // ---------- 播放 ----------

    /**
     * 播放一包下行音频。
     *
     * **`write` 必须在锁外执行**：MODE_STREAM 下缓冲写满时 `write` 会阻塞，
     * 而 `interrupt()` 需要同一把锁 —— 持锁写会让「打断」卡在锁上，
     * 用户感觉是「按了打断要等一下才停」。
     * 锁只用来保护 player/playing 这几个字段的读写。
     */
    fun playPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // 打断之后、服务端真正停下之前，还会飘来几百毫秒的音频包。
        // 不过滤的话，下一个包就会把刚暂停的播放重新拉起来 —— 表现就是
        // 「按了打断没反应，它停一下又接着说」。
        if (droppingAudio) return
        val track: android.media.AudioTrack
        synchronized(playerLock) {
            if (droppingAudio) return
            val t = player ?: newTrack().also { player = it }
            if (!playing.get()) {
                try {
                    t.play()
                } catch (e: Exception) {
                    return
                }
                playing.set(true)
            }
            track = t
        }
        try {
            track.write(bytes, 0, bytes.size)
        } catch (e: Exception) {
            // 播放失败不该影响主链路（打断时 write 会正常返回错误）
        }
    }

    /**
     * 打断：立即停播，并开启丢弃窗口。
     *
     * 窗口在 [resumeAudio] 时关闭 —— 调用方应当在服务端开始**新一轮**响应
     * （`response.created`）时调用它，而不是在打断那一刻。
     */
    fun resumeAudio() {
        droppingAudio = false
    }

    private fun newTrack(): android.media.AudioTrack =
        android.media.AudioTrack.Builder()
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(4096, FRAME_BYTES * 32))
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()

    /** barge-in：丢弃已排队音频，立即静音，并挡住后续残留的音频包 */
    fun interrupt() {
        // 先把标志立起来：playPcm 在锁外也会读它，新来的音频包立即被挡掉，
        // 不用等 pause/flush 做完
        droppingAudio = true
        playing.set(false)
        val track = synchronized(playerLock) { player } ?: return
        try {
            track.pause()
            track.flush()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun stopPlayback() {
        playing.set(false)
        val track = synchronized(playerLock) { player }
        try {
            track?.pause()
            track?.flush()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun release() {
        droppingAudio = false
        stopCapture()
        stopPlayback()
        try {
            player?.release()
        } catch (e: Exception) {
            // 忽略
        }
        synchronized(playerLock) { player = null }
    }

    /** 与 VadPolicy 默认阈值一致（-45 dBFS） */
    private val DEFAULT_THRESHOLD_DB = -45.0
}
