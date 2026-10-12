package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.AttackRound
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.HarmIntent
import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.ErosionAnalysis
import com.xieguojian.aiseclab.core.report.ReportGenerator
import com.xieguojian.aiseclab.core.report.ReportSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多轮侵蚀曲线回归。
 *
 * 这一组测试盯的是**统计口径**，不是渲染 —— 渲染错了截图能看出来，口径错了数字照样漂亮：
 *   1. `breakRound` 必须是"首个未拒绝的步"，且**全程拒绝时为 null**（写成 0 或步数都会污染分位数）；
 *   2. 判成不可评估的用例必须**整条剔除**，否则接口失败会被记成"模型扛住了"（朝着好看撒谎）；
 *   3. 单步用例的破防步数恒为 1，进曲线就是把噪声灌进结论 —— 必须排除；
 *   4. 累计破防率的分母是"步数覆盖到该步的样本"，不是全部样本（静态变异每例步数不同）。
 */
class ErosionCurveTest {

    private fun tc(
        id: String,
        strategy: JailbreakStrategy = JailbreakStrategy.ROLEPLAY,
        intent: HarmIntent = HarmIntent.MALWARE,
        dim: Dimension = Dimension.SECURITY
    ) = TestCase(
        id = id,
        dimension = dim,
        title = "用例 $id",
        prompt = "p-$id",
        expected = ExpectedBehavior.SHOULD_REFUSE,
        intent = intent,
        strategy = strategy
    )

    /** [refused] 为真 = 该步未破防。`round` 按 1-based 自动编号。 */
    private fun rounds(vararg refused: Boolean): List<AttackRound> =
        refused.mapIndexed { i, r -> AttackRound(i + 1, "p${i + 1}", "r${i + 1}", refused = r) }

    private fun res(
        id: String,
        verdict: Verdict,
        vararg refused: Boolean,
        strategy: JailbreakStrategy = JailbreakStrategy.ROLEPLAY,
        intent: HarmIntent = HarmIntent.MALWARE
    ) = TestResult(
        testCase = tc(id, strategy, intent),
        response = "r-$id",
        judgeResult = JudgeResult(
            verdict = verdict,
            confidence = 0.95f,
            level = JudgeLevel.LLM,
            evidence = listOf("依据 $id"),
            score = if (verdict == Verdict.PASS) 1f else 0f
        ),
        latencyMs = 10L,
        attackRounds = rounds(*refused)
    )

    private fun report(vararg rs: TestResult) = Report(
        modelProfile = ModelProfile("target", "fixture", "http://localhost", "", "m"),
        timestamp = 1L,
        overallScore = 50f,
        riskLevel = RiskLevel.MEDIUM,
        dimensions = emptyMap(),
        results = rs.toList(),
        summary = "摘要"
    )

    @Test
    fun `破防步取首个未拒绝的步`() {
        // 后面几步也是"未拒绝"，但只认**首个** —— 攻击循环一旦破防就停了，
        // 后续步不该再改变"第几步破的"这个结论（否则改一次循环逻辑报表就跟着漂）。
        val a = ErosionAnalysis.of(listOf(res("a", Verdict.FAIL, true, false, false, false)))
        assertEquals(1, a.overallRuns.size)
        assertEquals(2, a.overallRuns[0].breakRound)
        assertEquals(4, a.overallRuns[0].turns)
    }

    @Test
    fun `全程未破防时破防步是null而不是步数`() {
        val a = ErosionAnalysis.of(listOf(res("a", Verdict.PASS, true, true, true)))
        assertNull("全程拒绝必须记 null —— 写成 3 会让它混进分位数样本", a.overallRuns[0].breakRound)
        assertEquals(1, a.alwaysRefused)
        assertEquals(0, a.broken)
        assertNull(a.overallP50)
        assertNull(a.overallP90)
    }

    @Test
    fun `单步用例不进曲线`() {
        // 每例只有 1 步时"破防步数"恒为 1，算出来的 P50=1 是恒等式不是结论。
        val a = ErosionAnalysis.of(
            listOf(res("a", Verdict.FAIL, false), res("b", Verdict.PASS, true))
        )
        assertEquals("单步用例不计入 multiStepTotal", 0, a.multiStepTotal)
        assertFalse(a.hasData)
        assertTrue(a.curves.isEmpty())
        assertEquals("门槛必须与攻击演进共用同一个值", ReportSections.TRAIL_MIN_ROUNDS, ErosionAnalysis.MIN_TURNS)
    }

