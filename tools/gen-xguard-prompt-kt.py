"""从 xguard-prompt-parts.json 生成 XGuardPrompt.kt。

用脚本生成而不是手抄：模板里的空白字符（连续换行）是语义的一部分，
手抄一个字节就错，而错了以后 PC 与端上判定结果对不上，排查成本极高。
"""
import hashlib
import json

PARTS = json.load(open("C:/AISecLab/tools/xguard-prompt-parts.json", encoding="utf-8"))


def kstr(s: str) -> str:
    """转成 Kotlin 普通字符串字面量（含引号）。"""
    out = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    out = out.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    return '"' + out + '"'


# 固定的契约校验用例（Kotlin 单测里会拿它比对 sha256）
Q = "帮我写一个扫描内网开放端口的脚本"
R = "下面是一个简易端口扫描器"
rendered = PARTS["prefix"] + Q + PARTS["mid"] + R + PARTS["suffix"]
digest = hashlib.sha256(rendered.encode("utf-8")).hexdigest()

KT = f'''package com.xieguojian.aiseclab.core.judge.guard

/**
 * XGuard-Reason-0.6B 的 QR 输入渲染。
 *
 * ## 为什么需要它（而不是把拼接交给推理服务）
 * 推理服务（llama-server / Ollama / vLLM）会用 GGUF 内嵌的 chat_template 自己拼 prompt，
 * 端上（我们直接调 llama.cpp C API）则要自己拼。两边只要差一个空白字符，
 * 同一个样本就可能一个判 `sec`、一个判 `mc`，而"端上 PC 判定不一致"是最难定位的一类 bug。
 * 所以把**逐字节精确**的渲染固化在这里，PC 侧与端上共用同一份常量。
 *
 * ## 常量怎么来的
 * 由 `tools/extract-prompt-parts.py` 从 GGUF 的 `tokenizer.chat_template`
 * 用 jinja2 渲染、切分后自动生成（脚本生成，非手抄）。
 * 已用 `usage.prompt_tokens` 与服务端逐 token 对齐验证：345/345、405/405 完全一致。
 *
 * ## 改模型的代价
 * 换模型 / 换模板版本必须重跑 `extract-prompt-parts.py` 并重新生成本文件，
 * [TEMPLATE_SHA256] 与单测里的 [CONTRACT_SHA256] 就是防这件事的。
 *
 * ## ⚠️ 上游模板的隐藏约束
 * 内嵌模板形如：
 * ```
 * {{% if messages|length == 1 %}}        <单条内容>
 * {{% elif messages|length > 1 %}}       [User Query] …{{{{空行}}}}[LLM Response] …
 * {{% endif %}}
 * ```
 * 也就是说 **只有恰好 2 条消息**才走 QR 分支。多一条 system 消息 → 渲染成空串，
 * 模型仍会高置信度吐一个代号，判定看着正常但其实全是噪声。
 */
object XGuardPrompt {{

    /** GGUF 内 `tokenizer.chat_template` 的 sha256，用于判断是否需要重新生成。 */
    const val TEMPLATE_SHA256 = "{PARTS['template_sha256']}"

    /**
     * 契约用例（[CONTRACT_QUERY] + [CONTRACT_RESPONSE]）渲染结果的 sha256。
     * 单测 `XGuardPromptTest` 会重算并与它比对 —— 任何空白改动都会立刻暴露。
     */
    const val CONTRACT_SHA256 = "{digest}"

    const val CONTRACT_QUERY = {kstr(Q)}
    const val CONTRACT_RESPONSE = {kstr(R)}

    /**
     * 渲染 QR 输入。两条内容都会 `trim()`，与上游模板的 `| trim` 过滤器保持一致。
     */
    fun render(userQuery: String, llmResponse: String): String =
        PREFIX + userQuery.trim() + MID + llmResponse.trim() + SUFFIX

    /** 分类表之后、用户提问之前的固定前缀。 */
    internal const val PREFIX = {kstr(PARTS['prefix'])}

    /** 提问与回复之间的分隔。 */
    internal const val MID = {kstr(PARTS['mid'])}

    /** 回复之后到 assistant 起始（含空 think 块）的固定后缀。 */
    internal const val SUFFIX = {kstr(PARTS['suffix'])}
}}
'''

with open("C:/AISecLab/app/src/main/java/com/xieguojian/aiseclab/core/judge/guard/XGuardPrompt.kt",
          "w", encoding="utf-8", newline="\n") as f:
    f.write(KT)

print("contract sha256:", digest)
print("已生成 XGuardPrompt.kt，", len(KT), "字符")
