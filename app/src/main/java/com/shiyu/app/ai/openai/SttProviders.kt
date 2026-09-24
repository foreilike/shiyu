package com.shiyu.app.ai.openai

import com.shiyu.app.ai.SpeechToTextProvider
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
     * - host 指向 stepfun → stepfun 原生协议（`/audio/asr/sse`，JSON + base64 + SSE）
     * - 其它 → Whisper 兼容（`/audio/transcriptions`，multipart）
     */
    fun create(config: ProviderConfig, transport: HttpTransport): SpeechToTextProvider =
        if (StepfunAsrWire.isStepfunHost(config.baseUrl)) {
            StepfunSttProvider(config, transport)
        } else {
            OpenAiSttProvider(config, transport)
        }
}
