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
        const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320 // 20ms @16k
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
        val track = player ?: newTrack().also { player = it }
        if (!playing.get()) {
            track.play()
            playing.set(true)
        }
        track.write(bytes, 0, bytes.size)
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

    /** barge-in：丢弃已排队音频，立即静音 */
    @Synchronized
    fun interrupt() {
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
