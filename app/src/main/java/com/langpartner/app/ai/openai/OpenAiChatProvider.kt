package com.langpartner.app.ai.openai

import com.langpartner.app.ai.AiError
import com.langpartner.app.ai.AiResult
import com.langpartner.app.ai.ChatProvider
import com.langpartner.app.ai.ChatRequest
import com.langpartner.app.core.net.BaseUrlNormalizer
import com.langpartner.app.core.net.HttpCall
import com.langpartner.app.core.net.HttpErrors
import com.langpartner.app.core.net.HttpTransport
import com.langpartner.app.core.net.OpenAiWire
import com.langpartner.app.data.model.ProviderConfig

/**
 * OpenAI 兼容对话 Provider（§5.3.1）：
 * POST BASE/chat/completions，Bearer 鉴权（真实 Key 只进请求头，不进任何日志），
 * 解析 choices[0].message.content；取不到 → BadResponse("missing choices[0].message.content")。
 */
class OpenAiChatProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport
) : ChatProvider {

    override suspend fun chat(request: ChatRequest): AiResult<String> {
        val base = BaseUrlNormalizer.normalize(config.baseUrl)
            ?: return AiResult.Err(AiError.BadResponse("invalid baseUrl"))
        val call = HttpCall(
            method = "POST",
            url = base + "/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json"
            ),
            jsonBody = OpenAiWire.buildChatBody(
                model = config.model,
                messages = request.messages,
                temperature = request.temperature
            )
        )
        val raw = transport.execute(call)
        HttpErrors.classify(raw)?.let { return AiResult.Err(it) }
        val content = OpenAiWire.parseChatContent(raw.body)
            ?: return AiResult.Err(AiError.BadResponse("missing choices[0].message.content"))
        return AiResult.Ok(content)
    }
}
