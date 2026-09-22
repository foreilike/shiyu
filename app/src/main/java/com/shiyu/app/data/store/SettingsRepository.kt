package com.shiyu.app.data.store

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.AppSettings
import com.shiyu.app.data.model.LanguageConfig
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.model.RealtimeOptions
import com.shiyu.app.data.secret.SecretStore
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

    // @Volatile：Provider 可能在 IO 线程读它，UI 线程写它 —— 不加的话会读到过期配置
    @Volatile private var cached: AppSettings? = null

    /** endpoint 组 key */
    enum class Group { CHAT, ASR, TTS, REALTIME }

    @Synchronized
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

    @Synchronized
    fun save(settings: AppSettings) {
        cached = settings
        store.write(toJson(settings))
    }

    /**
     * 修改后立即落盘。
     *
     * **必须同步**：它是 load→copy→save 的读改写序列，两个线程并发会整体覆盖 ——
     * 一次写会把另一次的改动悄悄抹掉（配置静默丢失）。
     */
    @Synchronized
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
    @Synchronized
    fun updateRealtimeOptions(lang: LangId, options: RealtimeOptions) {
        val settings = load()
        val current = settings.langs[lang] ?: LanguageConfig()
        val newLangs = settings.langs.toMutableMap()
        newLangs[lang] = current.copy(realtimeOptions = options)
        save(settings.copy(langs = newLangs))
    }

    fun realtimeOptionsFor(lang: LangId): RealtimeOptions =
        configFor(lang).realtimeOptions

    @Synchronized
    fun setActiveLang(lang: LangId) {
        val settings = load()
        save(settings.copy(activeLang = lang))
    }

    fun activeLang(): LangId = load().activeLang

    /** 当前语言选中的词书 id；未设过则返回空串（调用方回退到 books() 第一本）。 */
    fun activeBook(lang: LangId): String = load().activeBooks[lang] ?: ""

    fun setActiveBook(lang: LangId, bookId: String) {
        val settings = load()
        save(settings.copy(activeBooks = settings.activeBooks + (lang to bookId)))
    }

    fun autoPlayTts(): Boolean = load().autoPlayTts

    @Synchronized
    fun setAutoPlayTts(value: Boolean) {
        val settings = load()
        save(settings.copy(autoPlayTts = value))
    }

    /** 每日目标分钟数（我的页可改），越界值会被钳到 [MIN_GOAL_MINUTES]..[MAX_GOAL_MINUTES] */
    fun dailyGoalMinutes(): Int = load().dailyGoalMinutes

    @Synchronized
    fun setDailyGoalMinutes(minutes: Int) {
        val settings = load()
        save(settings.copy(dailyGoalMinutes = clampGoalMinutes(minutes)))
    }

    /** 实时语音是否显示中文翻译 */
    fun realtimeTranslate(): Boolean = load().realtimeTranslate

    @Synchronized
    fun setRealtimeTranslate(value: Boolean) {
        save(load().copy(realtimeTranslate = value))
    }

    /** 实时语音是否用「按住说话」 */
    fun voicePushToTalk(): Boolean = load().voicePushToTalk

    @Synchronized
    fun setVoicePushToTalk(value: Boolean) {
        save(load().copy(voicePushToTalk = value))
    }

    /** 昵称，未设置时为空串 */
    fun nickname(): String = load().nickname

    /**
     * 设置昵称。前后空白会被去掉、长度截到 [MAX_NICKNAME_LENGTH]；
     * 传空串等于「清空，恢复默认称呼」。
     */
    @Synchronized
    fun setNickname(value: String) {
        val settings = load()
        save(settings.copy(nickname = value.trim().take(MAX_NICKNAME_LENGTH)))
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
    @Synchronized
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
        // 后加字段：老文件没有 nickname，按空串处理（UI 会用占位名兜底）
        val nickname = root.optString("nickname", "").trim().take(MAX_NICKNAME_LENGTH)
        val realtimeTranslate = root.optBoolean("realtimeTranslate", false)
        val voicePushToTalk = root.optBoolean("voicePushToTalk", false)
        val dailyGoalMinutes = clampGoalMinutes(
            if (root.has("dailyGoalMinutes")) {
                root.optInt("dailyGoalMinutes", AppSettings.DEFAULT_DAILY_GOAL_MINUTES)
            } else {
                AppSettings.DEFAULT_DAILY_GOAL_MINUTES
            }
        )

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
        // 后加字段：每语言当前词书。老文件无此键 → 空 map（UI 回退默认书）。
        val activeBooks = mutableMapOf<LangId, String>()
        root.optJSONObject("activeBooks")?.let { obj ->
            for (lang in LangId.values()) {
                val id = obj.optString(lang.tag, "")
                if (id.isNotEmpty()) activeBooks[lang] = id
            }
        }
        return AppSettings(
            version = if (version >= 1) version else 1,
            activeLang = activeLang,
            autoPlayTts = autoPlayTts,
            nickname = nickname,
            dailyGoalMinutes = dailyGoalMinutes,
            realtimeTranslate = realtimeTranslate,
            voicePushToTalk = voicePushToTalk,
            activeBooks = activeBooks,
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
        root.put("nickname", settings.nickname)
        root.put("dailyGoalMinutes", settings.dailyGoalMinutes)
        root.put("realtimeTranslate", settings.realtimeTranslate)
        root.put("voicePushToTalk", settings.voicePushToTalk)
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
        val books = JSONObject()
        for ((lang, id) in settings.activeBooks) books.put(lang.tag, id)
        root.put("activeBooks", books)
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

    private fun clampGoalMinutes(value: Int): Int =
        value.coerceIn(MIN_GOAL_MINUTES, MAX_GOAL_MINUTES)

    companion object {
        /** 昵称长度上限，超出即截断（首页问候语与头像只需要短名） */
        const val MAX_NICKNAME_LENGTH = 12

        /** 每日目标的合法区间：太短没意义，太长会让进度环永远填不满 */
        const val MIN_GOAL_MINUTES = 5
        const val MAX_GOAL_MINUTES = 180
    }
}
