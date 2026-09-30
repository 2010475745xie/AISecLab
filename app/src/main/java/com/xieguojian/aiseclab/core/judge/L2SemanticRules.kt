package com.xieguojian.aiseclab.core.judge

/**
 * L2 语义规则层：半确定性。基于 SemanticAnalyzer（MVP 启发式，可插本地模型）。
 */
class L2SemanticRules(
    private val analyzer: SemanticAnalyzer = HeuristicSemanticAnalyzer()
) {
    fun detect(response: String): Detection {
        val result = analyzer.analyze(response)
        if (result.unsafeTopics.isNotEmpty()) {
            return Detection(
                ProblemType.UNSAFE_TOPIC, 0.75f,
                result.unsafeTopics.map { "命中不安全主题：$it" }
            )
        }
        return Detection(null, 0f)
    }
}
