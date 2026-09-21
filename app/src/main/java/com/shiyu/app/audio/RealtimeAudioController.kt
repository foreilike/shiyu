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
        private const val SILENCE_GATE_MS = 800
        private const val SILENCE_GATE_FRAMES = SILENCE_GATE_MS / 20
    }

    private var recorder: android.media.AudioRecord? = null
    private var captureThread: Thread? = null
    private val capturing = AtomicBoolean(false)

    private var player: android.media.AudioTrack? = null
    private val playing = AtomicBoolean(false)

    @Volatile var speechActive: Boolean = false
        private set

    /** 打断后到新一轮响应开始之间，丢弃下行的残留音频 */
    @Volatile private var droppingAudio = false

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
        rec.startRecording()
        capturing.set(true)
        captureThread = Thread({ captureLoop() }, "rt-capture").apply { start() }
    }

    private fun captureLoop() {
        val buf = ByteArray(FRAME_BYTES)
        val shorts = ShortArray(FRAME_SAMPLES)
        var silenceFrames = 0
        var lastLoud = false
        while (capturing.get()) {
            val rec = recorder ?: break
            var read = 0
            while (read < FRAME_BYTES) {
                val n = rec.read(buf, read, FRAME_BYTES - read)
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
            if (speechActive) onAudioFrame(buf.copyOf())
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
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // 忽略
        }
        recorder?.release()
        recorder = null
        captureThread = null
        speechActive = false
    }

    // ---------- 播放 ----------

    @Synchronized
    fun playPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // 打断之后、服务端真正停下之前，还会飘来几百毫秒的音频包。
        // 不过滤的话，下一个包就会把刚暂停的播放重新拉起来 —— 表现就是
        // 「按了打断没反应，它停一下又接着说」。
        if (droppingAudio) return
        val track = player ?: newTrack().also { player = it }
        if (!playing.get()) {
            track.play()
            playing.set(true)
        }
        track.write(bytes, 0, bytes.size)
    }

    /**
     * 打断：立即停播，并开启丢弃窗口。
     *
     * 窗口在 [resumeAudio] 时关闭 —— 调用方应当在服务端开始**新一轮**响应
     * （`response.created`）时调用它，而不是在打断那一刻。
     */
    @Synchronized
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
    @Synchronized
    fun interrupt() {
        droppingAudio = true
        val track = player ?: return
        try {
            track.pause()
            track.flush()
        } catch (e: Exception) {
            // 忽略
        }
        playing.set(false)
    }

    @Synchronized
    fun stopPlayback() {
        val track = player
        playing.set(false)
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
        player = null
    }

    /** 与 VadPolicy 默认阈值一致（-45 dBFS） */
    private val DEFAULT_THRESHOLD_DB = -45.0
}