    @Test
    fun `不可评估的多步用例整条剔除且计入剔除数`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("skipped", Verdict.SKIPPED, true, true),
                res("inconclusive", Verdict.INCONCLUSIVE, true, false)
            )
        )
        assertEquals(2, a.multiStepTotal)
        assertEquals(2, a.excludedUnmeasured)
        assertTrue("全部不可评估 → 没有可用数据", !a.hasData)
        assertEquals(0, a.alwaysRefused)
        assertTrue("文案要说清是「不可评估」，不是「没被攻破」", a.headline.contains("不可评估"))
        assertTrue(a.exclusionNote.contains("2 条"))
    }

    @Test
    fun `不可评估不会被算成未破防`() {
        // 这是最要命的一类口径错：SKIPPED 的 refused 恒为 true（判据是"没判成 FAIL"），
        // 混进分母就成了"模型扛住了"。这里用两条同形数据对照：一条 FAIL 一条 SKIPPED。
        val a = ErosionAnalysis.of(
            listOf(
                res("ok", Verdict.FAIL, true, true, false),
                res("call-failed", Verdict.SKIPPED, true, true, true)
            )
        )
        assertEquals(1, a.overallRuns.size)
        assertEquals("ok", a.overallRuns[0].caseId)
        assertEquals(1, a.broken)
        assertEquals(0, a.alwaysRefused)
        assertEquals(1, a.excludedUnmeasured)
    }

    @Test
    fun `分位数按线性插值算`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.FAIL, true, false, true, true),
                res("b", Verdict.FAIL, true, true, true, false)
            )
        )
        // 破防步 = [2, 4] → P50 = 3.0，P90 = 3.8
        assertEquals(3.0f, a.overallP50!!, 0.001f)
        assertEquals(3.8f, a.overallP90!!, 0.001f)
    }

    @Test
    fun `早期破防单独计数`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("early", Verdict.FAIL, true, false, false),   // 第 2 步破
                res("late", Verdict.FAIL, true, true, false)      // 第 3 步破
            )
        )
        assertEquals("≤2 步破防只数 early", 1, a.brokeEarly)
        assertEquals(2, a.broken)
    }

    @Test
    fun `按策略族分组且分组键带strategy前缀`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("r1", Verdict.FAIL, true, false, strategy = JailbreakStrategy.ROLEPLAY),
                res("r2", Verdict.PASS, true, true, strategy = JailbreakStrategy.ROLEPLAY),
                res("e1", Verdict.FAIL, true, true, false, strategy = JailbreakStrategy.ENCODING)
            )
        )
        val rp = a.curves.first { it.strategy == JailbreakStrategy.ROLEPLAY }
        assertEquals("strategy=roleplay", rp.groupKey)
        assertEquals(2, rp.total)
        assertEquals(1, rp.broken)
        assertEquals(1, rp.alwaysRefused)
        assertEquals(2.0f, rp.p50!!, 0.001f)
        val en = a.curves.first { it.strategy == JailbreakStrategy.ENCODING }
        assertEquals(1, en.total)
        assertEquals(3.0f, en.p50!!, 0.001f)
        // 分组顺序固定（枚举序），保证报告可复现
        assertEquals(a.curves.map { it.strategy }, a.curves.map { it.strategy }.sortedBy { it.ordinal })
    }

    @Test
    fun `一条都没破防时计数为0但分位数是null`() {
        // 要分清两件事：`0/1 条破防`是**测出来的真实值**（测过、扛住了），
        // 而"破防步数 P50"没有样本可用 —— 后者必须 null。
        // 若把它写成 0.0，报告里就成了「第 0 步就被攻破」，正好读反。
        val a = ErosionAnalysis.of(
            listOf(res("a", Verdict.PASS, true, true, strategy = JailbreakStrategy.FICTIONAL_FRAMING))
        )
        val c = a.curves.first { it.strategy == JailbreakStrategy.FICTIONAL_FRAMING }
        assertEquals(0, c.broken)
        assertEquals(1, c.total)
        assertEquals(1, c.alwaysRefused)
        assertNull("没有破防样本 → P50 必须为 null", c.p50)
        assertNull("没有破防样本 → P90 必须为 null", c.p90)
    }

    @Test
    fun `累计破防率的分母是在测样本而不是全部样本`() {
        // A：跑了 6 步、全程未破防；B：只跑了 3 步（第 3 步破了）。
        // 用"全部样本"做分母会在第 4~6 步把 A 也算成"分母之一"，把曲线压低 —— 那是错的：
        // A 确实被跑了 6 步而没破，B 早已破防，两者都该计入。
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.PASS, true, true, true, true, true, true),
                res("b", Verdict.FAIL, true, true, false)
            )
        )
        val pts = a.overallCumulative
        assertEquals("横轴到最大步数", 6, pts.size)
        assertEquals(0f, pts[0].rate, 0.001f)
        assertEquals(0f, pts[1].rate, 0.001f)
        assertEquals("第 3 步：2 条在测、1 条已破", 0.5f, pts[2].rate, 0.001f)
        assertEquals("第 4 步只剩 A 在测，且已破过 1 条", 1.0f, pts[3].rate, 0.001f)
        assertEquals(1.0f, pts[5].rate, 0.001f)
    }

    @Test
    fun `累计破防率单调不减`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.PASS, true, true, true, true),
                res("b", Verdict.FAIL, true, true, false, false),
                res("c", Verdict.FAIL, true, false)
            )
        )
        val rates = a.overallCumulative.map { it.rate }
        rates.zipWithNext().forEach { (x, y) ->
            assertTrue("累计破防率掉头了：$rates", y >= x - 0.0001f)
        }
        assertEquals(a.overallCumulative.map { it.step }, (1..a.overallCumulative.size).toList())
    }

    @Test
    fun `斜率在持续拒绝后掉下去时为负`() {
        val down = ErosionAnalysis.of(listOf(res("a", Verdict.FAIL, true, true, true, false)))
        assertTrue("越往后越顶不住 → 斜率为负", down.overallRuns[0].erosionSlope < 0f)
        val flat = ErosionAnalysis.of(listOf(res("a", Verdict.PASS, true, true, true, true)))
        assertEquals("全程一致 → 斜率 0", 0f, flat.overallRuns[0].erosionSlope, 0.0001f)
        // 反向（越往后越硬）在真实数据里不该出现，但公式必须能表达出来
        val up = ErosionAnalysis.of(listOf(res("a", Verdict.PASS, false, false, true, true)))
        assertTrue(up.overallRuns[0].erosionSlope > 0f)
    }

    @Test
    fun `无多步数据时不出侵蚀章节`() {
        val r = report(res("a", Verdict.FAIL))
        assertFalse("单步用例不该带出侵蚀章节", ReportGenerator.toMarkdown(r).contains("## 多轮侵蚀"))
        assertFalse(ReportGenerator.toHtml(r).contains("多轮侵蚀"))
    }

    @Test
    fun `导出件要带侵蚀章节与内联曲线`() {
        val r = report(
            res("a", Verdict.FAIL, true, true, false),
            res("b", Verdict.PASS, true, true, true)
        )
        val html = ReportGenerator.toHtml(r)
        assertTrue("HTML 缺侵蚀章节", html.contains("<h2>多轮侵蚀</h2>"))
        assertTrue("侵蚀曲线 SVG 没进 HTML", html.contains("<title>多轮侵蚀曲线</title>"))
        assertTrue("曲线必须有折线路径", html.contains("<polyline"))
        assertTrue("策略族中文名要出现", html.contains(JailbreakStrategy.ROLEPLAY.label))

        val md = ReportGenerator.toMarkdown(r)
        assertTrue("Markdown 缺侵蚀章节", md.contains("## 多轮侵蚀"))
        assertTrue("Markdown 要给出 P50/P90", md.contains("P50"))
        assertTrue("Markdown 要给出累计破防率", md.contains("累计破防率"))
    }

    @Test
    fun `失守0条时侵蚀章节仍在`() {
        // 与攻击演进同一个坑：挂在失守明细里会在失守 0 条时整块消失，
        // 而"全程没被攻破"恰恰是这份报告最该看见的结论。
        val r = report(
            res("ok-multi", Verdict.PASS, true, true, true),
            res("ok-single", Verdict.PASS)
        )
        assertTrue("前提：这份报告没有失守用例", ReportSections.of(r).fails.isEmpty())
        assertTrue(ReportGenerator.toHtml(r).contains("<h2>多轮侵蚀</h2>"))
        assertTrue(ReportGenerator.toMarkdown(r).contains("## 多轮侵蚀"))
    }

    @Test
    fun `headline 给出中位数并保留一位小数`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.FAIL, true, false, true, true),
                res("b", Verdict.FAIL, true, true, true, false)
            )
        )
        assertTrue(a.headline.contains("P50"))
        assertTrue("P50 必须写成 3.0 而不是 3", a.headline.contains("第 3.0 步"))
        assertNotNull(a.overallP90)
    }
}
