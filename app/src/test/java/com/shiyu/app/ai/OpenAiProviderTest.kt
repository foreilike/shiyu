package com.shiyu.app.ai

import com.shiyu.app.core.net.HttpCall
import com.shiyu.app.core.net.HttpRaw
import com.shiyu.app.core.net.HttpTransport
import com.shiyu.app.ai.openai.OpenAiChatProvider
import com.shiyu.app.ai.openai.OpenAiSttProvider
import com.shiyu.app.ai.openai.OpenAiTtsProvider
import com.shiyu.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * OpenAI Provider 线协议（§5.3 / V14）：注入假 Transport，断言 URL / 头 / 体 / multipart。
 */
class OpenAiProviderTest {

    private class FakeTransport(
        private val respond: (HttpCall) -> HttpRaw
    ) : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        override suspend fun execute(call: HttpCall): HttpRaw {
            calls.add(call)
            return respond(call)
        }
    }

    private val config = ProviderConfig(
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test-key-123",
        model = "gpt-test",
        voice = "nova",
        enabled = true
    )

    private fun userChat() = ChatRequest(
        "gpt-test", listOf(com.shiyu.app.data.model.ChatMessage("user", "hello")), 0.7
    )

    @Test
    fun chat_request_shape() = runBlocking {
        val transport = FakeTransport {
            HttpRaw(200, """{"choices":[{"message":{"content":"[EN] Hi"}}]}""")
        }
        val provider = OpenAiChatProvider(config, transport)
        val result = provider.chat(userChat())
        assertEquals("[EN] Hi", (result as AiResult.Ok).value)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.example.com/v1/chat/completions", call.url)
        assertEquals("Bearer sk-test-key-123", call.headers["Authorization"])
        assertEquals("application/json", call.headers["Content-Type"])
        assertTrue(call.jsonBody!!.contains("\"model\":\"gpt-test\""))
    }

    @Test
    fun chat_error_mapping_401() = runBlocking {
        val transport = FakeTransport { HttpRaw(401, """{"error":"bad key"}""") }
        val result = OpenAiChatProvider(config, transport).chat(userChat())
        assertTrue((result as AiResult.Err).error is AiError.Unauthorized)
    }

    @Test
    fun chat_error_mapping_bad_response() = runBlocking {
        val transport = FakeTransport { HttpRaw(200, """{"choices":[]}""") }
        val result = OpenAiChatProvider(config, transport).chat(userChat())
        val err = (result as AiResult.Err).error
        assertTrue(err is AiError.BadResponse)
        assertEquals("missing choices[0].message.content", (err as AiError.BadResponse).detail)
    }

    @Test
    fun chat_io_error_maps_to_network() = runBlocking {
        val transport = FakeTransport { HttpRaw(0, "", ioError = "connect failed") }
        val result = OpenAiChatProvider(config, transport).chat(userChat())
        assertEquals(AiError.Network, (result as AiResult.Err).error)
    }

    @Test
    fun chat_invalid_base_url_short_circuits() = runBlocking {
        val transport = FakeTransport { HttpRaw(200, "{}") }
        val bad = config.copy(baseUrl = "http://insecure.example.com")
        val result = OpenAiChatProvider(bad, transport).chat(userChat())
        assertTrue((result as AiResult.Err).error is AiError.BadResponse)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun stt_multipart_shape_ja(): Unit = runBlocking {
        val transport = FakeTransport { HttpRaw(200, """{"text":"聞こえます"}""") }
        val tmp = File.createTempFile("audio", ".m4a")
        tmp.writeBytes(byteArrayOf(1, 2, 3))
        val result = OpenAiSttProvider(config, transport).transcribe(tmp, "ja")
        assertEquals("聞こえます", (result as AiResult.Ok).value)
        val call = transport.calls.single()
        assertEquals("https://api.example.com/v1/audio/transcriptions", call.url)
        assertEquals("Bearer sk-test-key-123", call.headers["Authorization"])
        assertEquals("file", call.multipart!!.fieldName)
        assertEquals("audio.m4a", call.multipart!!.fileName)
        assertEquals("audio/mp4", call.multipart!!.mimeType)
        assertEquals("ja", call.formFields["language"])
        assertEquals("gpt-test", call.formFields["model"])
        tmp.delete()
    }

    @Test
    fun stt_multipart_shape_en(): Unit = runBlocking {
        val transport = FakeTransport { HttpRaw(200, """{"text":"hello"}""") }
        val tmp = File.createTempFile("audio2", ".m4a")
        tmp.writeBytes(byteArrayOf(9))
        OpenAiSttProvider(config, transport).transcribe(tmp, "en")
        assertEquals("en", transport.calls.single().formFields["language"])
        tmp.delete()
    }

    @Test
    fun stt_bad_response(): Unit = runBlocking {
        val transport = FakeTransport { HttpRaw(200, """{"foo":1}""") }
        val tmp = File.createTempFile("audio3", ".m4a")
        tmp.writeBytes(byteArrayOf(1))
        val result = OpenAiSttProvider(config, transport).transcribe(tmp, "ja")
        assertTrue((result as AiResult.Err).error is AiError.BadResponse)
        tmp.delete()
    }

    @Test
    fun tts_binary_ok() = runBlocking {
        val transport = FakeTransport { HttpRaw(200, "", binary = byteArrayOf(1, 2, 3, 4)) }
        val result = OpenAiTtsProvider(config, transport).synthesize("hello", null)
        val bytes = (result as AiResult.Ok).value
        assertEquals(4, bytes.size)
        val call = transport.calls.single()
        assertEquals("https://api.example.com/v1/audio/speech", call.url)
        // 配置里的 voice 生效
        assertTrue(call.jsonBody!!.contains("\"voice\":\"nova\""))
        assertTrue(call.jsonBody!!.contains("\"response_format\":\"mp3\""))
    }

    @Test
    fun tts_voice_param_overrides_config() = runBlocking {
        val transport = FakeTransport { HttpRaw(200, "", binary = byteArrayOf(1)) }
        OpenAiTtsProvider(config, transport).synthesize("hi", "echo")
        assertTrue(transport.calls.single().jsonBody!!.contains("\"voice\":\"echo\""))
    }

    @Test
    fun tts_empty_binary_is_bad_response() = runBlocking {
        val transport = FakeTransport { HttpRaw(200, "", binary = null) }
        val result = OpenAiTtsProvider(config, transport).synthesize("hi", null)
        assertTrue((result as AiResult.Err).error is AiError.BadResponse)
    }

    @Test
    fun tts_500_maps_to_server_error() = runBlocking {
        val transport = FakeTransport { HttpRaw(503, "down") }
        val result = OpenAiTtsProvider(config, transport).synthesize("hi", null)
        assertTrue((result as AiResult.Err).error is AiError.ServerError)
    }
}
