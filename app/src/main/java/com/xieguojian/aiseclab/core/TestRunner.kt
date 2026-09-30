package com.xieguojian.aiseclab.core

import com.xieguojian.aiseclab.core.api.ChatMessage
import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.engine.AttackEngine
import com.xieguojian.aiseclab.core.engine.AttackModule
import com.xieguojian.aiseclab.core.engine.RecursiveAdversarialAttack
import com.xieguojian.aiseclab.core.judge.Judge
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.score.ScoringModel

/**
 * 测试编排器：串联 用例收集 → 攻击执行（可选递归对抗）→ 评分 → 报告。
 */
class TestRunner(
    private val targetClient: OpenAiCompatibleClient,
    private val judge: Judge,
    private val profile: ModelProfile
) {

    suspend fun run(
        modules: List<AttackModule>,
        useRecursive: Boolean,
        attackerChat: (suspend (List<ChatMessage>) -> String)?,
        // 回到 4 路并发：429 已归为 SKIPPED（不进分母），不再需要为限流牺牲吞吐
        maxConcurrency: Int = 4,
        /** 递归对抗的迭代上限。碰到天花板就停，但**不要为了省 token 调低** —— */
        maxAttackRounds: Int = DEFAULT_MAX_ROUNDS,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        onLog: ((String) -> Unit)? = null
    ): Report {
        val cases = modules.flatMap { it.buildCases() }
        val total = cases.size
        val dimLabels = modules.joinToString("/") { it.dimension.label }
        onLog?.invoke("▶ 开始测试：共 $total 条用例，维度 [$dimLabels]，递归对抗 = ${if (useRecursive && attackerChat != null) "开启" else "关闭"}")
        val results = mutableListOf<TestResult>()

        val recursive = useRecursive && attackerChat != null

        if (recursive) {
            val securityCases = cases.filter { it.dimension == Dimension.SECURITY }
            val otherCases = cases.filter { it.dimension != Dimension.SECURITY }

            // 普通维度：并发跑
            val engine = AttackEngine(targetClient, judge)
            results.addAll(
                engine.run(otherCases, maxConcurrency, onProgress) { line -> onLog?.invoke(line) }
            )
            var done = otherCases.size

            // 安全维度：递归对抗（串行迭代）
            val ra = RecursiveAdversarialAttack(targetClient, judge, attackerChat)
            for (c in securityCases) {
                val r = ra.attack(c, maxRounds = maxAttackRounds)
                results.add(r)
                done++
                val verdictZh = when (r.judgeResult.verdict) {
                    Verdict.PASS -> "通过"
                    Verdict.FAIL -> "失守"
                    Verdict.SKIPPED -> "跳过"
                    Verdict.INCONCLUSIVE -> "待定"
                }
                onLog?.invoke("● [security·递归] #$done/$total 《${c.title}》 → $verdictZh")
                onLog?.invoke("  输入：${c.prompt.replace("\n", " ").replace("\r", "").take(140)}")
                onLog?.invoke("  回答：${r.response.replace("\n", " ").replace("\r", "").take(180)}")
                onProgress(done, total)
            }
        } else {
            results.addAll(
                AttackEngine(targetClient, judge)
                    .run(cases, maxConcurrency, onProgress) { line -> onLog?.invoke(line) }
            )
        }

        return ScoringModel.score(results, profile, System.currentTimeMillis())
    }

    companion object {
        /**
         * 递归对抗默认迭代上限。
         *
         * 之前是 5，是当"省 token"来定的。现在按"能不能打到突破"来定：
         * 预算够就多迭代几轮，多轮侵蚀曲线本身就是要观察的现象（也是取证审计的核心证据）。
         *
         * 仍然保留上限的理由已经不是成本，而是**边际收益与可复现性**：
         * 无限迭代会让同一次测试跑出不同深度、报告之间没法横向比。
         * 深度不够就调这个值，别在攻击逻辑里偷偷偷工。
         */
        const val DEFAULT_MAX_ROUNDS = 10
    }
}
