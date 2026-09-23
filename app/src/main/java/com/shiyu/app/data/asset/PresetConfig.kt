package com.shiyu.app.data.asset

import android.content.Context
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.store.SettingsRepository
import org.json.JSONObject
import java.io.File

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

    /**
     * 预置组的版本号。**改了 [FILE_NAME] 里任何组的值（尤其 tts）就 +1**：
     * 老机器下次启动会把这些组强制同步过去。
     * v2：新增 tts 组（stepfun `/audio/speech`），修复日语朗读在国内网络静默失败。
     */
    private const val PRESET_VERSION = 2
    private const val VERSION_FILE = "preset_seed.version"

    /**
     * 应用预置迁移（每次启动都跑，幂等）。
     *
     * - 本地版本 < [PRESET_VERSION]：把预置里定义的组**强制同步**到本地（覆盖），推送修正配置。
     *   这就是已装机器拿到新增 tts 组的路径 —— [seedIfEmpty] 只在「一份配置都没有」时才动，
     *   老机器早 seed 过 chat/realtime，光靠它 tts 永远进不去。
     * - 版本相同：只补**本地空着**的组，绝不覆盖用户已填 / 已改的配置。
     *
     * 版本标记落在 `filesDir`（随卸载才清），跟加密的设置文件分开，读写都不影响学习进度。
     */
    fun applyPresetMigrations(context: Context, settings: SettingsRepository) {
        val versionFile = File(context.filesDir, VERSION_FILE)
        val localVersion = try {
            versionFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        } ?: 0
        val force = localVersion < PRESET_VERSION
        try {
            val json = context.assets.open(FILE_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val groups = JSONObject(json).optJSONObject("groups") ?: return
            for (lang in LangId.entries) {
                for (group in SettingsRepository.Group.entries) {
                    val o = groups.optJSONObject(group.name.lowercase()) ?: continue
                    // 同版本时只补空组；版本升级时强制覆盖，把修正后的配置推下去
                    if (!force && settings.groupFor(lang, group).baseUrl.isNotBlank()) continue
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
        } catch (e: Exception) {
            return // 迁移失败不写版本，下次启动再试；不影响启动
        }
        // 成功应用后才记下版本
        try {
            versionFile.writeText(PRESET_VERSION.toString())
        } catch (e: Exception) {
            // 写标记失败无妨：下次启动最多再强制同步一次（幂等）
        }
    }
}
