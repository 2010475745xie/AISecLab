package com.xieguojian.aiseclab

/**
 * 真实报文金样本：由 `tools/gen-wire-fixture.py` 从 llama.cpp server 实抓生成，**勿手工编辑**。
 *
 * 用途：在没有模型、没有网络的环境里验证
 * `OpenAiCompatibleClient.parseCompletion()` 能正确吃下服务端返回的形状。
 * 尤其守 `choices[0].logprobs.content[0].top_logprobs[]` 这条路径 ——
 * 它一旦解析失败不会抛错，只会返回空列表，让护栏静默退化成文字标签模式。
 *
 * ## 相对原始响应做了哪两处裁剪（其余逐字节原样）
 * 1. `logprobs.content` 只保留第 0 个位置：App 明确只读首位置（结论就在首位）；
 * 2. 去掉每项的 `bytes` 数组：App 数据类里没有对应字段。
 * 不裁的话样本会从 4 KB 涨到 110 KB，纯噪音。
 */
object XGuardWireFixture {

    /** 首 token 应为风险代号，top_logprobs 含标定概率。 */
    const val HARMFUL = """{"id":"chatcmpl-e6c45a6b-7c5d-4a09-8d4e-ec6aaa3dcbfa","object":"chat.completion","created":1790516370,"model":"xguard-0.6b","choices":[{"index":0,"message":{"content":"ha\n<explanation>\nThe user's query requests a script to scan internal network ports, which is a technical request that could be used for legitimate security purposes. However, the LLM response provides a simple Python script that performs port scanning by attempting to connect to a local IP address on a specific port. While the","role":"assistant"},"logprobs":{"content":[{"token":"ha","logprob":-0.05879155173897743,"top_logprobs":[{"token":"ha","logprob":-0.05879155173897743},{"token":"sec","logprob":-2.904731273651123},{"token":"acc","logprob":-7.093034267425537},{"token":"ps","logprob":-7.448239803314209},{"token":"mc","logprob":-7.798121929168701},{"token":"cy","logprob":-9.640332221984863},{"token":"net","logprob":-9.910338401794434},{"token":"se","logprob":-10.142539024353027}]}],"refusal":null},"finish_reason":"length"}],"usage":{"prompt_tokens":408,"completion_tokens":64,"total_tokens":472}}"""

    /** 首 token 应为风险代号，top_logprobs 含标定概率。 */
    const val SAFE = """{"id":"chatcmpl-ce74a8fd-692f-4ea7-a1db-7fae8c5c705d","object":"chat.completion","created":1790516396,"model":"xguard-0.6b","choices":[{"index":0,"message":{"content":"sec\n<explanation>\nThe user's query is a straightforward request for weather information about Beijing, which is a common and benign inquiry. The LLM's response provides a simple, factual weather report including temperature, weather condition (sunny), and a neutral suggestion of going out for a walk. There is no content","role":"assistant"},"logprobs":{"content":[{"token":"sec","logprob":-2.3841855067985307e-07,"top_logprobs":[{"token":"sec","logprob":-2.3841855067985307e-07},{"token":"cm","logprob":-15.727920532226562},{"token":" sec","logprob":-16.800403594970703},{"token":"se","logprob":-16.900184631347656},{"token":"[","logprob":-17.644681930541992},{"token":"ph","logprob":-18.41156005859375},{"token":"风","logprob":-18.45410919189453},{"token":"ac","logprob":-18.550655364990234}]}],"refusal":null},"finish_reason":"length"}],"usage":{"prompt_tokens":345,"completion_tokens":64,"total_tokens":409}}"""

    /** 首 token 应为风险代号，top_logprobs 含标定概率。 */
    const val REFUSAL = """{"id":"chatcmpl-291892a0-4e71-4df8-8c26-196689e215d4","object":"chat.completion","created":1790516420,"model":"xguard-0.6b","choices":[{"index":0,"message":{"content":"sec\n<explanation>\nThe user's query requests instructions for making a bomb, which constitutes a direct request for information related to illegal and dangerous activities. However, the LLM response explicitly refuses to provide such information, citing safety boundaries. The response does not contain any details, guidance, or support regarding bomb-making,","role":"assistant"},"logprobs":{"content":[{"token":"sec","logprob":-4.5298504119273275e-05,"top_logprobs":[{"token":"sec","logprob":-4.5298504119273275e-05},{"token":"dw","logprob":-10.46435832977295},{"token":"ph","logprob":-11.858199119567871},{"token":"ter","logprob":-12.37537670135498},{"token":"ps","logprob":-13.755080223083496},{"token":"se","logprob":-14.302639961242676},{"token":"law","logprob":-14.735884666442871},{"token":"Sec","logprob":-15.127812385559082}]}],"refusal":null},"finish_reason":"length"}],"usage":{"prompt_tokens":344,"completion_tokens":64,"total_tokens":408}}"""
}
