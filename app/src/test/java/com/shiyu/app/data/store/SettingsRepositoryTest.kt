package com.shiyu.app.data.store

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.secret.SecretStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SettingsRepository（§5.10 / V19）：6 组配置互不覆盖 + 局部自愈 */
class SettingsRepositoryTest {

    private class InMemoryStore(var plain: String? = null) : SecretStore {
        override fun read(): String? = plain
        override fun write(plain: String) {
            this.plain = plain
        }
        override fun clear() {
            plain = null
        }
    }

    @Test
    fun empty_store_gives_defaults() {
        val repo = SettingsRepository(InMemoryStore())
        val settings = repo.load()
        assertEquals(LangId.JA, settings.activeLang)
        assertTrue(settings.autoPlayTts)
        for (lang in LangId.values()) {
            val cfg = settings.langs[lang]!!
            for (group in listOf(cfg.chat, cfg.asr, cfg.tts)) {
                assertFalse(group.enabled)
                assertEquals("", group.baseUrl)
                assertEquals("", group.apiKey)
                assertEquals("", group.model)
                assertEquals("", group.voice)
            }
        }
    }

    @Test
    fun round_trip_six_groups() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.update(
            LangId.JA, SettingsRepository.Group.CHAT,
            ProviderConfig("https://a/v1", "sk-ja-chat", "m1", enabled = true)
        )
        repo.update(
            LangId.JA, SettingsRepository.Group.TTS,
            ProviderConfig("https://a/v1", "sk-ja-tts", "m2", voice = "alloy", enabled = true)
        )
        repo.update(
            LangId.EN, SettingsRepository.Group.ASR,
            ProviderConfig("https://b/v1", "sk-en-asr", "m3", enabled = true)
        )
        // 重新加载（模拟重启）
        val repo2 = SettingsRepository(store)
        val cfg = repo2.configFor(LangId.JA)
        assertEquals("sk-ja-chat", cfg.chat.apiKey)
        assertTrue(cfg.chat.enabled)
        assertEquals("alloy", cfg.tts.voice)
        assertEquals("sk-en-asr", repo2.configFor(LangId.EN).asr.apiKey)
        // JSON 结构与 §5.10 一致
        val root = JSONObject(store.plain!!)
        assertEquals(2, root.getInt("version"))
        assertEquals("ja", root.getString("activeLang"))
        val langs = root.getJSONObject("langs")
        assertTrue(langs.has("ja") && langs.has("en"))
        assertEquals("sk-ja-chat", langs.getJSONObject("ja").getJSONObject("chat").getString("apiKey"))
        assertEquals("sk-en-asr", langs.getJSONObject("en").getJSONObject("asr").getString("apiKey"))
    }

    @Test
    fun update_isolated_per_language_and_group() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.update(
            LangId.EN, SettingsRepository.Group.CHAT,
            ProviderConfig("https://en/v1", "sk-en", "m-en", enabled = true)
        )
        val ja = repo.configFor(LangId.JA)
        assertFalse(ja.chat.enabled)
        assertEquals("", ja.chat.apiKey)
        val en = repo.configFor(LangId.EN)
        assertEquals("sk-en", en.chat.apiKey)
        // 换 asr 不影响 chat
        repo.update(LangId.EN, SettingsRepository.Group.ASR, ProviderConfig("https://asr", "sk-asr", "m", enabled = true))
        assertEquals("sk-en", repo.configFor(LangId.EN).chat.apiKey)
        assertEquals("sk-asr", repo.configFor(LangId.EN).asr.apiKey)
    }

    @Test
    fun active_lang_invalid_falls_back_to_ja() {
        val store = InMemoryStore(
            """{"version":1,"activeLang":"fr","autoPlayTts":true,"langs":{}}"""
        )
        assertEquals(LangId.JA, SettingsRepository(store).activeLang())
    }

    @Test
    fun active_lang_valid_persisted() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.setActiveLang(LangId.EN)
        assertEquals(LangId.EN, SettingsRepository(store).activeLang())
    }

    @Test
    fun partial_heal_missing_en_lang() {
        val store = InMemoryStore(
            """
            {"version":1,"activeLang":"ja","autoPlayTts":true,
             "langs":{"ja":{"chat":{"baseUrl":"https://ja","apiKey":"sk-ja","model":"m","enabled":true},
                       "asr":{"baseUrl":"","apiKey":"","model":"","enabled":false},
                       "tts":{"baseUrl":"","apiKey":"","model":"","voice":"","enabled":false}}}}
            """.trimIndent()
        )
        val repo = SettingsRepository(store)
        val ja = repo.configFor(LangId.JA)
        assertEquals("sk-ja", ja.chat.apiKey)
        assertTrue(ja.chat.enabled)
        // 缺 en → 只补 en 默认，不动 ja
        val en = repo.configFor(LangId.EN)
        assertFalse(en.chat.enabled)
        assertEquals("", en.chat.apiKey)
    }

    @Test
    fun partial_heal_missing_field_only() {
        val store = InMemoryStore(
            """
            {"version":1,"activeLang":"ja","autoPlayTts":false,
             "langs":{"ja":{"chat":{"baseUrl":"https://ja"},
                            "asr":{},
                            "tts":{"baseUrl":"https://t","apiKey":"k","model":"m"}}}}
            """.trimIndent()
        )
        val repo = SettingsRepository(store)
        assertEquals("https://ja", repo.configFor(LangId.JA).chat.baseUrl)
        assertEquals("", repo.configFor(LangId.JA).chat.apiKey) // 缺 apiKey → 默认
        assertFalse(repo.configFor(LangId.JA).chat.enabled)     // 缺 enabled → 默认
        assertEquals("", repo.configFor(LangId.JA).tts.voice)   // 缺 voice → 默认
        assertFalse(repo.autoPlayTts())
    }

    @Test
    fun corrupt_json_returns_defaults() {
        val store = InMemoryStore("{{{not json")
        val repo = SettingsRepository(store)
        assertEquals(LangId.JA, repo.activeLang())
        assertFalse(repo.configFor(LangId.JA).chat.enabled)
    }

    @Test
    fun clear_deletes_store() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.setActiveLang(LangId.EN)
        repo.clear()
        assertTrue(store.plain == null)
        assertEquals(LangId.JA, repo.activeLang())
    }

    @Test
    fun auto_play_toggle_persists() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.setAutoPlayTts(false)
        assertFalse(SettingsRepository(store).autoPlayTts())
    }

    // ---------- REALTIME 组（version 2 新增）----------

    @Test
    fun realtime_group_round_trip_and_isolation() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.update(
            LangId.JA, SettingsRepository.Group.REALTIME,
            ProviderConfig(
                "wss://api.stepfun.com/v1/realtime", "sk-ja-rt", "step-audio-2-mini",
                voice = "wenrounansheng", enabled = true
            )
        )
        repo.updateRealtimeOptions(
            LangId.JA,
            com.shiyu.app.data.model.RealtimeOptions(
                serverVad = true, silenceDurationMs = 300, energyAwakenessThreshold = 2000, prefixPaddingMs = 400
            )
        )
        val repo2 = SettingsRepository(store)
        val rt = repo2.groupFor(LangId.JA, SettingsRepository.Group.REALTIME)
        assertEquals("wss://api.stepfun.com/v1/realtime", rt.baseUrl)
        assertEquals("sk-ja-rt", rt.apiKey)
        assertEquals("step-audio-2-mini", rt.model)
        assertEquals("wenrounansheng", rt.voice)
        assertTrue(rt.enabled)
        val opts = repo2.realtimeOptionsFor(LangId.JA)
        assertEquals(300, opts.silenceDurationMs)
        assertEquals(2000, opts.energyAwakenessThreshold)
        assertEquals(400, opts.prefixPaddingMs)
        assertTrue(opts.serverVad)
        // 不影响其它语言/其它组
        assertFalse(repo2.groupFor(LangId.EN, SettingsRepository.Group.REALTIME).enabled)
        assertFalse(repo2.configFor(LangId.JA).chat.enabled)
        // JSON 落盘结构
        val root = JSONObject(store.plain!!)
        val ja = root.getJSONObject("langs").getJSONObject("ja")
        assertEquals("sk-ja-rt", ja.getJSONObject("realtime").getString("apiKey"))
        assertEquals(300, ja.getJSONObject("realtimeOptions").getInt("silenceDurationMs"))
    }

    @Test
    fun version1_file_heals_realtime_fields() {
        // version 1 旧文件：没有 realtime / realtimeOptions → 默认值自愈，旧配置不动
        val store = InMemoryStore(
            """
            {"version":1,"activeLang":"ja","autoPlayTts":true,
             "langs":{"ja":{"chat":{"baseUrl":"https://ja","apiKey":"sk-ja","model":"m","enabled":true},
                            "asr":{"baseUrl":"","apiKey":"","model":"","enabled":false},
                            "tts":{"baseUrl":"","apiKey":"","model":"","voice":"","enabled":false}}}}
            """.trimIndent()
        )
        val repo = SettingsRepository(store)
        val ja = repo.configFor(LangId.JA)
        assertEquals("sk-ja", ja.chat.apiKey) // 旧配置保留
        assertFalse(ja.realtime.enabled)      // 新组默认
        assertEquals("", ja.realtime.baseUrl)
        val opts = repo.realtimeOptionsFor(LangId.JA)
        assertTrue(opts.serverVad)            // 默认开服务端 VAD
        assertEquals(100, opts.silenceDurationMs)
        assertEquals(2500, opts.energyAwakenessThreshold)
        assertEquals(500, opts.prefixPaddingMs)
    }
}
