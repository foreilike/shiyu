package com.langpartner.app.core.lang

import com.langpartner.app.ai.AiResult
import com.langpartner.app.ai.AiProviderRegistry
import com.langpartner.app.ai.ChatMessage
import com.langpartner.app.ai.ChatRequest
import com.langpartner.app.ai.mock.MockChatProvider
import com.langpartner.app.ai.mock.MockSttProvider
import com.langpartner.app.ai.mock.MockTtsProvider
import com.langpartner.app.core.chat.ReplyParser
import com.langpartner.app.data.secret.SecretStore
import com.langpartner.app.data.store.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock 降级链路（§M2-6 / §M3-2，验收 V3 硬要求） */
class MockProviderTest {

    private class InMemoryStore(var plain: String? = null) : SecretStore {
        override fun read(): String? = plain
        override fun write(plain: String) {
            this.plain = plain
        }
        override fun clear() {
            plain = null
        }
    }

    private fun registry(): AiProviderRegistry =
        AiProviderRegistry(SettingsRepository(InMemoryStore()))

    @Test
    fun mock_replies_contain_all_three_sections() {
        val jaReply = MockReplies.chat(LangId.JA)
        assertTrue(jaReply.contains("[JA]"))
        assertTrue(jaReply.contains("[ZH]"))
        assertTrue(jaReply.contains("[FIX]"))
        assertTrue(jaReply.contains("(mock)"))
        val enReply = MockReplies.chat(LangId.EN)
        assertTrue(enReply.contains("[EN]"))
        assertTrue(enReply.contains("[ZH]"))
        assertTrue(enReply.contains("[FIX]"))
        assertTrue(enReply.contains("(mock)"))
    }

    @Test
    fun mock_chat_round_trip_ja() = runBlocking {
        val factory = registry().factory(LangId.JA)
        assertTrue(factory.chat() is MockChatProvider)
        val result = factory.chat().chat(
            ChatRequest("any", listOf(ChatMessage("system", "s"), ChatMessage("user", "こんにちは")))
        )
        assertTrue(result is AiResult.Ok)
        val parsed = ReplyParser.parse(LanguageRegistry.module(LangId.JA), (result as AiResult.Ok).value)
        assertTrue(parsed.body.isNotEmpty())
        assertTrue(parsed.body.contains("(mock)"))
        assertTrue(parsed.translation.isNotEmpty())
        assertEquals("无", parsed.fix)
    }

    @Test
    fun mock_chat_round_trip_en() = runBlocking {
        val factory = registry().factory(LangId.EN)
        val result = factory.chat().chat(
            ChatRequest("any", listOf(ChatMessage("system", "s"), ChatMessage("user", "hi")))
        )
        val value = (result as AiResult.Ok).value
        val parsed = ReplyParser.parse(LanguageRegistry.module(LangId.EN), value)
        assertTrue(parsed.body.isNotEmpty())
        assertTrue(parsed.translation.isNotEmpty())
        assertEquals("无", parsed.fix)
    }

    @Test
    fun unconfigured_providers_are_mocks() {
        val reg = registry()
        assertFalse(reg.isChatEnabled(LangId.JA))
        assertFalse(reg.isChatEnabled(LangId.EN))
        assertFalse(reg.isSttEnabled(LangId.JA))
        assertFalse(reg.isTtsEnabled(LangId.EN))
        val factory = reg.factory(LangId.JA)
        assertTrue(factory.chat() is MockChatProvider)
        assertTrue(factory.stt() is MockSttProvider)
        assertTrue(factory.tts() is MockTtsProvider)
    }

    @Test
    fun mock_stt_and_tts_run(): Unit = runBlocking {
        val factory = registry().factory(LangId.JA)
        val tmp = java.io.File.createTempFile("mock", ".m4a")
        val stt = factory.stt().transcribe(tmp, "ja")
        assertTrue(stt is AiResult.Ok)
        assertNotNull((stt as AiResult.Ok).value)
        val tts = factory.tts().synthesize("hello", null)
        assertTrue(tts is AiResult.Ok)
        // Mock TTS 返回空音频，调用方静默跳过
        assertTrue((tts as AiResult.Ok).value.isEmpty())
        tmp.delete()
    }

    @Test
    fun enabled_provider_becomes_real_openai_impl() {
        val store = InMemoryStore()
        val repo = SettingsRepository(store)
        repo.update(
            LangId.JA, SettingsRepository.Group.CHAT,
            com.langpartner.app.data.model.ProviderConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-test",
                model = "gpt-test",
                enabled = true
            )
        )
        val reg = AiProviderRegistry(repo)
        assertTrue(reg.isChatEnabled(LangId.JA))
        // 聊天走真实实现；未配置的识别/合成仍是 mock
        assertTrue(
            reg.factory(LangId.JA).chat() is com.langpartner.app.ai.openai.OpenAiChatProvider
        )
        assertFalse(reg.isSttEnabled(LangId.JA))
        assertFalse(reg.isChatEnabled(LangId.EN))
    }

    @Test
    fun mock_stt_canned_text_per_language() {
        assertTrue(MockReplies.stt(LangId.JA).isNotEmpty())
        assertTrue(MockReplies.stt(LangId.EN).isNotEmpty())
        assertFalse(MockReplies.stt(LangId.JA) == MockReplies.stt(LangId.EN))
    }
}
