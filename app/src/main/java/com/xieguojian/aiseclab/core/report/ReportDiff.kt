package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict

/**
 * 基线对比：两份报告（基线 vs 本轮）逐用例比对，回答一个真实问题——
 * **「我改了 system prompt / 换了模型版本 / 上了护栏之后，到底变好了还是变坏了？」**
 *
 * 一次性测评只能告诉你「现在几分」，红队测试真正的价值在**持续监控**：
 * 回归（新增失守）比总分更重要 —— 总分被大量用例平均掉，单条新增失守很容易看不出来。
 *
 * ## 口径（必须与 ScoringModel / ReportGenerator 一致，别走岔）
 * - `SKIPPED`（接口失败/限流/超时）与 `INCONCLUSIVE`（拿不准）**既不算失守也不算安全**。
 *   所以一条用例从 SKIPPED 变成 FAIL，是「**新暴露了一个真问题**」，算新增失守（严重），
 *   而从 PASS 变成 SKIPPED **不算修复**（只是没测到，说不清好坏），归入「无法比较」。
 * - 只在两边都是「可评估」（PASS/FAIL）时才敢断言方向。含糊的归 [uncomparable]。
 *
 * 对齐键用 `TestCase.id`（如 `sec-inj-1`），静态用例集 id 稳定，可跨轮次对齐。
 */
object ReportDiff {

    /** 单条用例的变化。 */
    data class CaseChange(
        val result: TestResult,
        val from: Verdict,
        val to: Verdict
    )

    /** 单个维度的分数变化。 */
    data class DimChange(
        val dimension: Dimension,
        val baseScore: Float?,   // null = 基线里这个维度没跑（total=0 或维度缺失）
        val curScore: Float?
    ) {
        /** 可比才有 delta；一边没数据时返回 null，UI 显示"--"而不是 0。 */
        val delta: Float?
            get() = if (baseScore != null && curScore != null) curScore - baseScore else null
    }

    /** 完整对比结果。 */
    data class Result(
        val base: Report,
        val cur: Report,
        val baseTotal: Float,
        val curTotal: Float,
        val scoreDelta: Float?,      // null = 基线或本轮无有效样本，不能比
        val dimChanges: List<DimChange>,
        /** 本轮新增的失守（基线 PASS/未评估 → 本轮 FAIL）。最该看这个。 */
        val regressions: List<CaseChange>,
        /** 本轮修复的（基线 FAIL → 本轮 PASS）。 */
        val fixed: List<CaseChange>,
        /** 无法断言方向的（任一侧是 SKIPPED/INCONCLUSIVE）。不算好也不算坏。 */
        val uncomparable: List<CaseChange>
    ) {
        val hasAnyChange: Boolean
            get() = regressions.isNotEmpty() || fixed.isNotEmpty() || uncomparable.isNotEmpty()

        /** 一句话结论，报告顶部用。 */
        fun headline(): String = buildString {
            append(if (scoreDelta != null && scoreDelta > 0f) "总分 ↑%.1f".format(scoreDelta)
            else if (scoreDelta != null && scoreDelta < 0f) "总分 ↓%.1f".format(-scoreDelta)
            else "总分持平")
            if (regressions.isNotEmpty()) append(" · 新增失守 ${regressions.size}")
            if (fixed.isNotEmpty()) append(" · 修复 ${fixed.size}")
            if (uncomparable.isNotEmpty()) append(" · 无法比较 ${uncomparable.size}")
        }
    }

    /**
     * 比两份报告。[base] 是较早的那份（基线），[cur] 是本轮。
     */
    fun compare(base: Report, cur: Report): Result {
        val baseById = base.results.associateBy { it.testCase.id }
        val curById = cur.results.associateBy { it.testCase.id }

        val regressions = mutableListOf<CaseChange>()
        val fixed = mutableListOf<CaseChange>()
        val uncomparable = mutableListOf<CaseChange>()

        // 以本轮用例为主表遍历：本轮没跑的用例不算变化（那是"没测"，不是"变好"）
        for (id in curById.keys) {
            val c = curById.getValue(id)
            val b = baseById[id]
            if (b == null) {
                // 基线没这条：若本轮 FAIL，算新增失守（第一次就暴露了问题）
                if (c.judgeResult.verdict == Verdict.FAIL) {
                    regressions += CaseChange(c, Verdict.SKIPPED, Verdict.FAIL)
                }
                continue
            }
            val from = b.judgeResult.verdict
            val to = c.judgeResult.verdict
            if (from == to) continue

            // 只有两边都是"可评估"结论才敢断言方向
            val fromClear = from == Verdict.PASS || from == Verdict.FAIL
            val toClear = to == Verdict.PASS || to == Verdict.FAIL
            if (!fromClear || !toClear) {
                uncomparable += CaseChange(c, from, to)
            } else if (to == Verdict.FAIL) {
                regressions += CaseChange(c, from, to)   // PASS→FAIL：回归
            } else {
                fixed += CaseChange(c, from, to)          // FAIL→PASS：修复
            }
        }

        // 维度分变化：两边都跑了该维度（total>0）才给分，否则 null
        val dims = (base.dimensions.keys + cur.dimensions.keys).distinct()
            .map { dim ->
                val b = base.dimensions[dim]
                val c = cur.dimensions[dim]
                DimChange(
                    dimension = dim,
                    baseScore = b?.takeIf { it.total > 0 }?.score,
                    curScore = c?.takeIf { it.total > 0 }?.score
                )
            }
            .sortedBy { it.dimension.ordinal }

        // 总分同理：一条都没评上时 riskLevel=UNKNOWN，此时不能拿 0 分当基线比
        val baseTotal = base.overallScore.takeIf { base.riskLevel.name != "UNKNOWN" }
        val curTotal = cur.overallScore.takeIf { cur.riskLevel.name != "UNKNOWN" }

        return Result(
            base = base, cur = cur,
            baseTotal = baseTotal ?: 0f,
            curTotal = curTotal ?: 0f,
            scoreDelta = if (baseTotal != null && curTotal != null) curTotal - baseTotal else null,
            dimChanges = dims,
            regressions = regressions.sortedBy { it.result.testCase.dimension.ordinal },
            fixed = fixed.sortedBy { it.result.testCase.dimension.ordinal },
            uncomparable = uncomparable.sortedBy { it.result.testCase.dimension.ordinal }
        )
    }

    /** verdict 的中文短标签，UI 与导出共用。 */
    fun verdictText(v: Verdict): String = when (v) {
        Verdict.PASS -> "通过"
        Verdict.FAIL -> "失守"
        Verdict.SKIPPED -> "不可评估"
        Verdict.INCONCLUSIVE -> "待复核"
    }
}