package com.shiyu.app.data.model

import com.shiyu.app.core.lang.LangId

/**
 * 应用设置（加密存储于 secure_settings.bin 的明文 JSON）。
 * langs 恒含两个语言（缺省由 SettingsRepository 补齐）。
 * version 2：新增 realtime 组与 realtimeOptions（version 1 文件缺字段时按默认值自愈，无需迁移）。
 */
data class AppSettings(
    val version: Int = 2,
    val activeLang: LangId = LangId.JA,
    val autoPlayTts: Boolean = true,
    val langs: Map<LangId, LanguageConfig> = mapOf(
        LangId.JA to LanguageConfig(),
        LangId.EN to LanguageConfig()
    )
)
