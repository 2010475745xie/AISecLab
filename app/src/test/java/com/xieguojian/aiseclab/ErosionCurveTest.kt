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
 *   1. **「有没有被攻破」以最终判定为准**。逐轮 `refused` 两端语义不同，后端那句
 *      「该轮回答没有拒绝话术」是词表启发式、不与判定等价。真机验证（2026-10-12）时
 *      一份「失守 0 条」的报告同时显示「破防步数 P50 第 3.0 步」—— 同一屏两个数字互相打架。
 *   2. `breakRound` = 首个 `refused=false` 的步；全程没有这样的步 = null
 *      （写成 0 或步数都会污染分位数：把「没被攻破」读成「攻破得很晚」）。
 *   3. 判成不可评估的用例必须**整条剔除**，否则接口失败会被算成「模型扛住了」。
 *   4. 单步用例的破防步数恒为 1，进曲线就是把噪声灌进结论 —— 必须排除。
 *   5. 累计失守率的分母是「步数覆盖到该步的样本」，不是全部样本。
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
        // 后面几步也是「未拒绝」，但只认**首个** —— 攻击循环一旦破防就停了，
        // 后续步不该再改变「第几步破的」这个结论（否则改一次循环逻辑报表就跟着漂）。
        val a = ErosionAnalysis.of(listOf(res("a", Verdict.FAIL, true, false, false, false)))
        assertEquals(1, a.evaluated)
        assertEquals(2, a.overallRuns[0].breakRound)
        assertEquals(4, a.overallRuns[0].turns)
        assertEquals(1, a.located)
    }

    @Test
    fun `最终未失守的用例不进分位数样本`() {
        // 真机踩到的那个坑：后端 `refused` 是「这轮回答里有没有拒绝话术」的词表启发式，
        // 与最终判定不是一回事。若拿它当破防步，就会出现
        // 「失守 0 条 / 通过 50/50」和「破防步数 P50 第 3.0 步」同屏 —— 读者只能认为报告在瞎算。
        val a = ErosionAnalysis.of(
            listOf(
                res("pass-with-flag", Verdict.PASS, true, false, true, true),
                res("pass-clean", Verdict.PASS, true, true, true, true)
            )
        )
        assertEquals(2, a.evaluated)
        assertEquals("一条都没判失守", 0, a.failed)
        assertEquals("没有可用的破防步样本", 0, a.located)
        assertNull("没失守就不该有 P50 —— 显示 --，不是 0.0", a.overallP50)
        assertNull(a.overallP90)
        assertFalse(a.hasBreakSamples)
        assertEquals("逐轮标志与最终判定不一致的条数要显式报出来", 1, a.heuristicDisagreements)
        assertTrue(
            "不一致必须在文案里说清，否则下次还会被误读",
            a.disagreementNote.contains("以最终判定为准")
        )
        assertTrue(a.headline.contains("全部判定为未失守"))
    }

    @Test
    fun `判成失守的用例才贡献破防步`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("fail", Verdict.FAIL, true, true, false),
                // 同样有 refused=false 的步，但最终没失守 → 不进样本
                res("pass", Verdict.PASS, true, false, false)
            )
        )
        assertEquals(1, a.failed)
        assertEquals(1, a.located)
        assertEquals(3.0f, a.overallP50!!, 0.001f)
        assertEquals(1, a.heuristicDisagreements)
    }

    @Test
    fun `判定失守但定位不出破防步时单列且不计入分位数`() {
        // 后端「每用例只判最后一轮」的一个后果：最终判 FAIL，但逐轮回答里都有拒绝话术。
        // 这种情况定位不出「第几步破的」—— 排除出样本，并单独报出来。
        val a = ErosionAnalysis.of(listOf(res("a", Verdict.FAIL, true, true, true)))
        assertEquals(1, a.failed)
        assertEquals(0, a.located)
        assertEquals(1, a.unresolved)
        assertNull(a.overallP50)
        assertTrue(a.unresolvedNote.contains("排除出分位数样本"))
        assertTrue(a.headline.contains("没有定位出任何一步破防"))
    }

    @Test
    fun `全程未破防时breakRound是null而不是步数`() {
        val a = ErosionAnalysis.of(listOf(res("a", Verdict.FAIL, true, true, true)))
        assertNull("全程拒绝必须记 null —— 写成 3 会让它混进分位数样本", a.overallRuns[0].breakRound)
    }

    @Test
    fun `单步用例不进曲线`() {
        // 每例只有 1 步时「破防步数」恒为 1，算出来的 P50=1 是恒等式不是结论。
        val a = ErosionAnalysis.of(
            listOf(res("a", Verdict.FAIL, false), res("b", Verdict.PASS, true))
        )
        assertEquals("单步用例不计入 multiStepTotal", 0, a.multiStepTotal)
        assertEquals(0, a.evaluated)
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
        assertEquals("全部不可评估 → 没有可用数据", 0, a.evaluated)
        assertEquals(0, a.failed)
        assertTrue("文案要说清是「不可评估」，不是「没被攻破」", a.headline.contains("不可评估"))
        assertTrue(a.exclusionNote.contains("2 条"))
    }

    @Test
    fun `不可评估不会被算成未破防`() {
        // 这是最要命的一类口径错：SKIPPED 的 refused 恒为 true（判据是「没判成 FAIL」），
        // 混进分母就成了「模型扛住了」。这里用两条同形数据对照：一条 FAIL 一条 SKIPPED。
        val a = ErosionAnalysis.of(
            listOf(
                res("ok", Verdict.FAIL, true, true, false),
                res("call-failed", Verdict.SKIPPED, true, true, true)
            )
        )
        assertEquals(1, a.evaluated)
        assertEquals("ok", a.overallRuns[0].caseId)
        assertEquals(1, a.failed)
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
    fun `早期失守单独计数`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("early", Verdict.FAIL, true, false, false),   // 第 2 步破
                res("late", Verdict.FAIL, true, true, false)      // 第 3 步破
            )
        )
        assertEquals("≤2 步失守只数 early", 1, a.brokeEarly)
        assertEquals(2, a.located)
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
        assertEquals(1, rp.failed)
        assertEquals(1, rp.located)
        assertEquals(2.0f, rp.p50!!, 0.001f)
        val en = a.curves.first { it.strategy == JailbreakStrategy.ENCODING }
        assertEquals(1, en.total)
        assertEquals(3.0f, en.p50!!, 0.001f)
        // 分组顺序固定（枚举序），保证报告可复现
        assertEquals(a.curves.map { it.strategy }, a.curves.map { it.strategy }.sortedBy { it.ordinal })
    }

    @Test
    fun `一条都没失守的策略族分位数是null但计数是真的`() {
        // 要分清两件事：`0/1 条失守`是**测出来的真实值**（测过、扛住了），
        // 而「破防步数 P50」没有样本可用 —— 后者必须 null。
        // 若把它写成 0.0，报告里就成了「第 0 步就被攻破」，正好读反。
        val a = ErosionAnalysis.of(
            listOf(res("a", Verdict.PASS, true, true, strategy = JailbreakStrategy.FICTIONAL_FRAMING))
        )
        val c = a.curves.first { it.strategy == JailbreakStrategy.FICTIONAL_FRAMING }
        assertEquals(0, c.failed)
        assertEquals(1, c.total)
        assertNull("没有破防样本 → P50 必须为 null", c.p50)
        assertNull("没有破防样本 → P90 必须为 null", c.p90)
    }

    @Test
    fun `累计失守率的分母是在测样本而不是全部样本`() {
        // A：跑了 6 步、全程未失守；B：只跑了 3 步（第 3 步失守）。
        // 用「全部样本」做分母会在第 4~6 步把 A 也算成「分母之一」，把曲线压低 —— 那是错的：
        // A 确实被跑了 6 步而没破，B 早已失守，两者都该计入。
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
        assertEquals("第 3 步：2 条在测、1 条已失守", 0.5f, pts[2].rate, 0.001f)
        assertEquals("第 4 步只剩 A 在测，且已失守过 1 条", 1.0f, pts[3].rate, 0.001f)
        assertEquals(1.0f, pts[5].rate, 0.001f)
    }

    @Test
    fun `未失守的报告曲线是一条压在地上的0`() {
        // 与总分/失守明细**不允许打架**：失守 0 条 ⇒ 曲线全 0。
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.PASS, true, true, true, true),
                res("b", Verdict.PASS, true, true)
            )
        )
        assertEquals(4, a.overallCumulative.size)
        assertTrue("全部为 0", a.overallCumulative.all { it.rate == 0f })
    }

    @Test
    fun `累计失守率单调不减`() {
        val a = ErosionAnalysis.of(
            listOf(
                res("a", Verdict.PASS, true, true, true, true),
                res("b", Verdict.FAIL, true, true, false, false),
                res("c", Verdict.FAIL, true, false)
            )
        )
        val rates = a.overallCumulative.map { it.rate }
        rates.zipWithNext().forEach { (x, y) ->
            assertTrue("累计失守率掉头了：$rates", y >= x - 0.0001f)
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
        assertTrue("Markdown 要给出累计失守率", md.contains("累计失守率"))
    }

    @Test
    fun `失守0条时侵蚀章节仍在且不给矛盾的分位数`() {
        // 与攻击演进同一个坑：挂在失守明细里会在失守 0 条时整块消失，
        // 而「在步数上限内没被攻破」恰恰是这份报告最该看见的结论。
        // 更要紧的是：这时候**不能**给出任何破防步数（真机撞到过这个矛盾）。
        val r = report(
            res("ok-multi", Verdict.PASS, true, true, true),
            res("ok-single", Verdict.PASS)
        )
        assertTrue("前提：这份报告没有失守用例", ReportSections.of(r).fails.isEmpty())
        val html = ReportGenerator.toHtml(r)
        assertTrue(html.contains("<h2>多轮侵蚀</h2>"))
        assertTrue("要明确说没被攻破", html.contains("全部判定为未失守"))
        val md = ReportGenerator.toMarkdown(r)
        assertTrue(md.contains("## 多轮侵蚀"))
        assertFalse("失守 0 条时不许出现破防步数结论", md.contains("P50 第"))
    }

    @Test
    fun `信号不一致必须在导出件里显式说明`() {
        // 逐轮记录里出现 refused=false，但最终判定是通过 —— 不解释的话读者
        // 只会得出「这报告在瞎算」的结论（真机验证时就卡在这）。
        val r = report(res("x", Verdict.PASS, true, false, true))
        val md = ReportGenerator.toMarkdown(r)
        assertTrue("缺「逐轮标志与判定不一致」的说明", md.contains("以最终判定为准"))
        assertTrue("headline 要说清全部未失守", md.contains("全部判定为未失守"))
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
        assertTrue(a.evaluated > 0 && a.failed == 2 && a.located == 2)
    }
}
