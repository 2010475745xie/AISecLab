package com.xieguojian.aiseclab.core.engine

import com.xieguojian.aiseclab.core.api.ApiException
import com.xieguojian.aiseclab.core.api.ChatMessage
import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.judge.JudgeResults
import com.xieguojian.aiseclab.core.model.JudgeResult
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 目标模型调用 + 失败归类。**接口失败 ≠ 模型失守。**
 *
 * 之前的行为是把任何异常直接判成 `Verdict.FAIL / confidence 1.0 / score 0`，
 * 于是免费额度的 429 限流一多，报告就变成"失守率 100%、严重风险"，结论完全失真。
 * 现在的约定：
 *  1. 429 / 5xx / IO 超时 → 退避重试（2s、5s、10s）；
 *  2. 仍失败 → 该用例判 [com.xieguojian.aiseclab.core.model.Verdict.SKIPPED]（不可评估），
 *     由 ScoringModel 从分母里剔除；
 *  3. 错误信息截断后再进证据链，绝不把整段 429 JSON（含 request id）塞进报告。
 */
object TargetCaller {

    /** 重试等待（毫秒）。长度 = 重试次数。限流场景下多等一会儿比丢掉样本划算。 */
    private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)

    data class Outcome(val response: String?, val error: String?) {
        val ok: Boolean get() = response != null
    }

    suspend fun call(client: OpenAiCompatibleClient, prompt: String): Outcome {
        var lastErr: String? = null
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                return Outcome(client.chat(listOf(ChatMessage("user", prompt))), null)
            } catch (e: Exception) {
                lastErr = e.message
                if (attempt >= RETRY_DELAYS_MS.size || !retryable(e)) break
                delay(RETRY_DELAYS_MS[attempt])
            }
        }
        return Outcome(null, lastErr)
    }

    /**
     * 调用失败时的评判结果：不可评估，中性分，不进失守率。
     * 语义已收敛到 [JudgeResults] 统一维护，此处保留为原调用点的兼容入口。
     */
    fun skippedJudge(error: String?): JudgeResult = JudgeResults.skipped(error)

    private fun retryable(e: Exception): Boolean {
        val code = (e as? ApiException)?.status
        if (code == 429 || (code != null && code >= 500)) return true
        return e is IOException   // 连接/读写超时、网络中断
    }
}
