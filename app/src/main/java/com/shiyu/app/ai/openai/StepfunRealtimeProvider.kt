package com.shiyu.app.ai.openai

import com.shiyu.app.ai.AiError
import com.shiyu.app.ai.RealtimeVoiceProvider
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.core.realtime.RealtimeProtocol
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.model.RealtimeOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * stepfun realtime Provider（wss://api.stepfun.com/v1/realtime）。
 *
 * - 握手头 Authorization: Bearer <apiKey>
 * - 连上后立即发送 session.update（instructions / voice / server_vad 参数）
 * - 上行：input_audio_buffer.append（pcm16 Base64）
 * - 下行：response.audio.delta（pcm16）与 response.audio_transcript.delta（文字）
 * - 所有回调切回调用方线程的责任由 Listener 自行处理（UI 侧 post 到主线程）
 */
class StepfunRealtimeProvider(
    private val config: ProviderConfig,
    private val options: RealtimeOptions
) : RealtimeVoiceProvider {

    private var listener: RealtimeVoiceProvider.Listener? = null
    private var socket: WebSocket? = null
    private var connected = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun setListener(listener: RealtimeVoiceProvider.Listener?) {
        this.listener = listener
    }

    override fun connect(instructions: String) {
        val url = BaseUrlNormalizer.normalize(config.baseUrl) ?: run {
            listener?.onError(AiError.BadResponse("invalid realtime baseUrl"))
            return
        }
        if (config.apiKey.isBlank()) {
            listener?.onError(AiError.Unauthorized(401))
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                webSocket.send(
                    RealtimeProtocol.sessionUpdate(
                        instructions = instructions,
                        voice = config.voice.ifBlank { "wenrounansheng" },
                        serverVad = options.serverVad,
                        silenceDurationMs = options.silenceDurationMs,
                        energyAwakenessThreshold = options.energyAwakenessThreshold,
                        prefixPaddingMs = options.prefixPaddingMs
                    )
                )
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = RealtimeProtocol.parse(text)) {
                    is RealtimeProtocol.ServerEvent.SpeechStarted -> listener?.onUserSpeechStarted()
                    is RealtimeProtocol.ServerEvent.SpeechStopped -> listener?.onUserSpeechStopped()
                    is RealtimeProtocol.ServerEvent.AudioDelta -> {
                        if (event.pcm.isNotEmpty()) listener?.onAudioDelta(event.pcm)
                    }
                    is RealtimeProtocol.ServerEvent.TranscriptDelta ->
                        listener?.onTranscriptDelta(event.text)
                    is RealtimeProtocol.ServerEvent.UserTranscript ->
                        listener?.onUserTranscript(event.text)
                    is RealtimeProtocol.ServerEvent.ResponseDone -> listener?.onResponseDone()
                    is RealtimeProtocol.ServerEvent.ErrorEvent ->
                        listener?.onError(AiError.BadResponse(event.message))
                    else -> Unit // session.created / audio.done / unknown 等忽略
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val code = response?.code ?: 0
                listener?.onError(
                    when (code) {
                        401, 403 -> AiError.Unauthorized(code)
                        404 -> AiError.NotFound(code)
                        429 -> AiError.RateLimited(code, null)
                        in 500..599 -> AiError.ServerError(code, "")
                        else -> AiError.Network
                    }
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onClosed()
            }
        })
    }

    override fun sendAudio(pcm: ByteArray) {
        if (connected) socket?.send(RealtimeProtocol.appendAudio(pcm))
    }

    override fun commit() {
        if (connected) socket?.send(RealtimeProtocol.commit())
    }

    override fun cancelResponse() {
        if (connected) socket?.send(RealtimeProtocol.responseCancel())
    }

    override fun close() {
        connected = false
        try {
            socket?.close(1000, "bye")
        } catch (e: Exception) {
            // 忽略
        }
        socket = null
    }
}
