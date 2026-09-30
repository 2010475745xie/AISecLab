package com.xieguojian.aiseclab.core.engine

import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.judge.Judge
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 攻击引擎：并发调度所有用例，逐个调用目标模型并交给评判器。
 */
class AttackEngine(
    private val targetClient: OpenAiCompatibleClient,
    private val judge: Judge
) {

    suspend fun run(
        cases: List<TestCase>,
        // 回到 4 路并发。
        // 当初压到 2 是因为 429 会被误判成失守、把整份报告打成"严重风险"（实测踩过）；
        // 现在 429 已正确归为 SKIPPED 并从分母剔除，没必要再为它牺牲吞吐 ——
        // 跑得慢比跑得浅更伤（样本量直接决定结论可信度）。
        maxConcurrency: Int = 4,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        onLog: ((String) -> Unit)? = null
    ): List<TestResult> {
        if (cases.isEmpty()) return emptyList()
        val semaphore = Semaphore(maxConcurrency)
        val results = mutableListOf<TestResult>()
        val lock = Any()
        var completed = 0
        val total = cases.size

        coroutineScope {
            cases.map { case ->
                async {
                    semaphore.withPermit {
                        val result = runCase(case)
                        synchronized(lock) {
                            results.add(result)
                            completed++
                            val jr = result.judgeResult
                            val verdictZh = when (jr.verdict) {
                                Verdict.PASS -> "通过"
                                Verdict.FAIL -> "失守"
                                Verdict.SKIPPED -> "跳过"
                                Verdict.INCONCLUSIVE -> "待定"
                            }
                            val levelZh = when (jr.level) {
                                JudgeLevel.L1_PATTERN -> "规则"
                                JudgeLevel.L2_SEMANTIC -> "语义"
                                JudgeLevel.L3_STRUCTURAL -> "结构"
                                JudgeLevel.LLM -> "LLM"
                            }
                            onLog?.invoke(
                                "✓ [${case.dimension.label}] #$completed/$total 《${case.title}》 → $verdictZh" +
                                "（置信 ${(jr.confidence * 100).toInt()}% · $levelZh）"
                            )
                            onLog?.invoke("  输入：${case.prompt.replace("\n", " ").replace("\r", "").take(140)}")
                            onLog?.invoke("  回答：${result.response.replace("\n", " ").replace("\r", "").take(180)}")
                            jr.evidence.firstOrNull()?.let {
                                onLog?.invoke("  依据：${it.replace("\n", " ").replace("\r", "").take(160)}")
                            }
                        }
                        onProgress(completed, total)
                    }
                }
            }.awaitAll()
        }
        return results
    }

    private suspend fun runCase(case: TestCase): TestResult {
        val start = System.currentTimeMillis()
        val outcome = TargetCaller.call(targetClient, case.prompt)
        val latency = System.currentTimeMillis() - start
        return if (outcome.ok) {
            val response = outcome.response!!
            TestResult(case, response, judge.judge(case, response), latency)
        } else {
            // 接口失败 ≠ 模型失守：标为不可评估，由 ScoringModel 从分母里剔除
            TestResult(case, "<调用失败>", TargetCaller.skippedJudge(outcome.error), latency)
        }
    }
}
