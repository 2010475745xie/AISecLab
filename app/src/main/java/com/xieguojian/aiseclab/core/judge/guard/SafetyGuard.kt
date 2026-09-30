package com.xieguojian.aiseclab.core.judge.guard

/**
 * 护栏分类器的统一抽象：**把「模型怎么跑」和「怎么判」解耦**。
 *
 * 这样同一套判定逻辑可以在三种后端上复用，而且判定行为完全一致：
 *  - `HttpSafetyGuard`       —— 护栏跑在 PC / 局域网的 llama-server、Ollama 上（先验证用）
 *  - `LlamaCppSafetyGuard`   —— 护栏跑在端上（GGUF + llama.cpp JNI，完全离线）
 *  - 以后换 ONNX Runtime / MNN 也只是再多一个实现
 *
 * 接口刻意只回答一个问题：**这段回答有没有输出有害内容**。
 * 抗幻觉、一致性、事实性这些维度不该问护栏（见 GuardJudge 里的分流）。
 */
interface SafetyGuard {

    /** 展示用名字，会进报告的证据链。 */
    val name: String

    /**
     * 对「用户请求 + 模型回答」做联合判定（XGuard 的 QR 模式）。
     *
     * 之所以必须成对传入：护栏的核心能力正是区分
     * 「模型真的输出了危险内容」与「模型拒绝了，只是在复述请求里的危险词」。
     * 只喂回答会丢掉这个判断依据。
     */
    suspend fun classify(prompt: String, response: String): GuardVerdict
}

/**
 * 护栏的一次判定结论。
 *
 * `risk` 为 null 表示**没拿到可用结论**（解析失败 / 调用失败 / 模型吐了不认识的东西），
 * 此时上层必须走 `SKIPPED`，绝不能默认成"安全"或"失守"。
 */
data class GuardVerdict(
    /** 危险度 0..1。1 - P(sec)。null = 无结论。 */
    val risk: Float? = null,
    /** 命中的最高风险代号（`dw` / `mc` …）；安全时为 `sec`。 */
    val labelId: String? = null,
    /** 人类可读风险类别，进证据链。 */
    val categories: List<String> = emptyList(),
    /** 护栏给出的自然语言归因（`<explanation>` 内容）。 */
    val rationale: String? = null,
    /** true = 来自首 token 的 top-k 概率（标定过，可采信阈值）；false = 只拿到文字标签，置信度打折。 */
    val calibrated: Boolean = false,
    /** 调用/解析失败原因。 */
    val error: String? = null,
    /** 原始输出片段，便于排查（截断后存）。 */
    val raw: String = ""
) {
    val usable: Boolean get() = risk != null
}
