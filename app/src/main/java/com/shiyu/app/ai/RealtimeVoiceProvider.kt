package com.shiyu.app.ai

/**
 * 实时语音 Provider（stepfun realtime 协议）。
 * 与回合制三接口（chat/stt/tts）并列的第四种能力：一条 WebSocket 同时承载
 * 音频输入（pcm16）、音频输出（pcm16 delta）与转写文字流。
 */
interface RealtimeVoiceProvider {

    fun setListener(listener: Listener?)

    /** 建立 WebSocket 并发送 session.update；instructions 为当前场景 system prompt。失败经 onError 通知 */
    fun connect(instructions: String)

    /** 推送一段 pcm16 音频（客户端已做静音门控） */
    /**
     * 中途改写系统提示（重发 session.update）。
     * 目前用于「学习者要求换语言」——见 VoicePrompts 里为什么不能只靠提示词。
     * 未实现的服务端实现可以忽略。
     */
    fun updateInstructions(instructions: String) {}

    /** 打断后到新一轮响应开始之间丢弃残留音频（由实现方决定是否需要） */
    fun sendUserText(text: String) {}

    fun sendAudio(pcm: ByteArray)

    /** 非 server_vad 模式下手动提交音频缓冲 */
    fun commit()

    /** 打断当前回复（barge-in） */
    fun cancelResponse()

    fun close()

    interface Listener {
        fun onConnected()
        /** 服务端 VAD 判定用户开始说话（用于打断 AI 播放） */
        fun onUserSpeechStarted()
        fun onUserSpeechStopped()
        /** AI 语音增量（pcm16，16kHz 单声道） */
        fun onAudioDelta(pcm: ByteArray)
        /** AI 语音转写增量（边说边出的文字） */
        fun onTranscriptDelta(text: String)
        /** 用户语音转写完成 */
        fun onUserTranscript(text: String)
        /** 服务端开始新一轮响应（response.created）——用于关闭打断后的音频丢弃窗口 */
        fun onResponseStarted() {}

        /** 连接断了，正在第 [attempt] 次重连（1 起） */
        fun onReconnecting(attempt: Int) {}

        /** 重连次数用尽，本轮对话到此为止 */
        fun onReconnectFailed() {}

        fun onResponseDone()
        fun onError(error: AiError)
        fun onClosed()
    }
}
