package com.langpartner.app.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 能量 VAD（语音活动检测）决策器——纯逻辑，无 Android 依赖，单测覆盖。
 *
 * 背景：STT 按整段音频时长计费，静音段照计；且头尾静音无识别价值。
 * 本类只负责「给定 PCM16 序列，裁掉头尾静音」的区间决策，不做解码。
 *
 * 判定规则：
 * 1. 按 [Params.frameMs] 分帧，逐帧计算 RMS 电平（dBFS，相对 32768 满幅）；
 * 2. 帧 RMS >= [Params.thresholdDb] 记为有声帧；
 * 3. 取首个 / 末个有声帧，向前后各扩展 [Params.prefixPadMs] / [Params.suffixPadMs]；
 * 4. 有效语音短于 [Params.minSpeechMs] → 视为无语音，返回 null；
 * 5. 裁掉比例超过 [Params.maxTrimmedRatio] → 几乎全是静音，返回 null（交由调用方提示用户）。
 */
object VadPolicy {

    data class Params(
        val sampleRate: Int = 16000,
        val frameMs: Int = 20,
        val thresholdDb: Double = -45.0,
        val prefixPadMs: Int = 300,
        val suffixPadMs: Int = 500,
        val minSpeechMs: Int = 200,
        val maxTrimmedRatio: Double = 0.98
    )

    data class Result(
        val startSample: Int,
        val endSample: Int,
        val originalMs: Long,
        val keptMs: Long,
        val trimmedMs: Long
    )

    /**
     * @param pcm 16-bit 单声道 PCM 小端样本序列
     * @return 裁剪区间；null 表示整段无有效语音（不应计费上传）
     */
    fun trim(pcm: ShortArray, params: Params = Params()): Result? {
        if (pcm.isEmpty()) return null
        val frameSize = max(1, params.sampleRate * params.frameMs / 1000)
        val frameCount = pcm.size / frameSize
        if (frameCount == 0) return null

        val speech = BooleanArray(frameCount)
        var anySpeech = false
        for (f in 0 until frameCount) {
            val db = frameDb(pcm, f * frameSize, frameSize)
            speech[f] = db >= params.thresholdDb
            if (speech[f]) anySpeech = true
        }
        if (!anySpeech) return null

        var first = -1
        var last = -1
        for (f in 0 until frameCount) {
            if (speech[f]) {
                if (first < 0) first = f
                last = f
            }
        }

        // padding 为 0 时严格不扩展（自定义参数锚点），>0 时换算为整帧
        val padFrames = { ms: Int -> ms / params.frameMs }
        val startFrame = max(0, first - padFrames(params.prefixPadMs))
        val endFrame = min(frameCount - 1, last + padFrames(params.suffixPadMs))

        val startSample = startFrame * frameSize
        val endSample = min(pcm.size, (endFrame + 1) * frameSize)
        val totalSamples = pcm.size
        val keptSamples = endSample - startSample

        val originalMs = totalSamples * 1000L / params.sampleRate
        val keptMs = keptSamples * 1000L / params.sampleRate
        if (keptMs < params.minSpeechMs) return null

        val trimmedRatio = 1.0 - keptSamples.toDouble() / totalSamples
        if (trimmedRatio >= params.maxTrimmedRatio) return null

        return Result(
            startSample = startSample,
            endSample = endSample,
            originalMs = originalMs,
            keptMs = keptMs,
            trimmedMs = originalMs - keptMs
        )
    }

    /** 帧 RMS 电平（dBFS）；全零帧记 -100 dB，避免 log10(0)。 */
    internal fun frameDb(pcm: ShortArray, offset: Int, size: Int): Double {
        var sum = 0.0
        for (i in offset until min(pcm.size, offset + size)) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / size)
        if (rms <= 0.0) return -100.0
        val db = 20.0 * log10(rms / 32768.0)
        return max(-100.0, db)
    }
}
