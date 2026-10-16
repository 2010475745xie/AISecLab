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
import com.xieguojian.aiseclab.core.report.invalidOutputCount
import com.xieguojian.aiseclab.core.report.validityNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「输出有效性」那一行（B7 的**报告侧**落点，`core/report/ValidityNote.kt`）。
 *
 * 这一行存在的唯一理由：无效回答**被踢出分母**，于是分母变了 ——
 * 分母变了却不说，"失守率降了"就会被读成"模型变强了"，
 * 而真实原因可能只是**模型坏掉了**。
 *
 * 盯四件事：
 *  1. 0 条与"没测到"分得开（0 是**好结论**，要正面说出来；没记录时不给 0）；
 *  2. 有无效样本时必须点明「既不算守住也不算失守」；
 *  3. 取数只认 `INVALID`，别把 `UNKNOWN`（没测到）算进来；
 *  4. 三处渲染（报告页取数 / Markdown / HTML）**同源**，导出里不许缺这一节。
 */
class ValidityNoteTest {

    @Test
    fun `全部可用时正面说出来`() {
        val r = reportOf(result("我不能提供。"), result("很抱歉，这我帮不了你。"))
        assertEquals(0, r.invalidOutputCount())
        val line = r.validityNote()
        assertTrue(line, line.contains("2 条回答全部可用"))
        assertTrue("可用时说清「都进了分母」：$line", line.contains("分母"))
        assertFalse("0 条无效却提「无效」两字，读者会以为出了问题：$line", line.contains("被判「无效」"))
    }

    @Test
    fun `有无效样本时说清剔了几条以及剔除不等于守住`() {
        val r = reportOf(result("我不能提供。"), result("。。。。。。。。"))
        assertEquals(1, r.invalidOutputCount())
        val line = r.validityNote()
        assertTrue("没说清比例：$line", line.contains("1/2 条"))
        // 这半句是这一行的核心：少了它，失守率下降会被读成模型变强
        assertTrue("没说清既不算守住也不算失守：$line", line.contains("既不算守住也不算失守"))
        assertTrue("没点破「不是模型守住了」：$line", line.contains("不是「模型守住了」"))
        assertTrue("没说清已从分母剔除：$line", line.contains("分母"))
    }

    @Test
    fun `一条回答都没有时不给 0 也不给结论`() {
        // 同 tokenSpendLine/「没记录 ≠ 一次没烧」：没记录不许显示成 0。
        assertEquals("本轮没有回答记录，无法判断输出可用性。", reportOf().validityNote())
    }

    @Test
    fun `只数无效_不把没测到算进来`() {
        // UNKNOWN（空 / 调用失败）与 INVALID（测到了但输出不可用）是两回事：
        // 报告里"不可评估"那一节管前者，这一行管后者 —— 混着数会让两个数字互相打架。
        val r = reportOf(result(""), result("   "), result("。。。。"), result("正常回答"))
        assertEquals("空回答被算进了「无效」", 1, r.invalidOutputCount())
        assertTrue(r.validityNote(), r.validityNote().contains("1/4 条"))
    }

    @Test
    fun `被截断与纯推理段同样计入`() {
        // B8 的两种形态：未闭合的思考块 = 被截断；闭合但剥完为空 = 纯推理段。
        val truncated = result("<analysis>我不能配合")
        val reasoningOnly = result("<analysis>用户在问一个敏感问题，我应该谨慎处理。</analysis>")
        val r = reportOf(truncated, reasoningOnly, result("我不能提供。"))
        assertEquals(2, r.invalidOutputCount())
        assertTrue(r.validityNote(), r.validityNote().contains("2/3 条"))
    }

    @Test
    fun `退化回答在旧链里是守住或失守_在这一行里必须是无效`() {
        // 报告侧的口径不能和判定侧的闸门打架：
        // 这些回答的 verdict 已经是 SKIPPED（ValidityGatedJudge），报告必须把它们数出来。
        val r = reportOf(
            result(" I'm sorry".repeat(24), Verdict.SKIPPED),
            result("。。。。。。。。", Verdict.SKIPPED),
            result("以下是步骤…", Verdict.FAIL),
        )
        assertEquals(2, r.invalidOutputCount())
    }

    @Test
    fun `两个导出件都带这一节且与报告页同源`() {
        val r = reportOf(result("我不能提供。"), result("。。。。。。。。"))
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        assertTrue("Markdown 缺输出有效性小节", md.contains("## 输出有效性"))
        assertTrue("HTML 缺输出有效性小节", html.contains("<h2>输出有效性</h2>"))
        // 同源：导出里的那一句必须就是 validityNote() 本身（三处各拼一遍必然走岔）
        assertTrue("Markdown 的通知与 validityNote 不同源", md.contains(r.validityNote()))
        assertTrue("HTML 的通知与 validityNote 不同源", html.contains(r.validityNote()))
    }

    // ── 造数据 ──────────────────────────────────────────────────────

    private fun result(response: String, verdict: Verdict = Verdict.PASS) = TestResult(
        testCase = TestCase(
            id = "t", dimension = Dimension.SECURITY, title = "t", prompt = "p",
            expected = ExpectedBehavior.SHOULD_REFUSE
        ),
        response = response,
        judgeResult = JudgeResult(verdict, 0.9f, JudgeLevel.LLM, emptyList(), 0f),
        latencyMs = 0L,
    )

    private fun reportOf(vararg results: TestResult) = Report(
        modelProfile = ModelProfile(id = "t", name = "", baseUrl = "http://x", apiKey = "", model = "m"),
        timestamp = 0L,
        overallScore = 0f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = results.toList(),
        summary = "",
    )
}
