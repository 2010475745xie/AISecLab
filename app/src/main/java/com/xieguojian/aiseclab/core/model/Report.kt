package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AISecLab 测评报告：总分 + 风险等级 + 四维独立分 + 明细 + 摘要。
 */
@Serializable
data class Report(
    val modelProfile: ModelProfile,
    val timestamp: Long,
    val overallScore: Float,                        // 0..100
    val riskLevel: RiskLevel,
    val dimensions: Map<Dimension, DimensionScore>,
    val results: List<TestResult>,
    val summary: String,
    /**
     * 本轮实际烧掉的 token（按角色分账）。**跑完就落进报告**，所以历史报告翻出来
     * 也能看到当时花了多少 —— 之前只在跑的时候界面上显示，一离开那一刻就再也查不到了。
     *
     * `null` = 这份报告没有用量记录（旧版本生成的报告，或本轮一次模型调用都没发起）。
     * 展示时必须说「未记录」，**不能显示成 0** —— 「没记录」和「一次没烧」是两回事。
     */
    val tokenUsage: TokenUsage? = null,
    /**
     * 外部安全 API 的**对照结果**（B4）。`null` = 本轮未接入外部裁判。
     *
     * 展示时必须说「未接入」—— **不能显示成一致率 0%**：「没接入」与「完全不同意」
     * 是两回事（同「没采集 ≠ 很差」）。文案唯一源 = `core/report/ExternalNote.kt`。
     *
     * 字段名与后端 `app/models.py::Report` 不要求一致 —— 这一项**没有后端对应物**：
     * 外部裁判是端上的对照件（在本地判定链之外旁挂一路），后端那条链不参与，
     * 所以这里的 null 也可能是「后端产的报告」，同样按「未接入」展示。
     */
    val externalCheck: ExternalCheck? = null
)

/**
 * 一轮测试的 token 用量账单。与 [com.xieguojian.aiseclab.core.api.TokenMeter.Snapshot]
 * 同源（后者是跑的时候的实时计数），这里只保留落盘需要的部分。
 *
 * 两个刻意的取舍（与 TokenMeter 一致）：
 * 1. 只统计**带回 usage 的成功响应** —— 宁可不计也不估算，估出来的数会被当账单看。
 * 2. 端点不回 usage（本地 llama.cpp 默认不回）时计入 [unmeteredCalls]，
 *    让读者知道「这个数偏低，有 N 次调用没算进来」，而不是假装完整。
 */
@Serializable
data class TokenUsage(
    val total: Long = 0,
    val prompt: Long = 0,
    val completion: Long = 0,
    /** 成功且**带回 usage** 的调用数。 */
    val meteredCalls: Int = 0,
    /** 成功但**没带 usage** 的调用数。 */
    val unmeteredCalls: Int = 0,
    /** 角色（中文标签，如「被测 / 裁判 / 攻击 / 护栏」）→ token 数。 */
    val byRole: Map<String, Long> = emptyMap(),
) {
    /** 过万折算成 `1.2万`，避免长数字撑破一行（与跑的时候界面上同一把尺）。 */
    private fun fmt(v: Long): String =
        if (v >= 10_000) "%.1f万".format(v / 10_000.0) else "%,d".format(v)

    /** 一行摘要，如 `1.2万 token（被测 8,000 · 裁判 4,340）· 3 次未返回用量`。 */
    fun summary(): String {
        val parts = byRole.entries.filter { it.value > 0 }
            .joinToString(" · ") { "${it.key} ${fmt(it.value)}" }
        val base = "${fmt(total)} token" + if (parts.isNotEmpty()) "（$parts）" else ""
        return if (unmeteredCalls > 0) "$base · $unmeteredCalls 次未返回用量" else base
    }
}

/**
 * 「本次消耗」这一行的文案。**报告页 / Markdown 导出 / HTML 导出三处共用**，
 * 不许各自再拼一遍（那样「界面说 1.2万、导出写 12000」只是时间问题）。
 *
 * 没有用量记录时说「未记录」，**不显示 0**：「没记录」和「一次没烧」是两回事。
 */
fun Report.tokenSpendLine(): String =
    tokenUsage?.summary()
        ?: "未记录 —— 这份报告生成时没有留下用量（旧版本生成的报告，或本轮没有发起过模型调用）。"

@Serializable
data class DimensionScore(
    val dimension: Dimension,
    val score: Float,               // 0..100
    val passed: Int,
    val total: Int,
    val failRate: Float,            // 0..1
    val notableFindings: List<String>
)

/**
 * 风险等级。
 *
 * [label] 是**全项目唯一**的中文说法：此前 `HomeScreen` / `ReportScreen` /
 * `ReportGenerator` 各写了一份 `riskText()`（三个 `when` 一字不差）—— 改文案时漏掉一个，
 * 用户就会在同一屏上看到两种说法。与 [Dimension] / `JailbreakStrategy` 一样，
 * 把展示名挂在枚举上，而不是让每个渲染处各持一份。
 */
@Serializable
enum class RiskLevel(val label: String) {
    @SerialName("low") LOW("低风险"),
    @SerialName("medium") MEDIUM("中风险"),
    @SerialName("high") HIGH("高风险"),
    @SerialName("critical") CRITICAL("严重风险"),

    /**
     * 无法评级：一条样本都没评估成功（全部接口调用失败 / 限流 / 超时）。
     * 必须与 CRITICAL 区分开 —— "没测到"和"测出来很糟"是两回事，
     * 把前者显示成"严重风险"正是这份报告之前最大的误导。
     */
    @SerialName("unknown") UNKNOWN("无法评级")
}
