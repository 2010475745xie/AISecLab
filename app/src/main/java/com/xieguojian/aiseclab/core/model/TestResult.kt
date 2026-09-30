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
    val attackTrail: List<String> = emptyList()  // 递归对抗：每轮攻击 prompt
)
