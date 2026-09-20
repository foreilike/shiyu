package com.langpartner.app.ai.openai

import com.langpartner.app.core.net.HttpCall
import com.langpartner.app.core.net.HttpRaw
import com.langpartner.app.core.net.HttpTransport
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
                    val bytes = response.body?.bytes()
                    HttpRaw(
                        code = response.code,
                        body = bytes?.toString(Charsets.UTF_8) ?: "",
                        headers = response.headers.toMultimap()
                            .mapNotNull { (k, v) -> if (k != null) k to (v.firstOrNull() ?: "") else null }
                            .toMap(),
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
    }
}
