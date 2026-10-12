package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.Serializable

/**
 * 单条测试的完整结果，含递归对抗的攻击轨迹。
 */
@Serializable
data class TestResult(
    val testCase: TestCase,
    val response: String,
    val judgeResult: JudgeResult,
    val latencyMs: Long,
    val attackTrail: List<String> = emptyList(),  // 递归对抗：每轮攻击 prompt
    /**
     * 多次采样的一致性（多数派占比，0..1）。**只后端会填** —— 端上不做重复采样，
     * 恒为 1.0。⚠️ 字段名必须与后端 `app/models.py::TestResult` 逐字一致：反序列化用的是
     * `ignoreUnknownKeys = true`，名字对不上会**静默丢弃**，稳定性信号就再也到不了界面。
     */
    val stability: Float = 1f,
    /**
     * false = 单样本模式（后端 `SAMPLES_PER_CASE=1`，或端上本机跑），此时 [stability]
     * 是占位值 1.0、**不代表真实波动**。别把占位值当成"模型很稳定"。
     */
    val stabilityMeasured: Boolean = false
)
