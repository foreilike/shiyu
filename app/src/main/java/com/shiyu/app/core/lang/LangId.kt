package com.shiyu.app.core.lang

/**
 * 全局语言标识。tag 用于持久化（study_progress / secure_settings），
 * displayName 用于语言切换条，sttLanguage 作为语音识别接口的 language 参数。
 */
enum class LangId(val tag: String, val displayName: String, val sttLanguage: String) {
    JA("ja", "日语", "ja"),
    EN("en", "英语", "en");

    companion object {
        fun fromTag(tag: String?): LangId = if (tag == "en") EN else JA
    }
}
