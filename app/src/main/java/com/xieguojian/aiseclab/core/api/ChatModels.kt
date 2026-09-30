package com.xieguojian.aiseclab.core.api

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * 注意 kotlinx 默认 `encodeDefaults = false`：等于默认值的字段**不会**发出去。
 * 所以 `logprobs` / `top_logprobs` 用默认值时对老服务端完全无感，
 * 只有显式打开才会出现在请求体里 —— 这是刻意的，避免把不支持这些字段的端点打成 400。
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 2048,
    val logprobs: Boolean = false,
    val top_logprobs: Int = 0
)

@Serializable
data class ChatResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage = ChatMessage("", ""),
    val finish_reason: String? = null,
    val logprobs: ChoiceLogprobs? = null
)

/** OpenAI 兼容的 logprobs 结构（llama.cpp server / vLLM / Ollama 基本都支持）。 */
@Serializable
data class ChoiceLogprobs(
    val content: List<TokenLogprob> = emptyList()
)

@Serializable
data class TokenLogprob(
    val token: String = "",
    val logprob: Double = 0.0,
    val top_logprobs: List<TopLogprob> = emptyList()
)

@Serializable
data class TopLogprob(
    val token: String = "",
    val logprob: Double = 0.0
)
