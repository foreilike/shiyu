package com.langpartner.app.data.store

import com.langpartner.app.core.lang.LangId
import com.langpartner.app.data.model.AppSettings
import com.langpartner.app.data.model.LanguageConfig
import com.langpartner.app.data.model.ProviderConfig
import com.langpartner.app.data.model.RealtimeOptions
import com.langpartner.app.data.secret.SecretStore
import org.json.JSONException
import org.json.JSONObject

/**
 * 设置仓库（§5.10）。持有两种语言共 8 组 Provider 配置（chat/asr/tts/realtime × 2 语言），
 * JSON 解析/生成与默认值填充都在这里，可被 JVM 单测直接测试（注入内存版 SecretStore）。
 *
 * 局部自愈：langs 缺失 → 补全两个语言；langs.ja 缺失 → 只补 ja；
 * 某组缺 voice/enabled → 只补该字段；realtime / realtimeOptions 缺失 → 按默认值补（version 1 文件平滑升级）；
 * activeLang 非法 → 回退 ja。任一处的损坏都不影响其它语言/其它组的已存配置。
 */
class SettingsRepository(private val store: SecretStore) {

    private var cached: AppSettings? = null

    /** endpoint 组 key */
    enum class Group { CHAT, ASR, TTS, REALTIME }

    fun load(): AppSettings {
        cached?.let { return it }
        val plain = try {
            store.read()
        } catch (e: Exception) {
            null
        }
        val settings = if (plain.isNullOrEmpty()) {
            AppSettings()
        } else {
            parse(plain)
        }
        cached = settings
        return settings
    }

    fun save(settings: AppSettings) {
        cached = settings
        store.write(toJson(settings))
    }

    /** 修改后立即落盘 */
    fun update(lang: LangId, group: Group, config: ProviderConfig) {
        val settings = load()
        val current = settings.langs[lang] ?: LanguageConfig()
        val updated = when (group) {
            Group.CHAT -> current.copy(chat = config)
            Group.ASR -> current.copy(asr = config)
            Group.TTS -> current.copy(tts = config)
            Group.REALTIME -> current.copy(realtime = config)
        }
        val newLangs = settings.langs.toMutableMap()
        newLangs[lang] = updated
        save(settings.copy(langs = newLangs))
    }

    /** 实时语音附加参数（server_vad 等）修改后立即落盘 */
    fun updateRealtimeOptions(lang: LangId, options: RealtimeOptions) {
        val settings = load()
        val current = settings.langs[lang] ?: LanguageConfig()
        val newLangs = settings.langs.toMutableMap()
        newLangs[lang] = current.copy(realtimeOptions = options)
        save(settings.copy(langs = newLangs))
    }

    fun realtimeOptionsFor(lang: LangId): RealtimeOptions =
        configFor(lang).realtimeOptions

    fun setActiveLang(lang: LangId) {
        val settings = load()
        save(settings.copy(activeLang = lang))
    }

    fun activeLang(): LangId = load().activeLang

    fun autoPlayTts(): Boolean = load().autoPlayTts

    fun setAutoPlayTts(value: Boolean) {
        val settings = load()
        save(settings.copy(autoPlayTts = value))
    }

    fun configFor(lang: LangId): LanguageConfig = load().langs[lang] ?: LanguageConfig()

    fun groupFor(lang: LangId, group: Group): ProviderConfig {
        val langConfig = configFor(lang)
        return when (group) {
            Group.CHAT -> langConfig.chat
            Group.ASR -> langConfig.asr
            Group.TTS -> langConfig.tts
            Group.REALTIME -> langConfig.realtime
        }
    }

    /** 清空全部数据时调用：删除加密文件 */
    fun clear() {
        cached = null
        store.clear()
    }

    // ---------- JSON ----------

    private fun parse(plain: String): AppSettings {
        val root = try {
            JSONObject(plain)
        } catch (e: JSONException) {
            return AppSettings()
        }
        val version = root.optInt("version", 1)
        val activeLang = LangId.fromTag(root.optString("activeLang", LangId.JA.tag))
        val autoPlayTts = if (root.has("autoPlayTts")) root.optBoolean("autoPlayTts", true) else true

        val langs = mutableMapOf<LangId, LanguageConfig>()
        val langsObj = root.optJSONObject("langs")
        for (lang in LangId.values()) {
            val langObj = langsObj?.optJSONObject(lang.tag)
            langs[lang] = if (langObj == null) {
                LanguageConfig()
            } else {
                LanguageConfig(
                    chat = parseGroup(langObj, "chat", hasVoice = false),
                    asr = parseGroup(langObj, "asr", hasVoice = false),
                    tts = parseGroup(langObj, "tts", hasVoice = true),
                    realtime = parseGroup(langObj, "realtime", hasVoice = true),
                    realtimeOptions = parseRealtimeOptions(langObj)
                )
            }
        }
        return AppSettings(
            version = if (version >= 1) version else 1,
            activeLang = activeLang,
            autoPlayTts = autoPlayTts,
            langs = langs
        )
    }

    /** realtimeOptions 整体缺失 → 默认值；单字段缺失 → 只补该字段 */
    private fun parseRealtimeOptions(langObj: JSONObject): RealtimeOptions {
        val o = langObj.optJSONObject("realtimeOptions") ?: return RealtimeOptions()
        return RealtimeOptions(
            serverVad = o.optBoolean("serverVad", true),
            silenceDurationMs = o.optInt("silenceDurationMs", 100),
            energyAwakenessThreshold = o.optInt("energyAwakenessThreshold", 2500),
            prefixPaddingMs = o.optInt("prefixPaddingMs", 500)
        )
    }

    private fun parseGroup(langObj: JSONObject, key: String, hasVoice: Boolean): ProviderConfig {
        val o = langObj.optJSONObject(key) ?: return ProviderConfig()
        return ProviderConfig(
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            voice = if (hasVoice) o.optString("voice", "") else "",
            enabled = o.optBoolean("enabled", false)
        )
    }

    private fun toJson(settings: AppSettings): String {
        val root = JSONObject()
        root.put("version", settings.version)
        root.put("activeLang", settings.activeLang.tag)
        root.put("autoPlayTts", settings.autoPlayTts)
        val langs = JSONObject()
        for ((lang, langConfig) in settings.langs) {
            val langObj = JSONObject()
            langObj.put("chat", groupToJson(langConfig.chat, false))
            langObj.put("asr", groupToJson(langConfig.asr, false))
            langObj.put("tts", groupToJson(langConfig.tts, true))
            langObj.put("realtime", groupToJson(langConfig.realtime, true))
            val opts = JSONObject()
            opts.put("serverVad", langConfig.realtimeOptions.serverVad)
            opts.put("silenceDurationMs", langConfig.realtimeOptions.silenceDurationMs)
            opts.put("energyAwakenessThreshold", langConfig.realtimeOptions.energyAwakenessThreshold)
            opts.put("prefixPaddingMs", langConfig.realtimeOptions.prefixPaddingMs)
            langObj.put("realtimeOptions", opts)
            langs.put(lang.tag, langObj)
        }
        root.put("langs", langs)
        return root.toString()
    }

    private fun groupToJson(config: ProviderConfig, hasVoice: Boolean): JSONObject {
        val o = JSONObject()
        o.put("baseUrl", config.baseUrl)
        o.put("apiKey", config.apiKey)
        o.put("model", config.model)
        o.put("enabled", config.enabled)
        if (hasVoice) o.put("voice", config.voice)
        return o
    }
}
