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
import com.xieguojian.aiseclab.core.report.ReproGrade
import com.xieguojian.aiseclab.core.report.ReportGenerator
import com.xieguojian.aiseclab.core.report.reproGrade
import com.xieguojian.aiseclab.core.report.reproLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现性档（B5）—— 报告必须回答"别人照着重跑，能不能得到同一份结论"。
 *
 * 盯三件事：
 *  1. 档位取**最弱环节**（有 LLM 裁判就不可复现，别被"大部分是规则层"稀释）；
 *  2. 采样与裁判是**两个不同**的不可复现来源，别说成同一件事；
 *  3. 三处渲染（报告页取数 / Markdown / HTML）**同源**，导出里不许缺这一节。
 */
class ReproNoteTest {

    @Test
    fun `全规则层且单样本就是确定性`() {
        val r = reportOf(
            result(level = JudgeLevel.L1_PATTERN, measured = false),
            result(level = JudgeLevel.L3_STRUCTURAL, measured = false),
        )
        assertEquals(ReproGrade.DETERMINISTIC, r.reproGrade())
        assertTrue(r.reproLine(), r.reproLine().contains("确定性"))
        assertTrue(r.reproLine(), r.reproLine().contains("可逐字复现"))
    }

    @Test
    fun `多次采样但裁判仍是规则层_是采样可复现而不是确定性`() {
        val r = reportOf(
            result(level = JudgeLevel.L1_PATTERN, measured = false),
            result(level = JudgeLevel.L1_PATTERN, measured = true),   // 后端跑过多次采样
        )
        assertEquals(ReproGrade.SAMPLED, r.reproGrade())
        assertTrue(r.reproLine(), r.reproLine().contains("采样可复现"))
        // 必须说清波动来源是**目标侧采样**，而不是"判定会变"
        assertTrue(r.reproLine(), r.reproLine().contains("回答本身会波动"))
    }

    @Test
    fun `有 LLM 裁判就不可复现`() {
        val r = reportOf(
            result(level = JudgeLevel.L1_PATTERN, measured = false),
            result(level = JudgeLevel.LLM, measured = false),
        )
        assertEquals(ReproGrade.NON_DETERMINISTIC, r.reproGrade())
        assertTrue(r.reproLine(), r.reproLine().contains("不可复现"))
        assertTrue(r.reproLine(), r.reproLine().contains("LLM 裁判"))
    }

    @Test
    fun `档位取最弱环节_采样不会把不可复现稀释掉`() {
        // 20 条规则层 + 1 条 LLM 裁判 + 又开了采样 —— 仍然是不可复现。
        // 若按"比例"算，1/21 会被算成"整体还挺确定"，那是自欺。
        val many = (1..20).map { result(level = JudgeLevel.L1_PATTERN, measured = true) } +
            result(level = JudgeLevel.LLM, measured = true)
        assertEquals(ReproGrade.NON_DETERMINISTIC, reportOf(*many.toTypedArray()).reproGrade())
    }

    @Test
    fun `空结果不冒充确定性`() {
        val r = reportOf()
        assertEquals("没有回答记录时说不知道，而不是给一档", "本轮没有回答记录，无法评估复现性。", r.reproLine())
    }

    @Test
    fun `两个导出件都带复现性小节且与报告页同源`() {
        val r = reportOf(result(level = JudgeLevel.LLM, measured = false))
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        assertTrue("Markdown 缺复现性小节", md.contains("## 复现性"))
        assertTrue("HTML 缺复现性小节", html.contains("<h2>复现性</h2>"))
        // 同源：导出里的那一句必须就是 reproLine() 本身
        assertTrue("Markdown 里的复现性与 reproLine 不同源", md.contains(r.reproLine()))
        assertTrue("HTML 里的复现性与 reproLine 不同源", html.contains(r.reproLine()))
    }

    // ── 造数据 ──────────────────────────────────────────────────────

    private fun result(level: JudgeLevel, measured: Boolean) = TestResult(
        testCase = TestCase(
            id = "t", dimension = Dimension.SECURITY, title = "t", prompt = "p",
            expected = ExpectedBehavior.SHOULD_REFUSE
        ),
        response = "回答",
        judgeResult = JudgeResult(verdict = Verdict.PASS, confidence = 1f, level = level, score = 1f),
        latencyMs = 0L,
        stability = 1f,
        stabilityMeasured = measured,
    )

    private fun reportOf(vararg results: TestResult) = Report(
        modelProfile = ModelProfile(id = "t", name = "", baseUrl = "http://x", apiKey = "", model = "m"),
        timestamp = 0L,
        overallScore = 100f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = results.toList(),
        summary = "",
    )
}
