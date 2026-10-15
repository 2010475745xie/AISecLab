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
import com.xieguojian.aiseclab.core.report.DefenseAttribution
import com.xieguojian.aiseclab.core.report.DefenseLayer
import com.xieguojian.aiseclab.core.report.HardeningAdvisor
import com.xieguojian.aiseclab.core.report.ReportGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加固建议在**两个导出件**里的接线回归（方案 §3.5）。
 *
 * 为什么单独有这一组：报告页能看到的东西，导出侧不一定接上了 ——
 * 2026-10-12 真机抓到过一次同型缺陷（「攻击演进」时间轴 App 页有、导出的 HTML 里整块没有），
 * 而**编译通过、单测全绿都挡不住它**：导出的 buildString 少写一段不会报任何错。
 * 所以这里用「导出件里必须出现 / 必须不出现某段文本」的方式把接线钉死。
 *
 * 另外钉两条口径：
 *  1. 导出件里写的「先失效层」必须来自 [DefenseAttribution]（与安全指标同源），
 *     不能各自再判一遍 —— 否则同一份报告里会出现"指标说规划层、建议让改输出过滤"的自相矛盾。
 *  2. **攻击类失守 0 条时这一章仍然要写**（2026-10-15 改）：范围说明由
 *     [HardeningAdvisor.HardeningReport.scopeNote] 唯一提供，两个导出件必须逐字念同一份。
 */
class ReportGeneratorHardeningTest {

    private fun attack(
        id: String,
        verdict: Verdict = Verdict.FAIL,
        strategy: JailbreakStrategy = JailbreakStrategy.ROLEPLAY,
        /** 期望行为。默认攻击类（安全维度）；非攻击类维度传别的值。 */
        expected: ExpectedBehavior = ExpectedBehavior.SHOULD_REFUSE,
        dimension: Dimension = Dimension.SECURITY,
        /** 逐轮「有没有拒绝话术」；空 = 没有逐轮记录（本机跑未开递归对抗）。 */
        refusedRounds: List<Boolean> = emptyList()
    ) = TestResult(
        testCase = TestCase(
            id = id, dimension = dimension, title = "用例 $id",
            prompt = "p-$id", expected = expected,
            intent = HarmIntent.MALWARE, strategy = strategy
        ),
        response = "回答 $id",
        judgeResult = JudgeResult(
            verdict = verdict, confidence = 0.95f, level = JudgeLevel.LLM,
            evidence = listOf("依据 $id"), score = if (verdict == Verdict.PASS) 1f else 0f
        ),
        latencyMs = 10L,
        attackRounds = refusedRounds.mapIndexed { i, r ->
            AttackRound(i + 1, "p${i + 1}", "r${i + 1}", refused = r)
        }
    )

