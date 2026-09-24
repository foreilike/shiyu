package com.shiyu.app.audio

/**
 * 「测试连接」用的探针音频（纯 JVM，可单测）。
 *
 * 由来：原先设置页探活写的是 **64 字节全 0、扩展名 .m4a** —— 那不是合法音频文件
 * （既没有 m4a 容器头，也不是任何可解码的码流），服务端只能回
 * 「音频格式异常，无法解析」，于是填对了地址也永远探活失败。
 *
 * 探活的目的是验证「地址可达 + 鉴权通过」，不是验证识别质量，
 * 所以发一段**合法的静音 WAV** 即可：服务端能解码、返回空转写，
 * 由调用方把「空转写」判定为成功（见 SettingsActivity.runProbe）。
 */
object ProbeAudio {

    /**
     * 生成一段静音的 16bit PCM WAV。
     *
     * @param sampleRate 采样率，默认 16kHz（ASR 常用）
     * @param millis 时长，默认 1000ms（太短容易被判为无效音频）
     */
    fun silentWav(sampleRate: Int = 16000, millis: Int = 1000): ByteArray {
        val samples = sampleRate * millis / 1000
        val dataSize = samples * 2 // 单声道 × 16bit
        val out = ByteArray(44 + dataSize)
        writeAscii(out, 0, "RIFF")
        writeInt(out, 4, 36 + dataSize)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeInt(out, 16, 16) // fmt chunk 长度
        writeShort(out, 20, 1) // PCM
        writeShort(out, 22, 1) // 单声道
        writeInt(out, 24, sampleRate)
        writeInt(out, 28, sampleRate * 2) // byteRate = 采样率 × 声道 × 位深/8
        writeShort(out, 32, 2) // blockAlign
        writeShort(out, 34, 16) // bitsPerSample
        writeAscii(out, 36, "data")
        writeInt(out, 40, dataSize)
        // 数据区保持全 0 = 静音
        return out
    }

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
