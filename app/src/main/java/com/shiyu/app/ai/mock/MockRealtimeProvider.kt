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

    override fun commit() {}
    override fun cancelResponse() {}

    override fun close() {
        listener?.onClosed()
    }
}
