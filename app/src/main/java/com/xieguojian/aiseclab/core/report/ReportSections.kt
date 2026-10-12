package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict

/**
 * 报告的分区 —— **报告页、Markdown 导出、HTML 导出、图表四处共用这一份实现**。
 *
 * 为什么要抽出来：这几处过去各写了一遍同样的 filter（报告页 5 个、导出 2 组），
 * 2026-10-12 复查时发现三处的「待人工复核」判据都是
 * `needsReview && verdict != FAIL && verdict != INCONCLUSIVE` —— **漏了 SKIPPED**。
 * 一条 `needsReview=true` 的 SKIPPED 会同时出现在「待人工复核」与
 * 「不可评估 · 调用失败」两处，正是当初为了避免重复才做分区的那类 bug。
 * （当前后端只在 FAIL / INCONCLUSIVE 上打 `needs_review`，所以尚未暴露；
 *   但「同一个判据抄三遍、改一处忘两处」这个结构本身就是缺陷温床。）
 *
 * 现在用 `verdict == PASS` 表达「判过、且不是失守」：各路**互斥且完备** ——
 * 每条用例恰好落进 fails / reviewOnly / passPlain / skipped / inconclusive 之一，
 * 将来新增 verdict 也不会被静默算成「待复核」。
 *
 * `passPlain`（2026-10-12 增）是为了让上面的"完备"可被**计数**：
 * 图表要画「通过 38 条」这一条，如果调用方自己写
 * `count { PASS } - reviewOnly.size`，就等于在 UI 里再抄一遍分区判据。
 */
data class ReportSections(
    /** 真失守。 */
    val fails: List<TestResult>,
    /**
     * 判过（PASS）但证据链里仍有未消解分歧的那批。
     * 只列这批：FAIL 已在失守明细逐条列过，SKIPPED / INCONCLUSIVE 已在「不可评估」列过。
     */
    val reviewOnly: List<TestResult>,
    /** 判过、且证据链没有未消解分歧的那批（"干净通过"，报告里不单独列条，只计数）。 */
    val passPlain: List<TestResult>,
    /** 不可评估之一：接口调用失败 / 限流 / 超时 —— 查网络与配额，别去查模型。 */
    val skipped: List<TestResult>,
    /** 不可评估之二：三角色裁判未形成多数结论 —— 是判定链的问题，不是模型的问题。 */
    val inconclusive: List<TestResult>,
    /** 每个维度的不可评估条数（维度卡上要标「· 不可评估 N 条」）。 */
    val unassessedByDim: Map<Dimension, Int>
) {
    /** 不可评估总量 = 调用失败 + 判定拿不准（两者成因与修法不同，展示时要分开说）。 */
    val unassessed: List<TestResult> get() = skipped + inconclusive
    val unassessedCount: Int get() = skipped.size + inconclusive.size

    /** 五路求和 —— 应恒等于用例总数；图表与自检都用它。 */
    val total: Int get() = fails.size + reviewOnly.size + passPlain.size + skipped.size + inconclusive.size

    companion object {
        fun of(report: Report): ReportSections = of(report.results)

        fun of(results: List<TestResult>): ReportSections {
            val skipped = results.filter { it.judgeResult.verdict == Verdict.SKIPPED }
            val inconclusive = results.filter { it.judgeResult.verdict == Verdict.INCONCLUSIVE }
            return ReportSections(
                fails = results.filter { it.judgeResult.verdict == Verdict.FAIL },
                reviewOnly = results.filter {
                    it.judgeResult.needsReview && it.judgeResult.verdict == Verdict.PASS
                },
                passPlain = results.filter {
                    it.judgeResult.verdict == Verdict.PASS && !it.judgeResult.needsReview
                },
                skipped = skipped,
                inconclusive = inconclusive,
                unassessedByDim = (skipped + inconclusive)
                    .groupingBy { it.testCase.dimension }
                    .eachCount()
            )
        }
    }
}
