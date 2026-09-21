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

    private val TAG = "ShiyuRealtime"

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
        // model 是官方要求的必填查询参数，设置页把它存在 model 字段、不在 baseUrl 里，
        // 必须在这里拼进 URL，否则握手会被拒。
        val endpoint = RealtimeProtocol.endpointWithModel(url, config.model)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                android.util.Log.i(TAG, "realtime connected: $endpoint")
                // 默认用女声：语伴形象是 yoyo / Emma，而另一个可选音色是男声
                val voice = config.voice.ifBlank { RealtimeProtocol.VOICE_FEMALE }
                android.util.Log.i(TAG, "-> session.update voice=$voice instructions=${instructions.take(120)}")
                webSocket.send(
                    RealtimeProtocol.sessionUpdate(
                        // step-audio-2-mini 要求在 instructions 末尾追加性别提示
                        instructions = RealtimeProtocol.withGenderInstruction(instructions, voice),
                        voice = voice,
                        serverVad = options.serverVad,
                        silenceDurationMs = options.silenceDurationMs,
                        energyAwakenessThreshold = options.energyAwakenessThreshold,
                        prefixPaddingMs = options.prefixPaddingMs
                    )
                )
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 全量记录服务端事件类型：排「连上了但没反应」时，
                // 「服务端到底说没说话」是第一要问的问题
                val type = try {
                    org.json.JSONObject(text).optString("type")
                } catch (e: Exception) {
                    "unparsable"
                }
                if (type == "error") {
                    android.util.Log.e(TAG, "<- ERROR $text")
                } else if (type == "session.updated" || type == "session.created") {
                    // 这条最关键：它回显服务端实际接受的会话配置。
                    // 如果没有 turn_detection，说明 server_vad 压根没生效。
                    android.util.Log.i(TAG, "<- $type ${text.take(700)}")
                } else if (type == "response.created") {
                    responseInFlight = true
                    android.util.Log.i(TAG, "<- $type")
                } else if (type.startsWith("response.audio.delta")) {
                    // 音频增量太密，不逐条打
                } else {
                    android.util.Log.i(TAG, "<- $type")
                }
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
                    is RealtimeProtocol.ServerEvent.ResponseStarted ->
                        listener?.onResponseStarted()
                    is RealtimeProtocol.ServerEvent.ResponseDone -> {
                        responseInFlight = false
                        listener?.onResponseDone()
                    }
                    is RealtimeProtocol.ServerEvent.ErrorEvent ->
                        // 带上服务端原文：笼统的"响应格式异常"没法排障
                        listener?.onError(
                            AiError.BadResponse(
                                listOfNotNull(event.message, event.code).joinToString(" / ")
                            )
                        )
                    else -> Unit // session.created / audio.done / unknown 等忽略
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val code = response?.code ?: 0
                android.util.Log.e(TAG, "realtime failure code=$code msg=${t.message}", t)
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
                android.util.Log.w(TAG, "realtime closed code=$code reason=$reason")
                listener?.onClosed()
            }
        })
    }

    private var framesSent = 0L

    /** 是否有响应正在生成（response.created → response.done 之间） */
    @Volatile private var responseInFlight = false

    override fun sendAudio(pcm: ByteArray) {
        if (!connected) return
        socket?.send(RealtimeProtocol.appendAudio(pcm))
        framesSent++
        // 每 600 帧（约 12 秒）报一次心跳。原先每秒一条，排查完就变成了噪音，
        // 会把真正要紧的服务端事件淹掉。
        if (framesSent % 600L == 0L) {
            android.util.Log.i(TAG, "-> ${framesSent} frames sent (${pcm.size}B each)")
        }
    }

    override fun commit() {
        if (connected) socket?.send(RealtimeProtocol.commit())
    }

    /**
     * 只有在真的有响应在飞时才发 cancel。
     * 少了这道判断，用户每次点「打断」都会让服务端回一条
     * `no ongoing response to cancel` 错误，界面上就浮现一句莫名其妙的
     * 「响应格式异常」——排查时极易被误当成连接问题。
     */
    override fun cancelResponse() {
        if (connected && responseInFlight) {
            responseInFlight = false
            socket?.send(RealtimeProtocol.responseCancel())
        }
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
