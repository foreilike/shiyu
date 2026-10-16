package com.shiyu.app.data.asset

import com.shiyu.app.core.lang.LangId
import com.shiyu.app.data.store.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PresetConfig.parseLangOverrides` —— 预置里「按语言覆盖」的解析（§17.21.13）。
 *
 * **守的不变量**：预置里写的覆盖只能作用于**它自己声明的那门语言**。
 * 产品要求是「只有日语语音转文字走火山通路，英语保持原样」，
 * 而通用段 `groups` 是所有语言共享的 —— 一旦覆盖跨语言外溢，
 * 就会把英语的配置也一起改掉，这是看不见的回归。
 */
class PresetConfigLangOverrideTest {

    private val sample = """
    {
      "groups": { "chat": { "baseUrl": "https://x/v1" } },
      "langGroups": {
        "ja": { "asr": {
          "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
          "apiKey": "k", "model": "doubao-seed-2-0-mini-260428", "enabled": true
        } }
      }
    }
    """.trimIndent()

    @Test
    fun parses_language_override() {
        val list = PresetConfig.parseLangOverrides(sample)
        assertEquals(1, list.size)
        val o = list[0]
        assertEquals(LangId.JA, o.lang)
        assertEquals(SettingsRepository.Group.ASR, o.group)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", o.config.baseUrl)
        assertEquals("doubao-seed-2-0-mini-260428", o.config.model)
        assertTrue(o.config.enabled)
    }

    @Test
    fun override_does_not_leak_to_other_languages() {
        // 核心断言：没有 en 段落，就绝不能产生任何英语语言的覆盖
        val list = PresetConfig.parseLangOverrides(sample)
        assertTrue("英语不应被这条预置影响",
            list.none { it.lang == LangId.EN })
    }

    @Test
    fun explicit_english_override_is_honoured() {
        val json = """{"langGroups":{"en":{"asr":{"baseUrl":"https://y/v1"}}}}"""
        val list = PresetConfig.parseLangOverrides(json)
        assertEquals(1, list.size)
        assertEquals(LangId.EN, list[0].lang)
    }

    @Test
    fun unknown_language_and_group_are_skipped() {
        val json = """
        {"langGroups":{
          "de":{"asr":{"baseUrl":"https://z"}},
          "ja":{"nope":{"baseUrl":"https://z"}}
        }}
        """.trimIndent()
        assertTrue(PresetConfig.parseLangOverrides(json).isEmpty())
    }

    @Test
    fun missing_or_broken_section_gives_empty_list() {
        assertTrue(PresetConfig.parseLangOverrides("{}").isEmpty())
        assertTrue(PresetConfig.parseLangOverrides("not json").isEmpty())
        assertTrue(PresetConfig.parseLangOverrides("""{"groups":{}}""").isEmpty())
    }

    @Test
    fun group_name_is_matched_case_insensitively() {
        val json = """{"langGroups":{"ja":{"ASR":{"baseUrl":"https://a"}}}}"""
        val list = PresetConfig.parseLangOverrides(json)
        assertEquals(1, list.size)
        assertEquals(SettingsRepository.Group.ASR, list[0].group)
    }

    @Test
    fun real_preset_asset_only_overrides_japanese_asr() {
        // 直接读真实 assets 文件，防止以后有人手滑加了 en 段落
        val text = java.io.File("src/main/assets/preset_config.example.json").readText()
        val list = PresetConfig.parseLangOverrides(text)
        assertEquals("真实预置里应只有日语 asr 一条覆盖", 1, list.size)
        assertEquals(LangId.JA, list[0].lang)
        assertEquals(SettingsRepository.Group.ASR, list[0].group)
        assertTrue("日语 asr 应指向火山方舟",
            list[0].config.baseUrl.contains("volces.com"))
        assertTrue("必须启用，否则等于没配", list[0].config.enabled)
    }
}
