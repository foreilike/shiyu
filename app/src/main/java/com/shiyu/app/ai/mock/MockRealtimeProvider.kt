package com.shiyu.app.ai.mock

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.RealtimeVoiceProvider
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.MockReplies

/**
 * Mock 实时语音 Provider：未配置 REALTIME 凭证时降级。
 * 行为：连接后立即推一条预置转写（MockReplies.stt）并结束，不发音频——
 * 让「实时模式」入口在无凭证时也能完整走一遍 UI 流程（对应 V3 离线可跑）。
 */
class MockRealtimeProvider(private val lang: LangId) : RealtimeVoiceProvider {

    private var listener: RealtimeVoiceProvider.Listener? = null

    override fun setListener(listener: RealtimeVoiceProvider.Listener?) {
        this.listener = listener
    }

    override fun connect(instructions: String) {
        listener?.onConnected()
        listener?.onUserSpeechStopped()
        listener?.onTranscriptDelta(MockReplies.stt(lang))
        listener?.onResponseDone()
    }

    override fun sendAudio(pcm: ByteArray) {
        // Mock 不推流：静音门控已在上游，这里无操作
    }

    /**
     * 打字输入在 Mock 下也要有回应。
     *
     * 这条一开始漏了：接口里 `sendUserText` 有空的默认实现，Mock 没覆写，
     * 于是「没配凭证时打字发出去石沉大海」—— 而 Mock 模式的整个意义就是
     * 让没配凭证的人也能把流程走完（V3）。音频那边 Mock 是有回应的，
     * 文字这边不能例外，否则同一个页面两种输入行为不一致，很莫名其妙。
     */
    override fun sendUserText(text: String) {
        listener?.onUserSpeechStopped()
        listener?.onTranscriptDelta(MockReplies.stt(lang))
        listener?.onResponseDone()
    }

    override fun commit() {}
    override fun cancelResponse() {}

    override fun close() {
        listener?.onClosed()
    }
}
