package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.ReportGenerator
import com.xieguojian.aiseclab.core.report.ReportSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告四路分区（报告页 / Markdown / HTML 三处共用）的判据回归。
 *
 * 背景：这三处过去各写了一遍「待人工复核」过滤条件：
 * `needsReview && verdict != FAIL && verdict != INCONCLUSIVE` —— **漏了 SKIPPED**。
 * 一条 `needsReview=true` 的 SKIPPED 会同时出现在「待人工复核」和
 * 「不可评估 · 调用失败」两处（两种样式、看起来像两条不同的问题）。
 * 现在判据收敛到 [ReportSections]，这里把边界逐条钉死。
 */
class ReportSectionsTest {

    private fun tc(
        id: String,
        dim: Dimension = Dimension.SECURITY,
        title: String = "用例 $id"
    ) = TestCase(
        id = id,
        dimension = dim,
        title = title,
        prompt = "prompt of $id",
        expected = ExpectedBehavior.SHOULD_REFUSE
    )

    private fun res(
        id: String,
        verdict: Verdict,
        needsReview: Boolean = false,
        dim: Dimension = Dimension.SECURITY,
        title: String = "用例 $id"
    ) = TestResult(
        testCase = tc(id, dim, title),
        response = "response of $id",
        judgeResult = JudgeResult(
            verdict = verdict,
            confidence = 0.9f,
            level = JudgeLevel.LLM,
            evidence = listOf("依据 $id"),
            score = if (verdict == Verdict.PASS) 1f else 0f,
            needsReview = needsReview,
            disagreements = if (needsReview) listOf("票型 $id") else emptyList()
        ),
        latencyMs = 100L
    )

    private fun report(vararg rs: TestResult) = Report(
        modelProfile = ModelProfile("target", "fixture", "http://localhost", "", "m"),
        timestamp = 1L,
        overallScore = 100f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = rs.toList(),
        summary = "摘要"
    )

    /** 六种"容易分错桶"的组合，一次全放进来。 */
    private fun mixedResults() = listOf(
        res("ok-plain", Verdict.PASS),                                  // 普通通过 → 哪都不进
        res("ok-review", Verdict.PASS, needsReview = true),             // 判过但有分歧 → 待复核
        res("fail-review", Verdict.FAIL, needsReview = true),           // 失守 → 失守明细
        res("skip-review", Verdict.SKIPPED, needsReview = true),        // ⚠️ 曾经的缺口
        res("skip-plain", Verdict.SKIPPED),                             // 调用失败、无复核标记
        res("incon-review", Verdict.INCONCLUSIVE, needsReview = true)   // 判定拿不准
    )

    @Test
    fun `四路分区必须互斥且完备`() {
        val results = mixedResults()
        val s = ReportSections.of(results)

        assertEquals(listOf("fail-review"), s.fails.map { it.testCase.id })
        assertEquals(listOf("ok-review"), s.reviewOnly.map { it.testCase.id })
        assertEquals(listOf("ok-plain"), s.passPlain.map { it.testCase.id })
        assertEquals(listOf("skip-review", "skip-plain"), s.skipped.map { it.testCase.id })
        assertEquals(listOf("incon-review"), s.inconclusive.map { it.testCase.id })

        // 五路互斥且完备：求和必须等于用例总数（图表靠这个计数，别让某条落到五路之外）。
        assertEquals(results.size, s.total)
        assertEquals(
            results.map { it.testCase.id }.toSet(),
            (s.fails + s.reviewOnly + s.passPlain + s.skipped + s.inconclusive)
                .map { it.testCase.id }.toSet()
        )
        // 「干净通过」不得混进待复核 —— 两边的判据必须是互补的，不能都收 or 都不收。
        assertTrue(s.passPlain.none { it.judgeResult.needsReview })

        // 完备性：进桶的恰好是「非 PASS，或 PASS 但带复核标记」那批；两条普通 PASS 不该进任何桶。
        val placed = (s.fails + s.reviewOnly + s.unassessed).map { it.testCase.id }
        val shouldPlace = results
            .filter { it.judgeResult.verdict != Verdict.PASS || it.judgeResult.needsReview }
            .map { it.testCase.id }
        assertEquals(shouldPlace.toSet(), placed.toSet())
        // 互斥性：同一条不能被两个桶各收一次。
        assertEquals(placed.size, placed.distinct().size)
        assertEquals(1, s.unassessedCount - s.skipped.size) // inconclusive 恰 1 条
    }

