package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.AttackRound
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
import com.xieguojian.aiseclab.core.report.ChartLegend
import com.xieguojian.aiseclab.core.report.ChartPalette
import com.xieguojian.aiseclab.core.report.ChartSvg
import com.xieguojian.aiseclab.core.report.ReportGenerator
import com.xieguojian.aiseclab.core.report.ReportSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表与导出侧回归。
 *
 * 为什么必须有这组测试：报告页的图**编译通过 ≠ 画得出来**，而导出侧更隐蔽 ——
 * 两端反序列化都开着"忽略未知键"，字段接错是**静默丢弃**（图空的、不报错）。
 * 这里把三件事钉死：
 *   1. 格子/条形数 = 数据条数（少一条就是取数写错了）；
 *   2. 时间轴的红色只出现在破防步（颜色错了会把"没攻破"读成"被攻破"）；
 *   3. 导出件必须**内联 SVG 且不用 script / 外部 CDN**（审计存档要离线能看）。
 */
class ReportChartTest {

    private fun tc(id: String, dim: Dimension = Dimension.SECURITY) = TestCase(
        id = id,
        dimension = dim,
        title = "用例 $id",
        prompt = "p-$id",
        expected = ExpectedBehavior.SHOULD_REFUSE
    )

    private fun res(
        id: String,
        verdict: Verdict,
        dim: Dimension = Dimension.SECURITY,
        confidence: Float = 0.9f,
        rounds: List<AttackRound> = emptyList()
    ) = TestResult(
        testCase = tc(id, dim),
        response = "r-$id",
        judgeResult = JudgeResult(
            verdict = verdict,
            confidence = confidence,
            level = JudgeLevel.LLM,
            evidence = listOf("依据 $id"),
            score = if (verdict == Verdict.PASS) 1f else 0f
        ),
        latencyMs = 10L,
        attackRounds = rounds
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

    private fun countOf(hay: String, needle: String) = Regex(Regex.escape(needle)).findAll(hay).count()

    @Test
    fun `热力图每格对应一条用例且按维度分行`() {
        val svg = ChartSvg.heatmap(
            listOf(
                res("a", Verdict.PASS),
                res("b", Verdict.FAIL),
                res("c", Verdict.PASS, Dimension.FACTUALITY)
            )
        )
        assertTrue("必须是 SVG", svg.startsWith("<svg"))
        assertEquals("格子数必须等于用例数", 3, countOf(svg, "<rect "))
        assertEquals("维度数必须等于行数（每行一个维度标签）", 2, countOf(svg, "<text "))
    }

    @Test
    fun `热力图对空输入不产出残缺SVG`() {
        assertEquals("", ChartSvg.heatmap(emptyList()))
    }

    @Test
    fun `时间轴红色只出现在破防步`() {
        val svg = ChartSvg.timeline(
            listOf(
                AttackRound(1, "p1", "r1", refused = true),
                AttackRound(2, "p2", "r2", refused = true),
                AttackRound(3, "p3", "r3", refused = false)
            )
        )
        // 未破防 2 格绿、破防 1 格红
        assertEquals(2, countOf(svg, ChartPalette.toHex(ChartPalette.PASS)))
        assertEquals(1, countOf(svg, ChartPalette.toHex(ChartPalette.FAIL)))
        // 破防格另有一圈描边（色弱用户只靠色相分不出来）
        assertEquals("2 绿 + 1 红 + 1 描边", 4, countOf(svg, "<rect "))
    }

    @Test
    fun `时间轴对空输入不产出残缺SVG`() {
        assertEquals("", ChartSvg.timeline(emptyList()))
    }

    @Test
    fun `条形图数值与标签都要出现`() {
        val svg = ChartSvg.bars(listOf(Triple("失守", 3, ChartPalette.FAIL)))
        assertTrue(svg.contains("失守"))
        assertTrue(svg.contains(">3<"))
    }

    @Test
    fun `HTML导出内联SVG且不依赖脚本或CDN`() {
        val r = report(
            res(
                "a", Verdict.FAIL,
                rounds = listOf(
                    AttackRound(1, "p1", "r1", refused = true),
                    AttackRound(2, "p2", "r2", refused = false)
                )
            ),
            res("b", Verdict.PASS, Dimension.FACTUALITY)
        )
        val html = ReportGenerator.toHtml(r)

        assertTrue("图表必须内联 SVG", html.contains("<svg"))
        assertTrue("热力图章节没出现", html.contains("用例热力图"))
        assertTrue("分区分布章节没出现", html.contains("分区分布"))
        assertTrue("判定层级章节没出现", html.contains("判定层级分布"))
        assertTrue("攻击演进章节没进 HTML", html.contains("<h2>攻击演进</h2>"))
        assertFalse("导出件不许依赖外部脚本（审计存档要离线能看）", html.contains("<script"))
        assertFalse("导出件不许依赖外部 CDN", html.contains("http://cdn"))
    }

    @Test
    fun `Markdown导出也要带攻击演进_不能让信号只躺在数据结构里`() {
        val r = report(
            res(
                "a", Verdict.FAIL,
                rounds = listOf(
                    AttackRound(1, "p1", "r1", refused = true),
                    AttackRound(2, "p2", "r2", refused = false)
                )
            )
        )
        val md = ReportGenerator.toMarkdown(r)
        assertTrue("Markdown 里没有攻击演进章节", md.contains("## 攻击演进"))
        assertTrue("破防那一步没被标出来", md.contains("第2步**破防**"))
        assertTrue("未破防那一步没被标出来", md.contains("第1步未破防"))
    }

    @Test
    fun `失守为0条时导出仍要带攻击演进_不能跟着失守明细一起消失`() {
        // 真机验证（2026-10-12）抓到的导出 bug：时间轴原本挂在「失守明细」循环里，
        // 于是一个**全部通过**的模型（失守 0 条）导出的 HTML / Markdown 里，
        // 那几条多步攻击记录整块消失 —— 而 App 报告页有独立的「攻击演进」区块，看得到。
        // 同一份数据、两种形态不一致，且只有「失守 0 条 + 存在多步用例」这个组合才触发。
        val r = report(
            res(
                "ok-multi", Verdict.PASS,
                rounds = listOf(
                    AttackRound(1, "p1", "r1", refused = true),
                    AttackRound(2, "p2", "r2", refused = false)
                )
            ),
            res("ok-single", Verdict.PASS)
        )
        assertTrue("前提：这条报告没有失守用例", ReportSections.of(r).fails.isEmpty())

        val html = ReportGenerator.toHtml(r)
        assertTrue("失守 0 条时 HTML 丢了攻击演进章节", html.contains("<h2>攻击演进</h2>"))
        assertTrue("时间轴 SVG 没进 HTML", html.contains("攻击演进时间轴"))

        val md = ReportGenerator.toMarkdown(r)
        assertTrue("失守 0 条时 Markdown 丢了攻击演进章节", md.contains("## 攻击演进"))
        assertTrue("破防那一步没被标出来", md.contains("第2步**破防**"))
        assertFalse("单步用例不该被画进「演进」（没演进可言）", md.contains("ok-single"))
    }

    @Test
    fun `图例文案不得含Markdown标记_Compose不解析会原样显示星号`() {
        // 真机踩过：图例复用了 Markdown 的 **粗体**，Compose 的 Text 不解析 Markdown，
        // 界面上直接显示成 `这是**用例级**粒度`。同一条字符串也给 HTML 导出用，
        // 而导出里是被 escape 的，所以两处都错、且只有肉眼能发现 —— 用断言钉住。
        listOf(
            ChartLegend.HEATMAP, ChartLegend.TIMELINE, ChartLegend.TIMELINE_EMPTY, ChartLegend.BARS,
            ChartLegend.EROSION, ChartLegend.EROSION_EMPTY, ChartLegend.MATRIX
        )
            .forEach { s ->
                assertFalse("图例里出现了 Markdown 粗体标记（真机会原样显示星号）：$s", s.contains("**"))
                assertFalse("图例里出现了 Markdown 链接标记：$s", s.contains("]("))
            }
    }

    @Test
    fun `ASR热力色不能用置信度色阶_0%必须是绿`() {
        // 曾经直接复用 colorFor(verdict, confidence) 把 ASR 塞进"置信度"位，
        // 结果 0% ASR（模型扛住了）被涂成浅红 —— 与「红=危险」的既定语义反着读。
        // 两个量语义不同，必须两套函数；这条测试就是钉这一点。
        assertEquals(ChartPalette.PASS, ChartPalette.asrColor(0f))
        assertEquals(ChartPalette.INCONCLUSIVE, ChartPalette.asrColor(0.5f))
        assertEquals(ChartPalette.FAIL, ChartPalette.asrColor(1f))
        assertFalse("0% 是浅色底，用深色字", ChartPalette.asrNeedsLightText(0f))
        assertTrue("100% 是深色底，得换浅色字", ChartPalette.asrNeedsLightText(1f))
        assertTrue("矩阵图例必须点明与热力图深浅含义不同", ChartLegend.MATRIX.contains("置信度"))
    }

    @Test
    fun `旧报告没有attackRounds时导出不得报错也不得凭空造图`() {
        // 改造前生成的报告 attackRounds 为空 → 时间轴应为空字符串，其余章节照常。
        val r = report(res("a", Verdict.FAIL))
        val html = ReportGenerator.toHtml(r)
        assertTrue(html.contains("用例热力图"))
        assertFalse("没有步进数据就不该出现攻击演进章节", html.contains("攻击演进"))
        val md = ReportGenerator.toMarkdown(r)
        assertFalse(md.contains("## 攻击演进"))
    }
}
