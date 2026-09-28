package com.shiyu.app.audio

import com.shiyu.app.core.audio.WavWriter

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
    fun silentWav(sampleRate: Int = 16000, millis: Int = 1000): ByteArray =
        WavWriter.silence(sampleRate, millis)
}
