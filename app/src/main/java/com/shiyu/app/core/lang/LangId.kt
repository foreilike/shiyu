package com.shiyu.app.core.lang

/**
 * 全局语言标识。tag 用于持久化（study_progress / secure_settings），
 * displayName 用于语言切换条，sttLanguage 作为语音识别接口的 language 参数。
 *
 * **这里不再有 `realtimeTranscriptReliable`**（2026-10-14 删）：实时语音已改成自动跟随
 * 用户说的语种，没有"当前这门语言"可言，按语言决定"要不要纠正字幕"的前提不成立了。
 * 日语字幕仍会是中文谐音，这一点已拍板接受，详见 `VoiceChatActivity` 里的说明。
 */
enum class LangId(
    val tag: String,
    val displayName: String,
    val sttLanguage: String
) {
    JA("ja", "日语", "ja"),
    EN("en", "英语", "en");

    companion object {
        fun fromTag(tag: String?): LangId = if (tag == "en") EN else JA
    }
}
