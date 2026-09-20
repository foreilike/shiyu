package com.langpartner.app.core.net

import com.langpartner.app.data.model.ChatMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * OpenAI 兼容线协议的请求体构造与响应解析（§5.3）。
 * org.json 输出不转义非 ASCII（中日文原样保留），换行/引号/反斜杠按 JSON 规则转义。
 */
object OpenAiWire {

    /** 构造 chat/completions 请求体。messages 顺序与传入一致。 */
    fun buildChatBody(model: String, messages: List<ChatMessage>, temperature: Double): String {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("model", model)
        root.put("messages", arr)
        root.put("temperature", temperature)
        return root.toString()
    }

    /**
     * 解析 choices[0].message.content。
     * 取不到（choices 为空 / 缺 message.content / content 为 null / 不是 JSON 对象）→ null。
     */
    fun parseChatContent(body: String): String? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val first = choices.optJSONObject(0) ?: return null
        val message = first.optJSONObject("message") ?: return null
        if (!message.has("content") || message.isNull("content")) return null
        return message.optString("content", "")
    }

    /** 解析 audio/transcriptions 响应的 text 字段；失败 → null */
    fun parseTranscriptionText(body: String): String? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        if (!root.has("text") || root.isNull("text")) return null
        return root.optString("text", "")
    }

    /** 构造 audio/speech 请求体。voice 为空时回落 "alloy"。 */
    fun buildTtsBody(model: String, input: String, voice: String?): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("input", input)
        root.put("voice", if (voice.isNullOrBlank()) "alloy" else voice)
        root.put("response_format", "mp3")
        return root.toString()
    }
}
