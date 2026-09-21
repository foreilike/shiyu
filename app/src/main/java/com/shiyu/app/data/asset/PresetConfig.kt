package com.shiyu.app.data.asset

import android.content.Context
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.store.SettingsRepository
import org.json.JSONObject

/**
 * 出厂预置配置（`assets/preset_config.json`）。
 *
 * **安全提醒：这份文件是明文打包进 APK 的。** 任何拿到安装包的人解压就能读出里面的
 * API Key —— 它只适合自用或内部分发，不要当公开分发的版本。
 * 要给别人用请把 `apiKey` 留空，让对方自己填。
 *
 * 写在这里而不是 `data/store`：那个包被 `SourcePolicyTest` 禁止 import android.*
 * （它要保持纯 JVM 可测），而读 assets 需要 Context。
 */
object PresetConfig {

    /**
     * 只在**本地一份配置都没有**时写入，绝不覆盖用户已有的设置。
     *
     * 判定用「四组是否全空」而不是「配置文件是否存在」：用户如果在设置页点了某组的
     * 「清空」，文件还在但内容是空的 —— 那种情况下补回预置值是符合预期的
     * （他要的是"恢复可用"，不是"空白"）。
     */
    fun seedIfEmpty(context: Context, settings: SettingsRepository) {
        try {
            val json = context.assets.open(FILE_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)

            val hasAnything = LangId.entries.any { lang ->
                val cfg = settings.configFor(lang)
                listOf(cfg.chat, cfg.asr, cfg.tts, cfg.realtime)
                    .any { it.baseUrl.isNotBlank() || it.apiKey.isNotBlank() }
            }
            if (hasAnything) return

            val groups = root.optJSONObject("groups") ?: return
            // 预置配置对**所有语言**生效：两门语言共用同一套网关是最常见的用法
            for (lang in LangId.entries) {
                for (group in SettingsRepository.Group.entries) {
                    val o = groups.optJSONObject(group.name.lowercase()) ?: continue
                    settings.update(
                        lang,
                        group,
                        ProviderConfig(
                            baseUrl = o.optString("baseUrl", ""),
                            apiKey = o.optString("apiKey", ""),
                            model = o.optString("model", ""),
                            voice = o.optString("voice", ""),
                            enabled = o.optBoolean("enabled", false)
                        )
                    )
                }
            }
            root.optString("activeLang", "").takeIf { it.isNotEmpty() }?.let { tag ->
                settings.setActiveLang(LangId.fromTag(tag))
            }
        } catch (e: Exception) {
            // 预置失败不影响启动：App 会正常跑在"未配置 → 本地模拟"状态
        }
    }

    private const val FILE_NAME = "preset_config.json"
}
