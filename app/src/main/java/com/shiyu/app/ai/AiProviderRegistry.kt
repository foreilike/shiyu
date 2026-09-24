package com.shiyu.app.ai

import com.shiyu.app.ai.mock.MockChatProvider
import com.shiyu.app.ai.mock.MockRealtimeProvider
import com.shiyu.app.ai.mock.MockSttProvider
import com.shiyu.app.ai.mock.MockTtsProvider
import com.shiyu.app.ai.openai.OkHttpTransport
import com.shiyu.app.ai.openai.OpenAiChatProvider
import com.shiyu.app.ai.openai.OpenAiSttProvider
import com.shiyu.app.ai.openai.OpenAiTtsProvider
import com.shiyu.app.ai.openai.StepfunRealtimeProvider
import com.shiyu.app.ai.openai.StepfunSttProvider
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.MockReplies
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.StepfunAsrWire
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.store.SettingsRepository

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
                options = langConfig.realtimeOptions,
                // 用 sttLanguage 而非 tag：前者语义就是「ASR 接口的语言参数」。
                // ⚠️ stepfun realtime 会忽略这个字段（§17.21.1），保留只为协议兼容
                inputLanguage = lang.sttLanguage
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
                buildSttProvider(langConfig.asr)
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

    /**
     * ASR 组的两套协议择一（§17.21.5）：
     *
     * - 默认走 **Whisper 兼容**的 `POST /audio/transcriptions` —— 多数服务都实现这个；
     * - 但 **stepfun 没有这个端点**（实测 404），它要用自己的 `POST /audio/asr/sse`
     *   （JSON + base64，响应是 SSE）。所以 host 指向 stepfun 时改走 [StepfunSttProvider]。
     *
     * 按 host 判断而不是加配置项，是为了不动 `AppSettings` 的持久化结构（省一次迁移）。
     */
    private fun buildSttProvider(config: ProviderConfig): SpeechToTextProvider =
        if (StepfunAsrWire.isStepfunHost(config.baseUrl)) {
            StepfunSttProvider(config, transport)
        } else {
            OpenAiSttProvider(config, transport)
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
