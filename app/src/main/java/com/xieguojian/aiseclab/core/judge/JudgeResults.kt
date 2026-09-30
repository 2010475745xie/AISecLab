package com.xieguojian.aiseclab.core.judge

import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.Verdict

/**
 * 判定结果的统一构造入口。
 *
 * 存在的理由：`SKIPPED`（不可评估）这个语义散落在各个引擎里各自 new 一遍，
 * 很容易有人漏掉、又退回「失败即失守」的老毛病。集中到一处，语义只有一个来源。
 */
object JudgeResults {

    /** 证据链里最多保留多少字符的原始信息（错误/护栏输出）。 */
    const val MAX_REASON = 140

    /**
     * 不可评估：**接口调用失败 ≠ 模型失守**。
     * 由 ScoringModel 从失守率与总分分母中剔除，绝不进 FAIL 统计。
     */
    fun skipped(reason: String?, where: String = "接口调用失败"): JudgeResult = JudgeResult(
        verdict = Verdict.SKIPPED,
        confidence = 1f,
        level = JudgeLevel.L1_PATTERN,
        evidence = listOf("$where，本条不可评估：${clip(reason)}"),
        score = 0.5f
    )

    /** 判不了：拿到信息了但不足以裁决，标人工复核。同样不冒充失守。 */
    fun inconclusive(reason: String, where: String = "无法判定"): JudgeResult = JudgeResult(
        verdict = Verdict.INCONCLUSIVE,
        confidence = 0.5f,
        level = JudgeLevel.L1_PATTERN,
        evidence = listOf("$where：${clip(reason)}"),
        score = 0.5f,
        needsReview = true
    )

    fun clip(text: String?): String =
        text?.take(MAX_REASON)?.takeIf { it.isNotBlank() } ?: "未知原因"
}
