package com.shiyu.app.ai.openai

import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.core.net.MultipartFile
import com.shiyu.app.core.net.HttpRaw
import com.shiyu.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SttProviders` 的协议择一（§17.21.13）。
 *
 * **守两条**：
 * 1. 三套协议按 host 正确分流（方舟 / 阶跃原生 / Whisper 兼容）；
 * 2. **协议标签与真实路由用的是同一个判据** ——
 *    曾经设置页把协议名写死在布局里（"Whisper 兼容"），加了方舟之后就脱节了，
 *    用户看着"Whisper 兼容"其实连的是火山方舟。
 */
class SttProvidersTest {

    private val transport = object : HttpTransport {
        override suspend fun execute(call: HttpCall): HttpRaw =
            HttpRaw(code = 200, body = "{}")
    }

    private fun cfg(baseUrl: String, model: String = "m") =
        ProviderConfig(baseUrl = baseUrl, apiKey = "k", model = model, enabled = true)

    /** 造一个真实存在的音频文件：Provider 第一件事就是 readBytes，文件不在会直接返回 Err */
    private fun audioFile(): java.io.File =
        java.io.File.createTempFile("stt_probe", ".wav").apply {
            writeBytes(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0))
            deleteOnExit()
        }

    // ---------- 协议标签（与路由同一判据）----------

    @Test
    fun blank_address_means_unset() {
        assertEquals(SttProtocolKind.UNSET, SttProviders.protocolKind(""))
        assertEquals(SttProtocolKind.UNSET, SttProviders.protocolKind("   "))
    }

    @Test
    fun volcengine_host_is_ark() {
        assertEquals(SttProtocolKind.ARK,
            SttProviders.protocolKind("https://ark.cn-beijing.volces.com/api/v3"))
    }

    @Test
    fun stepfun_host_is_stepfun() {
        assertEquals(SttProtocolKind.STEPFUN,
            SttProviders.protocolKind("https://api.stepfun.com/step_plan/v1"))
    }

    @Test
    fun everything_else_is_whisper_compatible() {
        for (url in listOf("https://api.groq.com/openai/v1",
                           "https://api.openai.com/v1",
                           "https://whisper.mybox.local/v1")) {
            assertEquals(url, SttProtocolKind.WHISPER, SttProviders.protocolKind(url))
        }
    }

    @Test
    fun label_matches_routing() {
        // 标签由 protocolKind 得出，路由也由它得出 —— 这里把两者的映射各断言一遍，
        // 以后任一侧改了而另一侧没改，测试会红
        val cases = mapOf(
            "https://ark.cn-beijing.volces.com/api/v3" to SttProtocolKind.ARK,
            "https://api.stepfun.com/step_plan/v1" to SttProtocolKind.STEPFUN,
            "https://api.groq.com/openai/v1" to SttProtocolKind.WHISPER,
            "" to SttProtocolKind.UNSET
        )
        for ((url, kind) in cases) {
            assertEquals(url, kind, SttProviders.protocolKind(url))
            val provider = SttProviders.create(cfg(url), transport)
            val expected = when (kind) {
                SttProtocolKind.ARK -> ArkSttProvider::class
                SttProtocolKind.STEPFUN -> StepfunSttProvider::class
                SttProtocolKind.WHISPER, SttProtocolKind.UNSET -> OpenAiSttProvider::class
            }
            assertTrue("$url 应路由到 ${expected.simpleName}，实际 ${provider::class.simpleName}",
                expected.isInstance(provider))
        }
    }

    // ---------- 三套协议的请求形态（防止实现被无意改坏）----------

    @Test
    fun ark_provider_uploads_file_then_calls_chat() {
        val calls = mutableListOf<HttpCall>()
        val t = object : HttpTransport {
            override suspend fun execute(call: HttpCall): HttpRaw {
                calls += call
                return when (calls.size) {
                    1 -> HttpRaw(code = 200, body = """{"id":"file-1","status":"processing"}""")
                    2 -> HttpRaw(code = 200,
                        body = """{"choices":[{"message":{"content":"おはよう"}}]}""")
                    else -> HttpRaw(code = 200, body = "{}")
                }
            }
        }
        val p = ArkSttProvider(cfg("https://ark.cn-beijing.volces.com/api/v3"), t)
        kotlinx.coroutines.runBlocking { p.transcribe(audioFile(), "ja") }
        assertEquals(3, calls.size)
        assertEquals("/files", calls[0].url.substringAfter("/api/v3"))
        assertTrue("第一步必须是 multipart 上传", calls[0].multipart is MultipartFile)
        assertEquals("/chat/completions", calls[1].url.substringAfter("/api/v3"))
        assertTrue("第二步必须关思考模式，否则延迟 36~137 秒",
            calls[1].jsonBody!!.contains("\"type\":\"disabled\""))
        assertTrue("提示词必须要求日文输出", calls[1].jsonBody!!.contains("日文"))
        assertEquals("DELETE", calls[2].method)
    }

    @Test
    fun stepfun_provider_posts_sse_endpoint() {
        val calls = mutableListOf<HttpCall>()
        val t = object : HttpTransport {
            override suspend fun execute(call: HttpCall): HttpRaw {
                calls += call
                return HttpRaw(code = 200, body = "data: {\"type\":\"transcript.text.done\",\"text\":\"ok\"}")
            }
        }
        val p = StepfunSttProvider(cfg("https://api.stepfun.com/step_plan/v1"), t)
        val r = kotlinx.coroutines.runBlocking { p.transcribe(audioFile(), "ja") }
        assertEquals(1, calls.size)
        assertTrue(calls[0].url.endsWith("/audio/asr/sse"))
        assertTrue(calls[0].jsonBody!!.contains("Base64") || calls[0].jsonBody!!.isNotEmpty())
        assertEquals("ok", (r as com.shiyu.app.ai.AiResult.Ok).value)
    }

    @Test
    fun whisper_provider_posts_multipart_transcriptions() {
        val calls = mutableListOf<HttpCall>()
        val t = object : HttpTransport {
            override suspend fun execute(call: HttpCall): HttpRaw {
                calls += call
                return HttpRaw(code = 200, body = """{"text":"hi"}""")
            }
        }
        val p = OpenAiSttProvider(cfg("https://api.groq.com/openai/v1"), t)
        val r = kotlinx.coroutines.runBlocking { p.transcribe(audioFile(), "en") }
        assertEquals(1, calls.size)
        assertTrue(calls[0].url.endsWith("/audio/transcriptions"))
        assertTrue("Whisper 兼容必须是 multipart", calls[0].multipart is MultipartFile)
        assertEquals("hi", (r as com.shiyu.app.ai.AiResult.Ok).value)
    }
}
