package com.shiyu.app.ai.openai

import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpRaw
import com.shiyu.app.core.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 实现的传输层：把 HttpCall 发出去。
 * 连接层异常统一映射为 ioError（"timeout" / 其它），由 HttpErrors 分类。
 * 不打印任何请求头/请求体日志，避免 Key 泄漏（§6.1 / §6.2）。
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient()
) : HttpTransport {

    override suspend fun execute(call: HttpCall): HttpRaw =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(call.url)
                for ((key, value) in call.headers) {
                    builder.addHeader(key, value)
                }
                val body = when {
                    call.multipart != null -> {
                        val mf = call.multipart
                        val b = okhttp3.MultipartBody.Builder()
                            .setType(okhttp3.MultipartBody.FORM)
                        for ((key, value) in call.formFields) {
                            b.addFormDataPart(key, value)
                        }
                        b.addFormDataPart(
                            mf.fieldName,
                            mf.fileName,
                            mf.bytes.toRequestBody(mf.mimeType.toMediaType())
                        )
                        b.build()
                    }
                    call.jsonBody != null -> call.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                    else -> "".toRequestBody(null)
                }
                builder.method(call.method, body)
                client.newCall(builder.build()).execute().use { response ->
                    // **必须限长**：原先直接 body.bytes() 全量读入内存。
                    // 服务端（或被劫持的 200 错误页）返回几百 MB 的 body 就会 OOM 崩进程；
                    // TTS 的音频体本来就没有天然上限。
                    // 超限按 BadResponse 处理，而不是把整个 App 拖死。
                    val declared = response.body?.contentLength() ?: -1L
                    if (declared > MAX_BODY_BYTES) {
                        return@use HttpRaw(
                            code = response.code, body = "", headers = emptyMap(),
                            ioError = null, binary = ByteArray(0)
                        )
                    }
                    val bytes = response.body?.source()?.let { src ->
                        src.request(MAX_BODY_BYTES + 1)
                        if (src.buffer.size > MAX_BODY_BYTES) null else src.buffer.snapshot().toByteArray()
                    }
                    HttpRaw(
                        code = response.code,
                        body = bytes?.toString(Charsets.UTF_8) ?: "",
                        // toMultimap 的 key 是非空 String，原先的 if (k != null) 恒为真
                        // （编译器一直有 "Condition is always true" 警告），去掉这层死判断
                        headers = response.headers.toMultimap()
                            .entries.associate { (k, v) -> k to (v.firstOrNull() ?: "") },
                        ioError = null,
                        binary = bytes
                    )
                }
            } catch (e: SocketTimeoutException) {
                HttpRaw(code = 0, body = "", ioError = "timeout")
            } catch (e: IOException) {
                HttpRaw(code = 0, body = "", ioError = e.message ?: "connect failed")
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** 单次响应体上限：超过就按坏响应处理，别把内存打爆（TTS 音频体没有天然上限） */
        private const val MAX_BODY_BYTES = 32L * 1024L * 1024L
    }

}
