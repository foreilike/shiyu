package com.langpartner.app.ai

import com.langpartner.app.ai.mock.MockChatProvider
import com.langpartner.app.ai.mock.MockRealtimeProvider
import com.langpartner.app.ai.mock.MockSttProvider
import com.langpartner.app.ai.mock.MockTtsProvider
import com.langpartner.app.ai.openai.OkHttpTransport
import com.langpartner.app.ai.openai.OpenAiChatProvider
import com.langpartner.app.ai.openai.OpenAiSttProvider
import com.langpartner.app.ai.openai.OpenAiTtsProvider
import com.langpartner.app.ai.openai.StepfunRealtimeProvider
import com.langpartner.app.core.lang.LangId
import com.langpartner.app.core.lang.MockReplies
import com.langpartner.app.core.net.HttpTransport
import com.langpartner.app.data.model.ProviderConfig
import com.langpartner.app.data.store.SettingsRepository

/**
 * 四组能力齐全的 Provider 工厂（每个语言一个实例）。
 */
class DefaultAiProviderFactory(
    private val chatProvider: ChatProvider,
    private val sttProvider: SpeechToTextProvider,
    private val ttsProvider: TextToSpeechProvider,
    private val realtimeProvider: RealtimeVoiceProvider
) : AiProviderFactory {
    override fun chat(): ChatProvider = chatProvider
    override fun stt(): SpeechToTextProvider = sttProvider
    override fun tts(): TextToSpeechProvider = ttsProvider
    override fun realtime(instructions: String): RealtimeVoiceProvider = realtimeProvider
}

/**
 * 按语言 + 配置返回真实/Mock 实现（§M3-1 / §M3-2）。
 * 某组未配置（未启用或无 baseUrl）→ 对应 Mock 实现；不得抛异常、不得崩溃。
 * 本类不出现语言专有分支：语言差异由 core/lang 的 MockReplies 提供。
 */
class AiProviderRegistry(
    private val settings: SettingsRepository,
    private val transport: HttpTransport = OkHttpTransport()
) {

    fun factory(lang: LangId): AiProviderFactory {
        val langConfig = settings.configFor(lang)
        val realtimeProvider = if (langConfig.realtime.isUsable()) {
            StepfunRealtimeProvider(
                config = langConfig.realtime,
                options = langConfig.realtimeOptions
            )
        } else {
            MockRealtimeProvider(lang)
        }
        return DefaultAiProviderFactory(
            chatProvider = if (langConfig.chat.isUsable()) {
                OpenAiChatProvider(langConfig.chat, transport)
            } else {
                MockChatProvider(MockReplies.chat(lang))
            },
            sttProvider = if (langConfig.asr.isUsable()) {
                OpenAiSttProvider(langConfig.asr, transport)
            } else {
                MockSttProvider(MockReplies.stt(lang))
            },
            ttsProvider = if (langConfig.tts.isUsable()) {
                OpenAiTtsProvider(langConfig.tts, transport)
            } else {
                MockTtsProvider()
            },
            realtimeProvider = realtimeProvider
        )
    }

    /** 当前语言的对话能力是否真实可用（否则是 Mock 回复） */
    fun isChatEnabled(lang: LangId): Boolean = settings.configFor(lang).chat.isUsable()

    /** 当前语言的语音识别是否可用；不可用时 UI 应提示走文字输入 */
    fun isSttEnabled(lang: LangId): Boolean = settings.configFor(lang).asr.isUsable()

    /** 当前语言的语音合成是否可用；不可用时静默跳过朗读 */
    fun isTtsEnabled(lang: LangId): Boolean = settings.configFor(lang).tts.isUsable()

    /** 实时语音是否已配置真实服务；否则实时页走 Mock 降级 */
    fun isRealtimeEnabled(lang: LangId): Boolean = settings.configFor(lang).realtime.isUsable()

    private fun ProviderConfig.isUsable(): Boolean = enabled && baseUrl.isNotBlank()
}
