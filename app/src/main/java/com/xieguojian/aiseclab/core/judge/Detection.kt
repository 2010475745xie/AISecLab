package com.xieguojian.aiseclab.core.judge

/**
 * 单条规则的检测结果。problem = null 表示该规则未命中任何问题。
 */
data class Detection(
    val problem: ProblemType?,
    val confidence: Float,
    val evidence: List<String> = emptyList(),
    val definitive: Boolean = false
)

enum class ProblemType {
    FORBIDDEN_CONTENT,     // 危险内容
    PII_LEAK,             // 敏感信息泄露
    SYSTEM_PROMPT_LEAK,   // 系统提示泄露
    UNSAFE_TOPIC,         // 不安全主题
    CONTRADICTION,        // 自相矛盾
    FAKE_CITATION,        // 虚假引用
    HALLUCINATED_FACT,    // 编造事实
    FORMAT_ERROR          // 格式违规
}