    @Test
    fun `带复核标记的调用失败不得落进待人工复核`() {
        val results = mixedResults()
        val s = ReportSections.of(results)
        // 前提：这条确实同时满足「needsReview=true」和「verdict=SKIPPED」——
        // 旧判据（!= FAIL && != INCONCLUSIVE）会把它放进 reviewOnly，从而在界面上出现两遍。
        val skipReview = results.first { it.testCase.id == "skip-review" }
        assertTrue(skipReview.judgeResult.needsReview)
        assertEquals(Verdict.SKIPPED, skipReview.judgeResult.verdict)
        assertFalse(s.reviewOnly.any { it.testCase.id == "skip-review" })
        assertTrue(s.skipped.any { it.testCase.id == "skip-review" })
    }

    @Test
    fun `判定拿不准与失守都不得落进待人工复核`() {
        val s = ReportSections.of(mixedResults())
        assertFalse(s.reviewOnly.any { it.judgeResult.verdict == Verdict.INCONCLUSIVE })
        assertFalse(s.reviewOnly.any { it.judgeResult.verdict == Verdict.FAIL })
        assertFalse(s.reviewOnly.any { it.judgeResult.verdict == Verdict.SKIPPED })
        // 「待人工复核」这个区块的前提就是"判定为通过"——只能是 PASS。
        assertTrue(s.reviewOnly.all { it.judgeResult.verdict == Verdict.PASS })
    }

    @Test
    fun `不可评估按维度计数要把两类成因合并`() {
        val s = ReportSections.of(
            listOf(
                res("a", Verdict.SKIPPED, dim = Dimension.SECURITY),
                res("b", Verdict.INCONCLUSIVE, dim = Dimension.SECURITY, needsReview = true),
                res("c", Verdict.SKIPPED, dim = Dimension.ROBUSTNESS),
                res("d", Verdict.PASS, dim = Dimension.ROBUSTNESS)
            )
        )
        assertEquals(mapOf(Dimension.SECURITY to 2, Dimension.ROBUSTNESS to 1), s.unassessedByDim)
        assertEquals(3, s.unassessedCount)
    }

    @Test
    fun `导出文档与报告页共用同一份分区`() {
        val r = report(*mixedResults().toTypedArray())
        val s = ReportSections.of(r)
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)

        // 只有 ok-review 一条该进「待人工复核」；skip-review / incon-review 必须被排除
        //（旧判据 `!= FAIL && != INCONCLUSIVE` 会把它们算进来 → 3 条，所以这里同时钉住"不得是 3"）。
        assertEquals(1, s.reviewOnly.size)
        assertEquals(listOf("ok-review"), s.reviewOnly.map { it.testCase.id })
        assertTrue("markdown 里的待复核条数没跟上分区", md.contains("## 待人工复核 1 条"))
        assertTrue("html 里的待复核条数没跟上分区", html.contains("<h2>待人工复核 1 条</h2>"))
        assertFalse("又出现了旧判据的条数（把不可评估算进待复核）", md.contains("待人工复核 3 条"))

        // 两类不可评估必须在文档里分章列出，且同一条不得出现在「待人工复核」章节里。
        val mdReview = md.indexOf("## 待人工复核")
        val mdUnassessed = md.indexOf("## 不可评估")
        assertTrue(mdReview in 1 until mdUnassessed)
        listOf("用例 skip-review", "用例 incon-review").forEach { title ->
            val at = md.indexOf(title)
            assertTrue("$title 没有出现在文档里", at > 0)
            assertTrue("$title 出现在了「不可评估」章节之前（疑似重复）", at > mdUnassessed)
        }
        // skipped 是 2 条（skip-review + skip-plain），inconclusive 1 条 —— 两类分开列。
        assertTrue(html.contains("<h3>调用失败 2 条</h3>"))
        assertTrue(html.contains("<h3>判定拿不准 1 条</h3>"))
        assertTrue(md.contains("## 不可评估 3 条"))
    }
}
