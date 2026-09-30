package com.xieguojian.aiseclab.core.judge.guard

import com.xieguojian.aiseclab.core.api.ChatMessage
import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient

/**
 * 通过 OpenAI 兼容接口调用护栏模型。
 *
 * 定位：**端上方案的验证夹具**。把护栏跑在 PC / 局域网的 llama-server 或 Ollama 上，
 * 先把"判定链路 + 标签契约 + 阈值"三件事验对，再去做端上集成 ——
 * 否则一旦端上判定不对，你分不清是模型的问题、模板的问题、还是判定逻辑的问题。
 *
 * 解析与判定逻辑（[XGuardParser] / [GuardJudge]）与端上版**完全共用**，
 * 所以端上只需要换掉这一个类（换成 llama.cpp JNI 实现），其余一行不用改。
 */
class HttpSafetyGuard(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val model: String,
    /**
     * 生成上限。刻意给够（结论 token + 归因段落）：
     * 归因文本是报告证据链的主体，也是"取证审计"的卖点，不该为省 token 砍掉。
     */
    private val maxTokens: Int = 320,
    /** 取首 token 的 top-k 以还原标定概率。0 = 不取（判定退化为文字标签，置信度打折）。 */
    private val topLogprobs: Int = 20
) : SafetyGuard {

    override val name: String = "XGuard@${baseUrl.trim().trimEnd('/')}"

    override suspend fun classify(prompt: String, response: String): GuardVerdict {
        // ⚠️ 契约：**必须且只能传 2 条消息**（user=提问，assistant=回复）。
        //
        // 这不是风格问题，是踩过的坑。实测 GGUF 内嵌的 chat_template 长这样：
        //   {% if messages|length == 1 %}          {{ messages[0].content }}
        //   {% elif messages|length > 1 %}        [User Query] {{ messages[0].content }}
        //                                          [LLM Response] {{ messages[1].content }}
        //   {% endif %}
        // 只要多塞一条 system 消息，`length == 2` 这个分支就进不去 ——
        // 渲染出来的输入文本会是**空字符串**，模型照样高置信度地吐一个代号出来，
        // 于是判定结果看起来"正常"但完全是噪声。这是最难查的一类错。
        //
        // 也正因如此，这里不做"把 prompt 拼成 system 消息"之类的优化。
        val messages = listOf(
            ChatMessage("user", prompt),
            ChatMessage("assistant", response)
        )
        require(messages.size == 2) { "XGuard QR 模板要求恰好 2 条消息（user/assistant）" }
        return try {
            val client = OpenAiCompatibleClient(baseUrl, apiKey, model)
            val completion = client.chatDetailed(
                messages = messages,
                temperature = 0.0,
                maxTokens = maxTokens,
                topLogprobs = topLogprobs
            )
            XGuardParser.parse(completion.content, completion.firstTopTokens)
        } catch (e: Exception) {
            GuardVerdict(error = e.message)
        }
    }
}
