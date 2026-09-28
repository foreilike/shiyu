package com.shiyu.app.core.audio

/**
 * 把裸 PCM 包成 WAV 容器（纯 JVM，可单测）。
 *
 * 用途：实时语音要把「用户这一句」的采集音频送去语音识别（§17.21.5 B′），
 * 而识别接口吃的是**带头的音频文件**，不是裸 PCM。
 *
 * 与 [com.shiyu.app.audio.ProbeAudio] 共用同一份头部写法 —— 之前探针那份手写的
 * 44 字节头是对的，这里抽出来复用，避免两处各写一份、改一处忘一处。
 */
object WavWriter {

    /** 标准 44 字节 WAV 头长度（不含 data） */
    const val HEADER_BYTES = 44

    /**
     * 单声道 16bit PCM → WAV。
     *
     * @param pcm 小端 16bit 样本字节序列（长度须为偶数，奇数尾字节会被丢弃）
     * @param sampleRate 采样率，必须与 pcm 实际一致，否则识别出来是变速的
     */
    fun mono16(pcm: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size - (pcm.size % 2)
        val out = ByteArray(HEADER_BYTES + dataSize)
        writeAscii(out, 0, "RIFF")
        writeInt(out, 4, 36 + dataSize)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeInt(out, 16, 16)
        writeShort(out, 20, 1) // PCM
        writeShort(out, 22, 1) // 单声道
        writeInt(out, 24, sampleRate)
        writeInt(out, 28, sampleRate * 2) // byteRate
        writeShort(out, 32, 2) // blockAlign
        writeShort(out, 34, 16) // bitsPerSample
        writeAscii(out, 36, "data")
        writeInt(out, 40, dataSize)
        System.arraycopy(pcm, 0, out, HEADER_BYTES, dataSize)
        return out
    }

    /** 指定时长的静音 WAV（测试连接的探针用） */
    fun silence(sampleRate: Int, millis: Int): ByteArray =
        mono16(ByteArray(sampleRate * millis / 1000 * 2), sampleRate)

    private fun writeAscii(buf: ByteArray, offset: Int, text: String) {
        for (i in text.indices) {
            buf[offset + i] = text[i].code.toByte()
        }
    }

    /** WAV 头是小端 */
    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
