package com.langpartner.app.core.net

import com.langpartner.app.ai.AiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HttpErrors（§5.5）：纯函数分类，判定顺序固定 */
class HttpErrorsTest {

    @Test
    fun io_error_timeout_maps_to_timeout() {
        val raw = HttpRaw(code = 0, body = "", ioError = "timeout")
        assertEquals(AiError.Timeout(30000), HttpErrors.classify(raw))
    }

    @Test
    fun io_error_timeout_case_insensitive() {
        val raw = HttpRaw(code = 0, body = "", ioError = "TIMEOUT while reading")
        assertEquals(AiError.Timeout(30000), HttpErrors.classify(raw))
    }

    @Test
    fun io_error_other_maps_to_network() {
        val raw = HttpRaw(code = 0, body = "", ioError = "connect failed")
        assertEquals(AiError.Network, HttpErrors.classify(raw))
    }

    @Test
    fun io_error_takes_priority_over_code() {
        val raw = HttpRaw(code = 500, body = "boom", ioError = "timeout")
        assertEquals(AiError.Timeout(30000), HttpErrors.classify(raw))
    }

    @Test
    fun code_401_unauthorized() {
        assertEquals(AiError.Unauthorized(401), HttpErrors.classify(HttpRaw(401, "")))
    }

    @Test
    fun code_403_unauthorized() {
        assertEquals(AiError.Unauthorized(403), HttpErrors.classify(HttpRaw(403, "")))
    }

    @Test
    fun code_404_not_found() {
        assertEquals(AiError.NotFound(404), HttpErrors.classify(HttpRaw(404, "")))
    }

    @Test
    fun code_429_rate_limited_without_header() {
        val err = HttpErrors.classify(HttpRaw(429, ""))
        assertTrue(err is AiError.RateLimited)
        assertEquals(null, (err as AiError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun code_429_parses_retry_after() {
        val raw = HttpRaw(429, "", headers = mapOf("retry-after" to "12"))
        val err = HttpErrors.classify(raw)
        assertEquals(12L, (err as AiError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun code_429_ignores_invalid_retry_after() {
        val raw = HttpRaw(429, "", headers = mapOf("Retry-After" to "abc"))
        assertEquals(null, (HttpErrors.classify(raw) as AiError.RateLimited).retryAfterSeconds)
        val zero = HttpRaw(429, "", headers = mapOf("Retry-After" to "0"))
        assertEquals(null, (HttpErrors.classify(zero) as AiError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun code_500_server_error_truncates_body() {
        val longBody = "x".repeat(500)
        val err = HttpErrors.classify(HttpRaw(500, longBody))
        assertTrue(err is AiError.ServerError)
        assertEquals(200, (err as AiError.ServerError).body.length)
    }

    @Test
    fun code_503_server_error() {
        val err = HttpErrors.classify(HttpRaw(503, "unavailable"))
        assertEquals(AiError.ServerError(503, "unavailable"), err)
    }

    @Test
    fun code_302_unknown() {
        val err = HttpErrors.classify(HttpRaw(302, "moved"))
        assertEquals(AiError.Unknown(302, "moved"), err)
    }

    @Test
    fun code_400_unknown() {
        assertTrue(HttpErrors.classify(HttpRaw(400, "bad")) is AiError.Unknown)
    }

    @Test
    fun code_200_ok_returns_null() {
        assertNull(HttpErrors.classify(HttpRaw(200, "{}")))
    }

    @Test
    fun code_204_ok_returns_null() {
        assertNull(HttpErrors.classify(HttpRaw(204, "")))
    }
}
