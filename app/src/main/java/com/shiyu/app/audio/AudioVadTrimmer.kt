package com.shiyu.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.shiyu.app.core.audio.VadPolicy
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端上音频裁剪（A 档 VAD）：
 * m4a/AAC 录音文件 → 解码为 PCM16 → [VadPolicy] 裁掉头尾静音 → 写 WAV。
 *
 * 设计约束：
 * - 只用 framework 组件（MediaExtractor / MediaCodec），不引第三方依赖；
 * - 任何一步失败都返回 null，由调用方回退到原始文件上传，不影响主链路；
 * - WAV 而非重编码 AAC：OpenAI 兼容 STT 原生支持 audio/wav，避开 AAC 重编码的设备兼容坑；
 * - 放在 app.audio 而非 core：本类依赖 android.media，属平台适配层（core 保持纯逻辑，见 SourcePolicyTest）。
 */
object AudioVadTrimmer {

    data class Outcome(
        val file: File,
        val originalMs: Long,
        val keptMs: Long,
        val trimmedMs: Long
    )

    /** 最长处理的音频秒数，防异常长文件吃内存（与录音上限一致）。 */
    private const val MAX_SECONDS = 60

    /**
     * @param input 录音文件（m4a/AAC）
     * @param cacheDir 输出目录
     * @return 裁剪成功时返回新 WAV 文件与统计；全静音 / 解码失败返回 null
     */
    fun trim(input: File, cacheDir: File): Outcome? {
        if (!input.exists() || input.length() == 0L) return null
        return try {
            val pcm = decodeToPcm16(input) ?: return null
            val result = VadPolicy.trim(pcm.samples) ?: return null
            val out = File(cacheDir, "vad_${System.currentTimeMillis()}.wav")
            writeWav(out, pcm.samples, result.startSample, result.endSample, pcm.sampleRate)
            Outcome(out, result.originalMs, result.keptMs, result.trimmedMs)
        } catch (e: Exception) {
            null
        }
    }

    /** 解码后的 PCM16（小端）与采样率。 */
    private class Pcm16(val samples: ShortArray, val sampleRate: Int)

    private fun decodeToPcm16(input: File): Pcm16? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamples = MAX_SECONDS * 48000
            val acc = ArrayList<Short>(1 shl 18)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val chunk = ShortArray(info.size / 2)
                    outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunk)
                    for (s in chunk) acc.add(s)
                    // 采样率以 decoder 实际输出为准（部分设备与容器声明不一致）
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                    if (acc.size > maxSamples) return null // 超长，放弃裁剪
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            return Pcm16(acc.toShortArray(), sampleRate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** 写 16-bit 单声道 WAV（44 字节头 + PCM）。 */
    private fun writeWav(out: File, pcm: ShortArray, start: Int, end: Int, sampleRate: Int) {
        val samples = end - start
        val dataBytes = samples * 2
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shortBuf = body.asShortBuffer()
            for (i in start until end) shortBuf.put(pcm[i])
            raf.write(body.array())
        }
    }
}
