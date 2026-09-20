package com.langpartner.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BaseUrlNormalizer（§5.4） */
class BaseUrlNormalizerTest {

    @Test
    fun anchor_trailing_slash() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("https://api.example.com/v1/")
        )
    }

    @Test
    fun anchor_chat_suffix_stripped() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("https://api.example.com/v1/chat/completions")
        )
    }

    @Test
    fun anchor_scheme_added() {
        assertEquals(
            "https://api.example.com",
            BaseUrlNormalizer.normalize("api.example.com")
        )
    }

    @Test
    fun http_rejected() {
        assertNull(BaseUrlNormalizer.normalize("http://api.example.com"))
        assertNull(BaseUrlNormalizer.normalize("http://api.example.com/v1"))
    }

    @Test
    fun trims_whitespace() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("  https://api.example.com/v1  ")
        )
    }

    @Test
    fun strips_transcriptions_suffix() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("https://api.example.com/v1/audio/transcriptions")
        )
    }

    @Test
    fun strips_speech_suffix() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("https://api.example.com/v1/audio/speech/")
        )
    }

    @Test
    fun multiple_trailing_slashes() {
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("https://api.example.com/v1///")
        )
    }

    @Test
    fun non_http_scheme_rejected() {
        assertNull(BaseUrlNormalizer.normalize("ftp://api.example.com"))
    }

    @Test
    fun ugly_input_normalized() {
        // 无 scheme + endpoint 后缀 + 尾斜杠
        assertEquals(
            "https://api.example.com/v1",
            BaseUrlNormalizer.normalize("api.example.com/v1/chat/completions/")
        )
    }

    @Test
    fun host_only_with_suffix() {
        assertEquals(
            "https://api.example.com",
            BaseUrlNormalizer.normalize("https://api.example.com/audio/speech")
        )
    }

    // ---------- wss（realtime）----------

    @Test
    fun wss_realtime_endpoint_kept_as_is() {
        assertEquals(
            "wss://api.stepfun.com/v1/realtime",
            BaseUrlNormalizer.normalize("wss://api.stepfun.com/v1/realtime")
        )
    }

    @Test
    fun wss_trailing_slash_stripped() {
        assertEquals(
            "wss://api.stepfun.com/v1/realtime",
            BaseUrlNormalizer.normalize("wss://api.stepfun.com/v1/realtime///")
        )
    }

    @Test
    fun wss_uppercase_scheme_accepted() {
        assertEquals(
            "wss://api.stepfun.com/v1/realtime",
            BaseUrlNormalizer.normalize("WSS://api.stepfun.com/v1/realtime")
        )
    }

    @Test
    fun ws_rejected_like_http() {
        assertNull(BaseUrlNormalizer.normalize("ws://api.stepfun.com/v1/realtime"))
    }

    @Test
    fun wss_suffixes_not_stripped() {
        // wss 路径不做 endpoint 后缀裁剪（/realtime 是端点本体）
        assertEquals(
            "wss://api.example.com/v1/realtime",
            BaseUrlNormalizer.normalize("wss://api.example.com/v1/realtime")
        )
    }
}
