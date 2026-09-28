package com.shiyu.app.ai.openai

import com.shiyu.app.ai.SpeechToTextProvider
import com.shiyu.app.core.net.ArkAsrWire
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.StepfunAsrWire
import com.shiyu.app.data.model.ProviderConfig

/**
 * 语音识别 Provider 的唯一入口（§17.21.5）。
 *
 * **存在的理由**：ASR 有两套协议，选择逻辑必须只有一处。
 * 之前注册表里择一、而设置页「测试连接」直接 new `OpenAiSttProvider`，
 * 于是填了 stepfun 之后 —— 实际录音能跑（走注册表），**测试连接却报 404**
 * （还在打 stepfun 没有的 `/audio/transcriptions`）。两处必须共用同一份判断。
 */
object SttProviders {

    /**
     * ASR 组按 baseUrl 的 host 择一，共三套协议：
     *
     * | host | 协议 | 说明 |
     * |---|---|---|
     * | `*.volces.com`（火山方舟） | 两步：`/files` 上传 → `/chat/completions` 带 `input_audio` | 大模型转写，**能用提示词指定输出文字体系**，对带口音的日语有效 |
     * | `*.stepfun.com`（阶跃） | `/audio/asr/sse`，JSON + base64 + SSE | stepfun 没有 OpenAI 的 `/audio/transcriptions`；但其 ASR **无法**纠正口音导致的语种误判 |
     * | 其它 | Whisper 兼容 `/audio/transcriptions`，multipart | Groq / OpenAI / 自建 faster-whisper 等都走这里 |
     *
     * **判断只此一处**：注册表与设置页「测试连接」都调这个函数。
     * 曾经设置页自己 new 了 `OpenAiSttProvider`，于是出现「录音能跑、测试连接却 404」。
     */
    fun create(config: ProviderConfig, transport: HttpTransport): SpeechToTextProvider =
        when {
            ArkAsrWire.isArkHost(config.baseUrl) -> ArkSttProvider(config, transport)
            StepfunAsrWire.isStepfunHost(config.baseUrl) -> StepfunSttProvider(config, transport)
            else -> OpenAiSttProvider(config, transport)
        }
}
