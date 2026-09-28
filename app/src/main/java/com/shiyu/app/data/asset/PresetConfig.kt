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
                    settings.update(lang, group, groupFrom(o))
                }
            }
            // 按语言覆盖（`langGroups.<tag>.<group>`），覆盖在通用组之后
            applyLangOverrides(root.optJSONObject(LANG_GROUPS), settings) { group ->
                groups.optJSONObject(group.name.lowercase())
            }
            root.optString("activeLang", "").takeIf { it.isNotEmpty() }?.let { tag ->
                settings.setActiveLang(LangId.fromTag(tag))
            }
        } catch (e: Exception) {
            // 预置失败不影响启动：App 会正常跑在"未配置 → 本地模拟"状态
        }
    }

    private const val FILE_NAME = "preset_config.json"

    /** 按语言覆盖的段落名：`langGroups.<语言tag>.<组名>` */
    private const val LANG_GROUPS = "langGroups"

    /** 从预设的组对象构造 `ProviderConfig` */
    private fun groupFrom(o: JSONObject): ProviderConfig = ProviderConfig(
        baseUrl = o.optString("baseUrl", ""),
        apiKey = o.optString("apiKey", ""),
        model = o.optString("model", ""),
        voice = o.optString("voice", ""),
        enabled = o.optBoolean("enabled", false)
    )

    /**
     * 应用 `langGroups.<语言tag>.<组名>` 的按语言覆盖（在通用 `groups` 之后执行，故能盖住它）。
     *
     * **为什么需要这层**：`groups` 对**所有语言**生效，而"只有日语走某条通路"这类需求
     * 用 `groups` 表达不出来 —— 直接用会把英语也一起改掉（详见 HANDOVER §17.21.13）。
     *
     * @param langGroups `langGroups` 节点，null 时什么都不做
     * @param allGroupsOf 取通用 `groups` 下某组配置的函数；按语言覆盖是**整组替换**，
     *   不是逐字段合并 —— 局部合并会出现"新地址配旧 Key"这种没人想要的状态
     */
    private fun applyLangOverrides(
        langGroups: JSONObject?,
        settings: SettingsRepository,
        allGroupsOf: (SettingsRepository.Group) -> JSONObject?
    ) {
        if (langGroups == null) return
        for (lang in LangId.entries) {
            val perLang = langGroups.optJSONObject(lang.tag) ?: continue
            for (group in SettingsRepository.Group.entries) {
                val o = perLang.optJSONObject(group.name.lowercase()) ?: continue
                // 该语言该组在通用段里没定义、且本身是空白时，别用预置值去"激活"它 ——
                // 用户要的是"这门语言不动"，而不是"顺手给它也配上"
                if (allGroupsOf(group) == null && settings.groupFor(lang, group).baseUrl.isBlank()) {
                    continue
                }
                settings.update(lang, group, groupFrom(o))
            }
        }
    }

    /**
     * 通用 `groups` 的版本号。**改了 [FILE_NAME] 里通用组的值就 +1**：
     * 老机器下次启动会把这些组**强制同步**过去。
     * v2：新增 tts 组（stepfun `/audio/speech`），修复日语朗读在国内网络静默失败。
     *
     * ⚠️ 注意 [PRESET_VERSION] 升级会**强制覆盖全部通用组**，用户在这些组里改过的
     * 音色等本地设置会一起被冲掉。所以「只动某一门语言的某一组」请改
     * [LANG_PRESET_VERSION] 而不是动这个 —— 见 §17.21.13 的教训。
     */
    private const val PRESET_VERSION = 2

    /**
     * 按语言覆盖（`langGroups`）的版本号，与 [PRESET_VERSION] **相互独立**。
     *
     * 之所以分开：v1.0.10 只需要让日语语音识别组改成火山方舟。
     * 若为此把 [PRESET_VERSION] 升到 3，`force` 会连带强制覆盖通用组 ——
     * 实测会把设备上用户自己选的实时语音音色（如 `jingdiannvsheng`）冲成空。
     * 单独一个版本号就能做到「只强制推这一项，其它一律不碰」。
     *
     * v1：`langGroups.ja.asr` 指向火山方舟（修日语字幕显示中文谐音）。
     */
    private const val LANG_PRESET_VERSION = 1
    private const val VERSION_FILE = "preset_seed.version"

    /** 按语言覆盖的版本标记，与 [VERSION_FILE] 分开存 */
    private const val LANG_VERSION_FILE = "preset_lang_seed.version"

    /** 一条按语言覆盖：`lang` 语言的 `group` 组用 `config` */
    data class LangOverride(
        val lang: LangId,
        val group: SettingsRepository.Group,
        val config: ProviderConfig
    )

    /**
     * 纯函数：从预置 JSON 文本解析出「按语言覆盖」清单（**不碰 Context，可单测**）。
     *
     * 抽出来是为了守住一个关键不变量：预置里出现的覆盖**只作用于它自己那门语言**，
     * 不能外溢到别的语言（用户要求「只有日语走火山通路，英语不动」，§17.21.13）。
     * 原先这块逻辑埋在 `Context` 里，测不了。
     *
     * 认不出的语言 tag、组名一律跳过，不抛异常。
     */
    fun parseLangOverrides(json: String): List<LangOverride> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val langGroups = root.optJSONObject(LANG_GROUPS) ?: return emptyList()
        val out = mutableListOf<LangOverride>()
        for (tag in langGroups.keys()) {
            val lang = LangId.entries.firstOrNull { it.tag == tag } ?: continue
            val perLang = langGroups.optJSONObject(tag) ?: continue
            for (name in perLang.keys()) {
                val group = SettingsRepository.Group.entries
                    .firstOrNull { it.name.lowercase() == name.lowercase() } ?: continue
                val o = perLang.optJSONObject(name) ?: continue
                out += LangOverride(lang, group, groupFrom(o))
            }
        }
        return out
    }

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

        // 按语言覆盖用**自己的**版本号：单独升级它不会连带强制覆盖通用组
        val langVersionFile = File(context.filesDir, LANG_VERSION_FILE)
        val localLangVersion = try {
            langVersionFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        } ?: 0
        val forceLang = localLangVersion < LANG_PRESET_VERSION

        try {
            val json = context.assets.open(FILE_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            val groups = root.optJSONObject("groups") ?: return
            for (lang in LangId.entries) {
                for (group in SettingsRepository.Group.entries) {
                    val o = groups.optJSONObject(group.name.lowercase()) ?: continue
                    // 同版本时只补空组；版本升级时强制覆盖，把修正后的配置推下去
                    if (!force && settings.groupFor(lang, group).baseUrl.isNotBlank()) continue
                    settings.update(lang, group, groupFrom(o))
                }
            }
        } catch (e: Exception) {
            return // 迁移失败不写版本，下次启动再试；不影响启动
        }
        // 通用组已落地（可能空转），按语言覆盖单独走自己的版本判断
        applyLangGroups(context, settings, forceLang)
        // 成功应用后才记下版本
        try {
            versionFile.writeText(PRESET_VERSION.toString())
        } catch (e: Exception) {
            // 写标记失败无妨：下次启动最多再强制同步一次（幂等）
        }
    }

    /** 按语言覆盖的落地；失败不写版本，下次启动重试 */
    private fun applyLangGroups(
        context: Context,
        settings: SettingsRepository,
        force: Boolean
    ) {
        try {
            val json = context.assets.open(FILE_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            val groups = root.optJSONObject("groups")
            val langGroups = root.optJSONObject(LANG_GROUPS) ?: return
            if (force) {
                applyLangOverrides(langGroups, settings) {
                    groups?.optJSONObject(it.name.lowercase())
                }
            } else {
                // 同版本：只给"这门语言这组还空着"的情况补上，不覆盖用户改动
                for (lang in LangId.entries) {
                    val perLang = langGroups.optJSONObject(lang.tag) ?: continue
                    for (group in SettingsRepository.Group.entries) {
                        val o = perLang.optJSONObject(group.name.lowercase()) ?: continue
                        if (settings.groupFor(lang, group).baseUrl.isNotBlank()) continue
                        if (groups?.optJSONObject(group.name.lowercase()) == null) continue
                        settings.update(lang, group, groupFrom(o))
                    }
                }
            }
        } catch (e: Exception) {
            return
        }
        try {
            File(context.filesDir, LANG_VERSION_FILE).writeText(LANG_PRESET_VERSION.toString())
        } catch (e: Exception) {
            // 同上：写标记失败最多导致下次幂等重放
        }
    }
}
