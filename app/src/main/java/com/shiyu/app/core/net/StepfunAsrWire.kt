package com.shiyu.app.core.net

import org.json.JSONObject

/**
 * stepfun 语音识别（ASR）的线协议（纯 JVM，可单测）。
 *
 * **为什么需要单独一套**：stepfun **没有** OpenAI 的 `POST /audio/transcriptions`
 * （实测返回 404），它自己的 ASR 是 `POST /audio/asr/sse`：
 * 请求体是 JSON + base64（不是 multipart），响应是 SSE 文本流（不是 JSON）。
 * 两者协议完全不同，所以 `OpenAiSttProvider` 直接填 stepfun 的地址是调不通的。
 *
 * 实测（2026-09-24，model = `stepaudio-2.5-asr`）：
 * - 端点 `https://api.stepfun.com/step_plan/v1/audio/asr/sse` 可用（200 + session_id）
 * - **日语可识别**（language=ja），中文也可；见 HANDOVER §17.21.5
 * - 套餐限制：`stepaudio-3-asr-max` / `stepaudio-2-asr-pro` 会返回
 *   404 `model_invalid`（无权访问），只有 `stepaudio-2.5-asr` 可用
 */
object StepfunAsrWire {

    /** ASR 端点后缀（拼在规范化后的 baseUrl 之后） */
    const val ASR_SSE_PATH = "/audio/asr/sse"

    /**
     * 「端点通了、音频也被接受，但里面没有可识别的人声」时用的错误描述。
     *
     * 这个**不是配置错误**：地址错会是 404、Key 错会是 401，而走到这里说明
     * 鉴权和端点都正常。设置页「测试连接」发的是一段静音探针，必然得到空转写，
     * 所以探活时要把这条当成**成功**（见 `SettingsActivity.runProbe`）。
     */
    const val EMPTY_TRANSCRIPT = "empty transcript"

    private val KNOWN_TYPES = setOf("wav", "mp3", "ogg", "m4a", "flac", "pcm")

    /**
     * 按文件扩展名给出 stepfun 的 `format.type`。
     * 未知的扩展名一律回落 `m4a`（与 [OpenAiWire] 的多回落一致，录音产物就是 m4a）。
     */
    fun formatTypeFor(extension: String): String {
        val ext = extension.trim().trim('.').lowercase()
        return if (ext in KNOWN_TYPES) ext else "m4a"
    }

    /**
     * 构造 ASR 请求体。
     *
     * ```json
     * {"audio":{"data":"<base64>","input":{
     *    "transcription":{"language":"ja","model":"stepaudio-2.5-asr"},
     *    "format":{"type":"wav"}}}}
     * ```
     *
     * 注意：`rate`/`bits`/`channel` **只在 `type=pcm` 时必填**，其余格式选填，
     * 所以这里不传（我们是带头的 wav/m4a，不是裸 PCM）。
     */
    fun buildAsrBody(
        base64Audio: String,
        language: String,
        model: String,
        formatType: String
    ): String {
        val format = JSONObject().put("type", formatType)
        val transcription = JSONObject()
            .put("language", language)
            .put("model", model)
        val input = JSONObject()
            .put("transcription", transcription)
            .put("format", format)
        val audio = JSONObject()
            .put("data", base64Audio)
            .put("input", input)
        return JSONObject().put("audio", audio).toString()
    }

    /**
     * 解析 SSE 响应，取完整转写文本。
     *
     * 优先取 `transcript.text.done` 的 `text`；没有 done 事件时，
     * 退化为拼接所有 `transcript.text.delta` 的 `delta`（流式被截断也能救回一部分）。
     * 任何一行解析失败都跳过，不抛异常。
     *
     * @return 转写文本；没有内容则返回空串（**不是 null** —— 空串表示"识别完了但没内容"）
     */
    fun parseSseTranscript(body: String): String {
        var done: String? = null
        val deltas = StringBuilder()
        for (line in body.lineSequence()) {
            val payload = line.trim().removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val node = try {
                JSONObject(payload)
            } catch (e: Exception) {
                continue
            }
            when (node.optString("type")) {
                "transcript.text.done" -> done = node.optString("text", "")
                "transcript.text.delta" -> deltas.append(node.optString("delta", ""))
            }
        }
        return done ?: deltas.toString()
    }

    /**
     * 取 SSE 流里的 `error` 事件描述；没有错误事件返回 null。
     * 服务端常以 **HTTP 200 + SSE 里带 error** 的方式报错，只看状态码会漏。
     */
    fun parseSseError(body: String): String? {
        for (line in body.lineSequence()) {
            val payload = line.trim().removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val node = try {
                JSONObject(payload)
            } catch (e: Exception) {
                continue
            }
            if (node.optString("type") == "error") {
                val msg = node.optString("message", "")
                return msg.ifEmpty { "unknown ASR error" }
            }
        }
        return null
    }

    /**
     * baseUrl 是否指向 stepfun 官方。用于在 ASR 组里区分
     * 「OpenAI 兼容」与「stepfun 原生」两套协议（§17.21.5）。
     *
     * ⚠️ 判定必须是**域名后缀匹配**，不能简单 `contains("stepfun")` ——
     * 否则 `my-stepfun-mirror.example.com` 这类自建/代理域名会被误判成 stepfun，
     * 于是拿 stepfun 私有协议去打一个其实是 Whisper 兼容的服务，必然调不通。
     * 只认 `stepfun.com` 及其子域（官方端点形如 `api.stepfun.com`）。
     */
    fun isStepfunHost(baseUrl: String): Boolean {
        val host = baseUrl.substringAfter("://", "").substringBefore('/').lowercase()
        return host == "stepfun.com" || host.endsWith(".stepfun.com")
    }
}
