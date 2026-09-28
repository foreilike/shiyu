package com.shiyu.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守住「ASR 协议选择只有一处」这个不变量（§17.21.5）。
 *
 * 曾经踩过的坑：注册表里择一，但设置页「测试连接」自己 new 了 `OpenAiSttProvider`，
 * 于是填了 stepfun 之后出现**录音能跑、测试连接却 404** 的怪象。
 *
 * 这里不直接测 `SttProviders`（它在 ai 包，构造需要 transport），
 * 而是钉住它唯一的判据 [StepfunAsrWire.isStepfunHost] ——
 * 只要两处都用这个函数，行为就不可能再分叉。
 */
class StepfunAsrConsistencyTest {

    @Test
    fun stepfun_official_host_uses_native_protocol() {
        assertTrue2(
            "官方域名必须走 stepfun 原生协议",
            StepfunAsrWire.isStepfunHost("https://api.stepfun.com/step_plan/v1")
        )
    }

    @Test
    fun non_stepfun_host_keeps_whisper_compatible() {
        assertTrue2(
            "Groq 仍是 Whisper 兼容协议",
            !StepfunAsrWire.isStepfunHost("https://api.groq.com/openai/v1")
        )
        assertTrue2(
            "自建服务仍是 Whisper 兼容协议",
            !StepfunAsrWire.isStepfunHost("https://whisper.mybox.local/v1")
        )
    }

    @Test
    fun sse_path_and_empty_marker_are_stable() {
        // 这两个常量被 Provider 与设置页探活同时引用，改了必须同步
        assertEquals("/audio/asr/sse", StepfunAsrWire.ASR_SSE_PATH)
        assertEquals("empty transcript", com.shiyu.app.ai.SttContract.EMPTY_TRANSCRIPT)
    }

    private fun assertTrue2(msg: String, cond: Boolean) {
        org.junit.Assert.assertTrue(msg, cond)
    }
}
