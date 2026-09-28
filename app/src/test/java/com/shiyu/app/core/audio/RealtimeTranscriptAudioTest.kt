package com.shiyu.app.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WavWriter / Pcm16 / PcmUtteranceBuffer —— 实时语音「用户字幕纠错」的纯逻辑件
 * （§17.21.5 B′）。
 *
 * 这几个件共同保证：**送进语音识别的必须是采样率正确、格式合法的音频**。
 * 之前探针就是喂了垃圾数据导致服务端报「音频格式异常」（§17.21.7），
 * 所以这里把字节布局钉死。
 */
class WavWriterTest {

    private fun ascii(buf: ByteArray, offset: Int, len: Int) =
        String(buf, offset, len, Charsets.US_ASCII)

    private fun int32LE(buf: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (buf[offset + i].toInt() and 0xFF)
        return v
    }

    private fun int16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun mono16_writes_valid_header_at_the_real_sample_rate() {
        val pcm = ByteArray(2400) { 1 } // 1200 样本
        val wav = WavWriter.mono16(pcm, sampleRate = 24000)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(16, int32LE(wav, 16))
        assertEquals(1, int16LE(wav, 20))
        assertEquals(1, int16LE(wav, 22))
        // 采样率必须原样写进去：写成别的值，识别出来就是变速的
        assertEquals(24000, int32LE(wav, 24))
        assertEquals(24000 * 2, int32LE(wav, 28))
        assertEquals(2, int16LE(wav, 32))
        assertEquals(16, int16LE(wav, 34))
        assertEquals(pcm.size, int32LE(wav, 40))
        assertEquals(36 + pcm.size, int32LE(wav, 4))
        assertEquals(WavWriter.HEADER_BYTES + pcm.size, wav.size)
    }

    @Test
    fun mono16_copies_payload_after_header() {
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val wav = WavWriter.mono16(pcm, sampleRate = 16000)
        assertArrayEquals(pcm, wav.copyOfRange(WavWriter.HEADER_BYTES, wav.size))
    }

    @Test
    fun mono16_drops_odd_tail_byte() {
        // 奇数长度会让「样本数 × 2」对不上，必须截掉而不是照抄
        val wav = WavWriter.mono16(byteArrayOf(1, 2, 3), sampleRate = 16000)
        assertEquals(2, int32LE(wav, 40))
        assertEquals(WavWriter.HEADER_BYTES + 2, wav.size)
    }

    @Test
    fun silence_has_zeroed_payload() {
        val wav = WavWriter.silence(16000, millis = 500)
        assertEquals(44 + 16000, wav.size)
        assertTrue(wav.drop(WavWriter.HEADER_BYTES).all { it == 0.toByte() })
    }
}

class Pcm16Test {

    @Test
    fun round_trips_little_endian() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345)
        assertArrayEquals(samples, Pcm16.toShorts(Pcm16.toBytes(samples)))
    }

    @Test
    fun to_shorts_reads_little_endian() {
        // 0x34 是低位、0x12 是高位 → 0x1234
        assertEquals(1, Pcm16.toShorts(byteArrayOf(0x34, 0x12)).size)
        assertEquals(0x1234.toShort(), Pcm16.toShorts(byteArrayOf(0x34, 0x12))[0])
    }

    @Test
    fun to_shorts_handles_negative() {
        // -2 的小端表示是 FE FF
        assertEquals((-2).toShort(), Pcm16.toShorts(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))[0])
    }

    @Test
    fun to_shorts_drops_odd_tail() {
        assertEquals(1, Pcm16.toShorts(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun empty_input_gives_empty_output() {
        assertEquals(0, Pcm16.toShorts(ByteArray(0)).size)
        assertEquals(0, Pcm16.toBytes(ShortArray(0)).size)
    }
}

class PcmUtteranceBufferTest {

    @Test
    fun accumulates_and_drains_once() {
        val buf = PcmUtteranceBuffer()
        buf.offer(byteArrayOf(1, 2))
        buf.offer(byteArrayOf(3, 4))
        assertEquals(4, buf.sizeBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buf.drain())
        // drain 即清空：语义是「把这一句交出去」
        assertEquals(0, buf.sizeBytes)
        assertEquals(0, buf.drain().size)
    }

    @Test
    fun empty_frames_are_ignored() {
        val buf = PcmUtteranceBuffer()
        buf.offer(ByteArray(0))
        assertEquals(0, buf.sizeBytes)
    }

    @Test
    fun keeps_only_the_most_recent_bytes_when_over_cap() {
        // 上限保证「VAD 一直不报说完」时内存不会失控
        val buf = PcmUtteranceBuffer(maxBytes = 4)
        buf.offer(byteArrayOf(1, 2, 3, 4))
        buf.offer(byteArrayOf(5, 6))
        assertArrayEquals("应保留最近的 4 字节", byteArrayOf(3, 4, 5, 6), buf.drain())
    }

    @Test
    fun clear_discards_without_returning() {
        val buf = PcmUtteranceBuffer()
        buf.offer(byteArrayOf(9))
        buf.clear()
        assertEquals(0, buf.drain().size)
    }

    @Test
    fun default_cap_is_30_seconds_of_24khz_mono_16bit() {
        assertEquals(30 * 24_000 * 2, PcmUtteranceBuffer.DEFAULT_MAX_BYTES)
    }
}
