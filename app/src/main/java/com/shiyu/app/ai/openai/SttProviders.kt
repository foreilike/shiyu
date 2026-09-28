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
/**
 * ASR 组的协议种类（设置页据此显示标签，见 §17.21.13）。
 *
 * **标签和路由必须用同一个判据**：曾经设置页把协议名写死在布局里（"Whisper 兼容"），
 * 加了 stepfun 原生与火山方舟两套之后，标签就和实际行为脱节了 ——
 * 用户看着"Whisper 兼容"却连的是火山方舟。
 */
enum class SttProtocolKind {
    /** 火山方舟：两步（files 上传 → chat/completions） */
    ARK,

    /** 阶跃原生：`/audio/asr/sse` */
    STEPFUN,

    /** Whisper 兼容：`/audio/transcriptions` */
    WHISPER,

    /** 接口地址还没填 */
    UNSET
}

object SttProviders {

    /**
     * ASR 组按 baseUrl 的 host 择一，共三套协议：
     *
     * | host | 协议 |
     * |---|---|
     * | `*.volces.com`（火山方舟） | 两步：`/files` 上传 → `/chat/completions` 带 `input_audio` |
     * | `*.stepfun.com`（阶跃） | `/audio/asr/sse`，JSON + base64 + SSE |
     * | 其它 | Whisper 兼容 `/audio/transcriptions`，multipart |
     *
     * **判断只此一处**：注册表、设置页「测试连接」、以及设置页的协议标签都基于它。
     * 曾经设置页自己 new 了 `OpenAiSttProvider`，于是出现「录音能跑、测试连接却 404」。
     */
    fun create(config: ProviderConfig, transport: HttpTransport): SpeechToTextProvider =
        when (protocolKind(config.baseUrl)) {
            SttProtocolKind.ARK -> ArkSttProvider(config, transport)
            SttProtocolKind.STEPFUN -> StepfunSttProvider(config, transport)
            SttProtocolKind.WHISPER, SttProtocolKind.UNSET ->
                OpenAiSttProvider(config, transport)
        }

    /**
     * 按 baseUrl 判定协议种类。
     *
     * [create] 内部也调它，所以**标签不可能和实际走的协议不一致**。
     * 地址为空 → [SttProtocolKind.UNSET]（而不是猜一个），让 UI 显示"未配置"。
     */
    fun protocolKind(baseUrl: String): SttProtocolKind {
        val url = baseUrl.trim()
        return when {
            url.isEmpty() -> SttProtocolKind.UNSET
            ArkAsrWire.isArkHost(url) -> SttProtocolKind.ARK
            StepfunAsrWire.isStepfunHost(url) -> SttProtocolKind.STEPFUN
            else -> SttProtocolKind.WHISPER
        }
    }
}
