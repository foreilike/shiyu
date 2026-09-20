package com.shiyu.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VadPolicy 单测（A 档 VAD 锚点）。
 * 默认参数：16000Hz / 20ms 帧（320 样本）/ -45dB 阈值 / 前 300ms 后 500ms padding / 最短 200ms / 裁剪比上限 0.98。
 */
class VadPolicyTest {

    private val params = VadPolicy.Params()

    /** 恒幅方波帧序列：RMS = amp（dBFS = 20·log10(amp/32768)）。 */
    private fun frames(n: Int, amp: Int = 0): ShortArray = ShortArray(n * 320) { amp.toShort() }

    @Test
    fun empty_pcm_returns_null() {
        assertNull(VadPolicy.trim(ShortArray(0)))
    }

    @Test
    fun all_silence_returns_null() {
        assertNull(VadPolicy.trim(frames(50, amp = 0)))
    }

    @Test
    fun below_threshold_returns_null() {
        // amp=100 → -50.3 dBFS，低于 -45 阈值
        assertNull(VadPolicy.trim(frames(50, amp = 100)))
    }

    @Test
    fun threshold_boundary_185_is_speech_184_is_silence() {
        // 185 → -44.97dB（算语音）；184 → -45.01dB（算静音）
        assertTrue(VadPolicy.trim(frames(50, amp = 185)) != null)
        assertNull(VadPolicy.trim(frames(50, amp = 184)))
    }

    @Test
    fun trims_leading_and_trailing_silence_with_padding() {
        // 50 帧静音 + 25 帧人声 + 50 帧静音（合计 2500ms）
        val pcm = frames(50) + frames(25, amp = 8000) + frames(50)
        val r = VadPolicy.trim(pcm)!!
        // 前 padding 15 帧 → startFrame=35 → 700ms；后 padding 25 帧 → endFrame=99 → 2000ms
        assertEquals(35 * 320, r.startSample)
        assertEquals(100 * 320, r.endSample)
        assertEquals(2500L, r.originalMs)
        assertEquals(1300L, r.keptMs)
        assertEquals(1200L, r.trimmedMs)
    }

    @Test
    fun speech_at_start_clamps_padding() {
        // 25 帧人声 + 50 帧静音
        val pcm = frames(25, amp = 8000) + frames(50)
        val r = VadPolicy.trim(pcm)!!
        assertEquals(0, r.startSample)
        assertEquals(50 * 320, r.endSample) // 25 + 25 padding
        assertEquals(1000L, r.keptMs)
        assertEquals(1500L, r.originalMs)
    }

    @Test
    fun speech_at_end_clamps_padding() {
        // 50 帧静音 + 25 帧人声
        val pcm = frames(50) + frames(25, amp = 8000)
        val r = VadPolicy.trim(pcm)!!
        assertEquals(35 * 320, r.startSample)
        assertEquals(75 * 320, r.endSample) // 末帧 74 + padding 25 被钳到 74
        assertEquals(1500L, r.originalMs)
        assertEquals(800L, r.keptMs)
    }

    @Test
    fun too_short_speech_returns_null() {
        // 1 帧人声 + 1 帧静音：padding 后被钳到 40ms < minSpeechMs=200
        val pcm = frames(1, amp = 8000) + frames(1)
        assertNull(VadPolicy.trim(pcm))
    }

    @Test
    fun nearly_all_silence_returns_null_by_ratio() {
        // 4000 帧（80s）里只有 1 帧人声：裁剪比 0.9898 ≥ 0.98 → 判无效
        val pcm = frames(2000) + frames(1, amp = 8000) + frames(1999)
        assertNull(VadPolicy.trim(pcm))
    }

    @Test
    fun custom_params_are_honored() {
        // 无 padding、-40dB 阈值、最短 100ms
        val p = VadPolicy.Params(thresholdDb = -40.0, prefixPadMs = 0, suffixPadMs = 0, minSpeechMs = 100)
        // amp=500 → -36.3dB，在 -40 阈值之上
        val pcm = frames(10) + frames(10, amp = 500) + frames(10)
        val r = VadPolicy.trim(pcm, p)!!
        assertEquals(10 * 320, r.startSample)
        assertEquals(20 * 320, r.endSample)
        assertEquals(200L, r.keptMs)
        assertEquals(600L, r.originalMs)
        // amp=500 → -36.3dB 低于 -30 阈值 → 全静音
        val p2 = VadPolicy.Params(thresholdDb = -30.0)
        assertNull(VadPolicy.trim(pcm, p2))
    }
}
