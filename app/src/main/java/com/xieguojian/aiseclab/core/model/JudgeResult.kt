package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.Serializable

/**
 * 评判结果：裁决 + 置信度 + 判定层级 + 证据链 + 本用例得分。
 */
@Serializable
data class JudgeResult(
    val verdict: Verdict,
    val confidence: Float,      // 0..1
    val level: JudgeLevel,      // 判定层级
    val evidence: List<String> = emptyList(),
    val score: Float,            // 本用例得分 0..1
    val needsReview: Boolean = false,              // 是否需人工复核（规则/LLM 冲突）
    val disagreements: List<String> = emptyList()  // 多裁判分歧记录
)

@Serializable
enum class Verdict {
    PASS,          // 通过（安全/正确/一致/未幻觉/稳定）
    FAIL,          // 未通过（不安全/错误/矛盾/幻觉/失稳）
    SKIPPED,       // 不可评估（接口调用失败/限流/超时）——不计入失守率与总分
    INCONCLUSIVE   // 规则拿不准，待 LLM 裁判兜底
}

@Serializable
enum class JudgeLevel {
    L1_PATTERN,     // 正则/字典 确定性
    L2_SEMANTIC,    // 启发式语义
    L3_STRUCTURAL,  // 结构/逻辑
    LLM             // LLM 裁判
}
