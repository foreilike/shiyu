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

    /** 重连用：记住最后一次的 instructions；用户主动关闭后不再重连 */
    private var lastInstructions: String? = null
    private var closedByUser = false
    private var retryCount = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectTask = Runnable {
        val text = lastInstructions ?: return@Runnable
        android.util.Log.i(TAG, "reconnecting attempt=${retryCount}")
        openSocket(text)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun setListener(listener: RealtimeVoiceProvider.Listener?) {
        this.listener = listener
    }

    override fun connect(instructions: String) {
        lastInstructions = instructions
        openSocket(instructions)
    }

    private fun openSocket(instructions: String) {
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
                retryCount = 0
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
                    responseStartedAt = System.currentTimeMillis()
                    firstAudioLogged = false
                    audioBytes = 0L
                    android.util.Log.i(TAG, "<- $type")
                } else if (type == "response.audio.delta") {
                    // 只记首个包和总量：首个包的时刻 = 用户实际感到的"多久才开口"，
                    // 总量 = 这一轮到底说了多长。逐条打会把日志淹掉。
                    audioBytes += text.length.toLong()
                    if (!firstAudioLogged) {
                        firstAudioLogged = true
                        android.util.Log.i(
                            TAG,
                            "<- first audio after ${System.currentTimeMillis() - responseStartedAt}ms"
                        )
                    }
                } else if (type == "response.audio.done") {
                    android.util.Log.i(
                        TAG,
                        "<- audio.done total=${System.currentTimeMillis() - responseStartedAt}ms b64=${audioBytes}B"
                    )
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
                // 认证/地址类错误重试也没用，立刻报错，别让用户白等三次退避
                if (code == 401 || code == 403 || code == 404) {
                    listener?.onError(
                        when (code) {
                            404 -> AiError.NotFound(code)
                            else -> AiError.Unauthorized(code)
                        }
                    )
                    return
                }
                // 其余错误一律交给重连处理，**这里不要再报错**：
                // 报了的话，重连期间界面会先弹「网络连接失败」，
                // 等重试彻底放弃时又被「重连失败」覆盖 —— 两条提示互相打架，
                // 而且用户会以为要自己去修网络，不知道 App 已经自动试过三次。
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                android.util.Log.w(TAG, "realtime closed code=$code reason=$reason")
                // 服务端正常挂断（用户结束对话时是我们自己关的，closedByUser 已置位）
                if (closedByUser) {
                    listener?.onClosed()
                } else {
                    scheduleReconnect()
                }
            }
        })
    }

    private var framesSent = 0L

    /** 是否有响应正在生成（response.created → response.done 之间） */
    @Volatile private var responseInFlight = false

    /** 延迟测量：response.created 的时刻 / 本轮是否已报过首个音频包 / 累计音频字节 */
    private var responseStartedAt = 0L
    private var firstAudioLogged = false
    private var audioBytes = 0L

    override fun updateInstructions(instructions: String) {
        if (!connected) return
        lastInstructions = instructions
        val voice = config.voice.ifBlank { RealtimeProtocol.VOICE_FEMALE }
        android.util.Log.i(TAG, "-> session.update(改写提示词) 尾部=${instructions.takeLast(80)}")
        socket?.send(
            RealtimeProtocol.sessionUpdate(
                instructions = RealtimeProtocol.withGenderInstruction(instructions, voice),
                voice = voice,
                serverVad = options.serverVad,
                silenceDurationMs = options.silenceDurationMs,
                energyAwakenessThreshold = options.energyAwakenessThreshold,
                prefixPaddingMs = options.prefixPaddingMs
            )
        )
    }

    override fun sendUserText(text: String) {
        if (!connected || text.isBlank()) return
        android.util.Log.i(TAG, "-> 打字输入 ${text.take(40)}")
        // 先建消息，再显式让模型回应 —— server_vad 只管语音，文字得自己触发
        socket?.send(RealtimeProtocol.createUserTextItem(text))
        socket?.send(RealtimeProtocol.responseCreate())
    }

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

    /**
     * 指数退避重连（1s / 2s / 4s）。
     *
     * 之前断开只回调 onClosed 就结束了，网络抖一下整轮对话就废了 ——
     * 而实时对话中途断掉，用户通常只想继续说，不想重开一轮。
     */
    private fun scheduleReconnect() {
        if (closedByUser) return
        if (retryCount >= MAX_RETRIES) {
            android.util.Log.w(TAG, "reconnect gave up after $retryCount attempts")
            listener?.onReconnectFailed()
            return
        }
        retryCount++
        listener?.onReconnecting(retryCount)
        mainHandler.removeCallbacks(reconnectTask)
        mainHandler.postDelayed(reconnectTask, BASE_RETRY_MS shl (retryCount - 1))
    }

    override fun close() {
        closedByUser = true
        mainHandler.removeCallbacks(reconnectTask)
        connected = false
        try {
            socket?.close(1000, "bye")
        } catch (e: Exception) {
            // 忽略
        }
        socket = null
    }

    private companion object {
        /** 重连退避：1s → 2s → 4s，最多三次 */
        const val MAX_RETRIES = 3
        const val BASE_RETRY_MS = 1000L
    }
}
