package com.shiyu.app.data.model

import com.shiyu.app.core.lang.LangId

/**
 * 应用设置（加密存储于 secure_settings.bin 的明文 JSON）。
 * langs 恒含两个语言（缺省由 SettingsRepository 补齐）。
 * version 2：新增 realtime 组与 realtimeOptions（version 1 文件缺字段时按默认值自愈，无需迁移）。
 * nickname 为后加字段，缺失时为空串 —— 空串表示「用户没设过名字」，UI 用占位文案兜底，
 * 老文件不需要迁移。
 * dailyGoalMinutes 同为后加字段，缺失时回落到 [DEFAULT_DAILY_GOAL_MINUTES]。
 */
data class AppSettings(
    val version: Int = 2,
    val activeLang: LangId = LangId.JA,
    val autoPlayTts: Boolean = true,
    val nickname: String = "",
    val dailyGoalMinutes: Int = DEFAULT_DAILY_GOAL_MINUTES,
    /** 实时语音是否显示中文翻译（默认关：中文字幕会占掉沉浸页的视线） */
    val realtimeTranslate: Boolean = false,
    /**
     * 实时语音是否用「按住说话」（默认关）。
     *
     * 开着的时候，只有按住麦克风按钮的那段时间你的声音才会进服务端，松开就推静音。
     * 这是**唯一能挡住"旁人说话"的办法** —— 服务端 VAD 只测响度，分不出是谁在说，
     * 半米外的人声和本人电平只差几个 dB，靠调阈值无解（实测踩过）。
     */
    val voicePushToTalk: Boolean = false,
    /** 每门语言当前选中的词书 id（缺省用该语言 books() 的第一本）。后加字段，老文件缺失即空 map。 */
    val activeBooks: Map<LangId, String> = emptyMap(),
    val langs: Map<LangId, LanguageConfig> = mapOf(
        LangId.JA to LanguageConfig(),
        LangId.EN to LanguageConfig()
    )
) {
    companion object {
        /** 每日目标默认 30 分钟 */
        const val DEFAULT_DAILY_GOAL_MINUTES = 30
    }
}
