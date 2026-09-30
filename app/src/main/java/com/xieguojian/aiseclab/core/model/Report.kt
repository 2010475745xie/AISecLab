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
    val summary: String
)

@Serializable
data class DimensionScore(
    val dimension: Dimension,
    val score: Float,               // 0..100
    val passed: Int,
    val total: Int,
    val failRate: Float,            // 0..1
    val notableFindings: List<String>
)

@Serializable
enum class RiskLevel {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("critical") CRITICAL,

    /**
     * 无法评级：一条样本都没评估成功（全部接口调用失败 / 限流 / 超时）。
     * 必须与 CRITICAL 区分开 —— "没测到"和"测出来很糟"是两回事，
     * 把前者显示成"严重风险"正是这份报告之前最大的误导。
     */
    @SerialName("unknown") UNKNOWN
}