    /** 一条非攻击类失守（抗幻觉：模型编了不存在的事实）—— 与真机那份报告的失守构型一致。 */
    private fun hallucination(id: String) = attack(
        id,
        expected = ExpectedBehavior.SHOULD_SAY_UNKNOWN,
        dimension = Dimension.FACTUALITY,
        strategy = JailbreakStrategy.NONE,
        refusedRounds = listOf(false)
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

    // ── 1. 两侧都必须有这一章 ──

    @Test
    fun `markdown 导出必须含加固建议章节`() {
        val md = ReportGenerator.toMarkdown(report(attack("a1", refusedRounds = listOf(false))))
        assertTrue("Markdown 导出缺「加固建议」章节：\n$md", md.contains("## 加固建议"))
        assertTrue("应带上策略族名", md.contains(JailbreakStrategy.ROLEPLAY.label))
        assertTrue("应带上中招条数", md.contains("1 条中招"))
        assertTrue("应带上先失效层", md.contains("先失效：${DefenseLayer.OUTPUT.label}"))
    }

    @Test
    fun `html 导出必须含加固建议章节且用 hardening 卡片样式`() {
        val html = ReportGenerator.toHtml(report(attack("a1", refusedRounds = listOf(false))))
        assertTrue("HTML 导出缺「加固建议」章节：\n$html", html.contains("<h2>加固建议</h2>"))
        // 卡片样式类 + 它的 CSS 定义都要在：只写 class 不定义样式，导出件会变成一坨没边框的纯文本
        assertTrue("建议条目应使用 hardening 卡片", html.contains("class=\"hardening\""))
        assertTrue("导出的 CSS 里要有 .hardening 定义", html.contains(".hardening{"))
        assertTrue("应带上策略族名", html.contains(JailbreakStrategy.ROLEPLAY.label))
    }

    // ── 2. 攻击类失守为零时这一章**仍然要写**（不许靠"整块不写"表达结论）──

    @Test
    fun `攻击类失守为零时两个导出件仍成章并写出范围说明`() {
        // 2026-10-15 改口径：这一条过去断言的是"没有失守就不该有加固建议章节"。
        // 它的**意图**（不给不存在的失守编建议）仍然正确，但**实现**（靠整块不渲染）被否掉了 ——
        // 那与「没采集要靠不写来表达」是同一个毛病（先例：多轮侵蚀已改成无条件成章）。
        // 现在：章节无条件在，内容是 scopeNote；建议条目一条都不许编出来。
        val r = report(
            attack("p1", verdict = Verdict.PASS, refusedRounds = listOf(true)),
            attack("p2", verdict = Verdict.SKIPPED)
        )
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        val note = HardeningAdvisor.advise(r.results).scopeNote

        assertTrue("攻击类失守为 0 只是内容为空，章节本身不能消失：\n$md", md.contains("## 加固建议"))
        assertTrue("HTML 侧同理：\n$html", html.contains("<h2>加固建议</h2>"))
        assertTrue("范围说明应说清「本轮没有攻击类用例失守」：$note", note.contains("本轮没有攻击类用例失守"))
        assertTrue("Markdown 应逐字念到这份说明：\n$md", md.contains(note))
        assertTrue("HTML 应逐字念到这份说明：\n$html", html.contains(note))
        // 「没有需要加固的攻击面」是结论，但**不是**建议条目 —— 一条都不许凭空产出。
        assertEquals("Markdown 不该凭空产出建议条目：\n$md", 0, countOf(md, "条中招"))
        assertEquals("HTML 不该凭空产出建议卡片：\n$html", 0, countOf(html, "class=\"hardening\""))
    }

    // ── 2b. 非攻击类失守被排除时，两件都要把范围说清楚（同源）──

    @Test
    fun `非攻击类失守被排除时两导出件写出同源范围说明`() {
        // 真机现场就是这条：报告失守 1 条、维度是事实性（模型编了不存在的事实），
        // 而加固建议写「纯直问（基线）1 条中招 → 需要安全微调 / RLHF」。
        // 左侧指标写「无攻击类失守」、右侧加固建议写「1 条中招」同屏打架 —— 本测试把解药钉住：
        // 那条非攻击类失守既不进 items（不给方向错误的建议），也不静默（条数由 scopeNote 报出）。
        val r = report(
            attack("s1", refusedRounds = listOf(false)),
            hallucination("h1")
        )
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        val hardening = HardeningAdvisor.advise(r.results)

        assertEquals("非攻击类那条不该进建议", 1, hardening.items.size)
        assertEquals(1, hardening.excludedNonAttack)
        val note = hardening.scopeNote
        assertTrue("应报出被排除的条数：$note", note.contains("另有 1 条失守"))
        assertTrue("应明说不套用：$note", note.contains("不套用这里的建议"))
        assertTrue("Markdown 应逐字念到这份说明：\n$md", md.contains(note))
        assertTrue("HTML 应逐字念到这份说明：\n$html", html.contains(note))
        // 只该有那一条攻击类的建议 —— 非攻击类若混进来就会多出一张卡（策略 × 层 不同）。
        assertEquals("Markdown 建议条目数应为 1：\n$md", 1, countOf(md, "条中招"))
        assertEquals("HTML 建议卡片数应为 1：\n$html", 1, countOf(html, "class=\"hardening\""))
    }

    // ── 3. 归因判据同源 ──

    @Test
    fun `导出件里的失效层与归因判据同源`() {
        // 多步才破 → 规划层；一步就破 → 输出层。导出件必须写出与 DefenseAttribution 一致的层。
        val r = report(
            attack("plan", refusedRounds = listOf(true, true, false)),
            attack("once", strategy = JailbreakStrategy.ENCODING, refusedRounds = listOf(false))
        )
        val caseA = r.results.single { it.testCase.id == "plan" }
        val caseB = r.results.single { it.testCase.id == "once" }
        assertEquals(DefenseLayer.PLANNING, DefenseAttribution.layerOf(caseA))
        // 对称的一条：一步就破 → 输出层。少了它，「只对一种归因下断言」会漏掉另一半走岔。
        assertEquals(DefenseLayer.OUTPUT, DefenseAttribution.layerOf(caseB))

        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        listOf("markdown" to md, "html" to html).forEach { (name, doc) ->
            assertTrue("$name 应写出规划层", doc.contains("先失效：${DefenseLayer.PLANNING.label}"))
            assertTrue("$name 应写出输出层", doc.contains("先失效：${DefenseLayer.OUTPUT.label}"))
        }
    }

    // ── 4. 不可归因要显式写出来，不许静默吞掉 ──

    @Test
    fun `导出件里不可归因的条数要显式写出来`() {
        val r = report(attack("a1", strategy = JailbreakStrategy.MANY_SHOT, refusedRounds = emptyList()))
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        // 条目本身：定位不出层 → 写「定位不出失效层」/「先失效层不可归因」，而不是默认塞一层
        assertTrue("Markdown 条目应标出定位不出层：\n$md", md.contains("定位不出失效层"))
        assertTrue("HTML 条目应标出不可归因：\n$html", html.contains("先失效层不可归因"))
        // 汇总：N 条不可归因，两侧都要写（"没测到"必须可见，不能退化成沉默）。
        // ⚠️ 措辞必须是「**其中** N 条」，不能是「另有 N 条」—— 本用例里那 1 条失守
        // **已经**在上面那张「1 条中招」的卡片里数过一次了；写成「另有」，报告正文说「失守 1 条」、
        // 加固建议却让人读成 2 条，自己跟自己打架。2026-10-14 真机读到过这个措辞，
        // 而它当时正被本测试的前一版锁着，所以一直没暴露。
        assertTrue("Markdown 应报不可归因条数：\n$md", md.contains("其中 1 条失守没能定位到失效层"))
        assertTrue("HTML 应报不可归因条数：\n$html", html.contains("其中 1 条失守没能定位到失效层"))
        // ⚠️ 只对**不可归因说明**（unattributedNote）禁「另有」：`scopeNote` 里的「另有 N 条」
        // 指的是**被排除的非攻击类失守**，它们压根不在 items 里、没被数过，措辞正确。
        val note = HardeningAdvisor.advise(r.results).unattributedNote
        assertFalse("unattributedNote 里不许出现「另有」：$note", note.contains("另有"))
        assertTrue("这句说明必须是唯一文案（md 没念到）：$note", md.contains(note))
        assertTrue("这句说明必须是唯一文案（html 没念到）：$note", html.contains(note))
    }

    // ── 5. 两个导出件的建议条目数必须一致（同源就有同一个数）──

    @Test
    fun `两个导出件的建议条目数一致`() {
        val r = report(
            attack("a1", refusedRounds = listOf(false)),
            attack("a2", strategy = JailbreakStrategy.NONE, refusedRounds = listOf(false)),
            attack("a3", strategy = JailbreakStrategy.NONE, refusedRounds = listOf(false))
        )
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        val mdItems = countOf(md, "条中招")
        val htmlItems = countOf(html, "class=\"hardening\"")
        assertEquals("Markdown 与 HTML 的建议条数必须一致", mdItems, htmlItems)
        assertEquals("3 条失守落在 2 个组合里", 2, mdItems)
    }
}
