package com.shiyu.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProbeAudio：设置页「测试连接」用的静音 WAV。
 *
 * 钉住的目标：**生成的必须是能被解码的合法 WAV**。
 * 之前的探针是 64 字节全 0 + 扩展名 .m4a，服务端回「音频格式异常，无法解析」，
 * 导致填对了地址也永远探活失败。
 */
class ProbeAudioTest {

    private fun ascii(buf: ByteArray, offset: Int, len: Int): String =
        String(buf, offset, len, Charsets.US_ASCII)

    private fun int32LE(buf: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 3 downTo 0) {
            v = (v shl 8) or (buf[offset + i].toInt() and 0xFF)
        }
        return v
    }

    private fun int16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun has_valid_riff_wave_header() {
        val wav = ProbeAudio.silentWav()
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
    }

    @Test
    fun header_fields_are_consistent() {
        val wav = ProbeAudio.silentWav(sampleRate = 16000, millis = 1000)
        val dataSize = int32LE(wav, 40)
        assertEquals("1 秒 ×16k ×单声道 ×16bit", 32000, dataSize)
        assertEquals("RIFF size = 36 + data", 36 + dataSize, int32LE(wav, 4))
        assertEquals("fmt chunk = 16", 16, int32LE(wav, 16))
        assertEquals("PCM", 1, int16LE(wav, 20))
        assertEquals("单声道", 1, int16LE(wav, 22))
        assertEquals("采样率", 16000, int32LE(wav, 24))
        assertEquals("byteRate", 16000 * 2, int32LE(wav, 28))
        assertEquals("blockAlign", 2, int16LE(wav, 32))
        assertEquals("位深", 16, int16LE(wav, 34))
        assertEquals("总长 = 44 + data", 44 + dataSize, wav.size)
    }

    @Test
    fun data_region_is_silence() {
        val wav = ProbeAudio.silentWav(millis = 500)
        assertTrue("数据区应全为 0（静音）", wav.drop(44).all { it == 0.toByte() })
    }

    @Test
    fun length_scales_with_millis() {
        assertEquals(44 + 16000 * 2, ProbeAudio.silentWav(millis = 1000).size)
        assertEquals(44 + 8000 * 2, ProbeAudio.silentWav(millis = 500).size)
    }
}
