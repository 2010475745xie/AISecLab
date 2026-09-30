package com.xieguojian.aiseclab.core.judge

import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.TestCase

/**
 * 评判器统一接口。实现：RuleJudge（规则流水线）与 LlmJudge（LLM 裁判）。
 */
interface Judge {
    suspend fun judge(testCase: TestCase, response: String): JudgeResult
}
