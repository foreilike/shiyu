package com.shiyu.app.core.lang

/**
 * 全局语言标识。tag 用于持久化（study_progress / secure_settings），
 * displayName 用于语言切换条，sttLanguage 作为语音识别接口的 language 参数。
 */
enum class LangId(
    val tag: String,
    val displayName: String,
    val sttLanguage: String,
    /**
     * **实时语音**给出的「用户说了什么」转写是否可信。
     *
     * stepfun realtime（`stepaudio-3-realtime-preview`）的转写头只按中文、英文训练，
     * 说日语会被按中英解码成中文谐音（如 ありがとう → 「阿里嘎多」）。
     * 模型本身听得懂日语（实测她的回复是对的），**错的只是给你看的那行字幕**
     * ——详见 HANDOVER §17.21.3。
     *
     * 为 false 时，实时语音页会用独立语音识别（§17.21.4 的 B′）把字幕换成正确语种；
     * 为 true 时沿用实时通道自己的转写，省一次请求与等待。
     */
    val realtimeTranscriptReliable: Boolean
) {
    JA("ja", "日语", "ja", realtimeTranscriptReliable = false),
    EN("en", "英语", "en", realtimeTranscriptReliable = true);

    companion object {
        fun fromTag(tag: String?): LangId = if (tag == "en") EN else JA
    }
}
