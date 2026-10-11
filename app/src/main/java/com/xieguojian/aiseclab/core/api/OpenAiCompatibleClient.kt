package com.xieguojian.aiseclab.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * OpenAI 兼容 chat completions 客户端。
 * 覆盖 OpenAI / DeepSeek / 通义 / 月之暗面 / 智谱 / vLLM / **Ollama + llama.cpp server（本地）** 等一切 OpenAI 兼容端点。
 * 设计要点：请求/响应不落盘、不打日志，Key 只在内存中短暂存在。
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /**
     * 这个 client 在测试里扮演的角色，仅用于 token 记账（[TokenMeter]）。
     * 默认 TARGET：被测模型是绝大多数调用的主体，且这是最容易漏标的那个。
     */
    private val role: TokenMeter.Role = TokenMeter.Role.TARGET
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    /**
     * 一次补全的完整结果。
     *
     * [firstTopTokens] 是**首个生成位置**的 top-k 概率（token 文本 → 概率）。
     * 护栏模型（YuFeng-XGuard 之类）采用「首 token 即结论」范式，所以这个位置就是结论本身，
     * 有了它才能把判定从"是/否"升级为**标定过的危险度**。
     *
     * [usage] 为该次调用的用量（端点不回时为 null），由 [chatDetailed] 交给 [TokenMeter] 记账。
     */
    data class Completion(
        val content: String,
        val firstTopTokens: List<Pair<String, Double>> = emptyList(),
        val usage: Usage? = null
    )

    suspend fun chat(messages: List<ChatMessage>, temperature: Double = 0.7): String =
        chatDetailed(messages = messages, temperature = temperature).content

    suspend fun chatDetailed(
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        maxTokens: Int? = null,
        topLogprobs: Int = 0
    ): Completion = withContext(Dispatchers.IO) {
        val req = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = maxTokens ?: 2048,
            logprobs = topLogprobs > 0,
            top_logprobs = topLogprobs
        )
        val body = json.encodeToString(ChatRequest.serializer(), req)
            .toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(normalizeUrl(baseUrl))
            .addHeader("Content-Type", "application/json")
        // 本地推理服务（Ollama / llama.cpp server）没有 Key。
        // 空 Key 时不发 Authorization，而不是发一个空的 "Bearer " —— 有些网关会直接 401。
        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")

        val request = builder.post(body).build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ApiException("HTTP ${resp.code}: ${text.take(500)}", resp.code)
            }
            val completion = parseCompletion(text)
            // 记账：只记成功响应。失败/重试的请求拿不到 usage，宁可不计也不估算。
            // 所有测试链路（被测/裁判/攻击/护栏）都经过这里，所以记账放在这一层而不是调用方。
            TokenMeter.record(role, completion.usage)
            completion
        }
    }

    private fun normalizeUrl(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions", ignoreCase = true)) b else "$b/chat/completions"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析 chat completions 响应体。
         *
         * 抽成静态方法是为了**可离线测试**：护栏判定链最脆弱的一环不是模型，
         * 而是"服务端返回的 JSON 形状 ↔ App 数据类"能不能对上。
         * 对不上时不会报错，只会静默退化成"拿不到概率"的文字标签模式 ——
         * 这类退化必须在单测里用真实报文拦住，而不是等跑完一轮发现置信度全被打折。
         *
         * 真实报文样本见 `XGuardWireFixture`（由 `tools/gen-wire-fixture.py` 从
         * llama.cpp server 实抓生成）。
         */
        fun parseCompletion(body: String): Completion {
            val parsed = json.decodeFromString(ChatResponse.serializer(), body)
            val choice = parsed.choices.firstOrNull()
            return Completion(
                content = choice?.message?.content?.trim() ?: "",
                firstTopTokens = firstTopTokens(choice),
                usage = parsed.usage
            )
        }

        /**
         * 取第一个生成位置的 top-k。
         * 只取位置 0：护栏默认 `reason_first=False`，结论就在首位；
         * 取别的位次会把归因文本里的词当成判定，那是错的。
         */
        fun firstTopTokens(choice: Choice?): List<Pair<String, Double>> {
            val first = choice?.logprobs?.content?.firstOrNull() ?: return emptyList()
            return first.top_logprobs.map { it.token to exp(it.logprob) }
        }
    }
}

/** status = HTTP 状态码（网络层异常时为 null），供调用方判断是否可重试（429/5xx）。 */
class ApiException(message: String, val status: Int? = null) : Exception(message)
