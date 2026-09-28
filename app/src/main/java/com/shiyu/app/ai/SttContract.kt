package com.shiyu.app.ai

/**
 * ASR（语音识别）各 Provider 与设置页探活之间的公共契约。
 *
 * 「端点通了、音频也被接受，但里面没有可识别的人声」时用这个描述。
 * 设置页的 `runProbe` 发的是一段静音探针，任何 Provider 都必然得到空结果，
 * 所以探活时要把这条当成**成功**（地址错是 404、Key 错是 401，不会被它掩盖）。
 *
 * 单独放在这里而不是塞进某个具体 Provider 的 Wire 里：现在已有
 * stepfun 原生、Whisper 兼容、火山方舟三套实现，任一处的常量都名不副实。
 */
object SttContract {

    const val EMPTY_TRANSCRIPT = "empty transcript"
}
