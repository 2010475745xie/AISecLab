package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.DimensionScore
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.ReportDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基线对比（[ReportDiff]）口径测试。
 *
 * 重点覆盖几个**容易写错、且会直接误导用户**的地方：
 * - SKIPPED → PASS 不是"修复"（只是没测到，不能说变好了）
 * - SKIPPED → FAIL 是新增失守（第一次暴露真问题，要报出来）
 * - 总分不可评估（UNKNOWN）时不能拿 0 分当基线比
 */
class ReportDiffTest {

    private fun case(id: String, dim: Dimension = Dimension.SECURITY) =
        TestCase(
            id = id, dimension = dim, title = "用例 $id",
            prompt = "p", expected = ExpectedBehavior.SHOULD_REFUSE
        )

    private fun res(id: String, v: Verdict, dim: Dimension = Dimension.SECURITY) =
        TestResult(
            testCase = case(id, dim), response = "r",
            judgeResult = JudgeResult(
                verdict = v, confidence = 1f, level = JudgeLevel.L1_PATTERN,
                score = if (v == Verdict.FAIL) 0f else 1f
            ),
            latencyMs = 1L
        )

    private fun dimScore(score: Float, passed: Int, total: Int, dim: Dimension) =
        DimensionScore(dim, score, passed, total, 0f, emptyList())

    private fun report(
        results: List<TestResult>,
        overall: Float,
        risk: RiskLevel = RiskLevel.LOW,
        dims: Map<Dimension, DimensionScore> = emptyMap()
    ) = Report(
        modelProfile = ModelProfile(id = "t", name = "m", baseUrl = "http://x", apiKey = "", model = "gpt"),
        timestamp = 1L, overallScore = overall, riskLevel = risk,
        dimensions = dims, results = results, summary = "s"
    )

    @Test
    fun `PASS 变 FAIL 算新增失守`() {
        val base = report(listOf(res("a", Verdict.PASS)), 100f)
        val cur = report(listOf(res("a", Verdict.FAIL)), 0f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(1, d.regressions.size)
        assertEquals(0, d.fixed.size)
        assertEquals("a", d.regressions[0].result.testCase.id)
    }

    @Test
    fun `FAIL 变 PASS 算修复`() {
        val base = report(listOf(res("a", Verdict.FAIL)), 0f)
        val cur = report(listOf(res("a", Verdict.PASS)), 100f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(1, d.fixed.size)
        assertEquals(0, d.regressions.size)
    }

    @Test
    fun `SKIPPED 变 PASS 不算修复`() {
        // 关键：接口失败后测到了，不等于"变好了"，只是这次测到了
        val base = report(listOf(res("a", Verdict.SKIPPED)), 50f)
        val cur = report(listOf(res("a", Verdict.PASS)), 100f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(0, d.fixed.size)
        assertEquals(0, d.regressions.size)
        assertEquals(1, d.uncomparable.size)
    }

    @Test
    fun `SKIPPED 变 FAIL 算新增失守`() {
        // 基线是"没测到"，本轮测到真失守 → 这是新暴露的问题，必须报出来
        val base = report(listOf(res("a", Verdict.SKIPPED)), 50f)
        val cur = report(listOf(res("a", Verdict.FAIL)), 0f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(1, d.regressions.size)
    }

    @Test
    fun `INCONCLUSIVE 参与变化时归为无法比较`() {
        val base = report(listOf(res("a", Verdict.INCONCLUSIVE)), 50f)
        val cur = report(listOf(res("a", Verdict.FAIL)), 0f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(0, d.regressions.size)
        assertEquals(1, d.uncomparable.size)
    }

    @Test
    fun `总分不可评估时不给 delta`() {
        val base = report(listOf(res("a", Verdict.SKIPPED)), 0f, RiskLevel.UNKNOWN)
        val cur = report(listOf(res("a", Verdict.SKIPPED)), 0f, RiskLevel.UNKNOWN)
        val d = ReportDiff.compare(base, cur)
        assertNull(d.scoreDelta)
    }

    @Test
    fun `维度缺失时 delta 为 null 而非 0`() {
        val base = report(emptyList(), 80f, dims = mapOf(Dimension.SECURITY to dimScore(80f, 8, 10, Dimension.SECURITY)))
        val cur = report(emptyList(), 80f, dims = mapOf(Dimension.SECURITY to dimScore(80f, 8, 10, Dimension.SECURITY)))
        val d = ReportDiff.compare(base, cur)
        val sec = d.dimChanges.first { it.dimension == Dimension.SECURITY }
        assertEquals(0f, sec.delta!!, 0.001f)
        // 基线没跑事实性 → 不该报成 0 变化
        val fact = d.dimChanges.first { it.dimension == Dimension.FACTUALITY }
        assertNull(fact.delta)
    }

    @Test
    fun `基线没有的用例本轮失守也算新增失守`() {
        val base = report(emptyList(), 100f)
        val cur = report(listOf(res("new1", Verdict.FAIL)), 0f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(1, d.regressions.size)
        assertEquals("new1", d.regressions[0].result.testCase.id)
    }

    @Test
    fun `本轮没跑的用例不算变化`() {
        // 基线有、本轮没这条 → 是"没测"不是"修好了"，不能进 fixed
        val base = report(listOf(res("a", Verdict.FAIL)), 0f)
        val cur = report(emptyList(), 100f)
        val d = ReportDiff.compare(base, cur)
        assertEquals(0, d.fixed.size)
        assertEquals(0, d.regressions.size)
    }

    @Test
    fun `总分上升 headline 显示箭头`() {
        val base = report(listOf(res("a", Verdict.FAIL)), 60f)
        val cur = report(listOf(res("a", Verdict.PASS)), 80f)
        val d = ReportDiff.compare(base, cur)
        assertTrue(d.headline().contains("↑"))
        assertTrue(d.headline().contains("修复 1"))
    }
}