package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.judge.ExternalJudgeRequest
import com.xieguojian.aiseclab.core.judge.ExternalJudgeResult
import com.xieguojian.aiseclab.core.judge.StubExternalJudge
import com.xieguojian.aiseclab.core.judge.UnavailableExternalJudge
import com.xieguojian.aiseclab.core.judge.agreementOf
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.ExternalCheck
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.ReportGenerator
import com.xieguojian.aiseclab.core.report.externalNote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部裁判接入形状（B4）—— 一根针挑三件事：
 *
 *  1. **只对照、不回写**：外部判定绝不改本地 verdict，分歧只记 id 供人回去看；
 *  2. **失败 ≠ 分歧**：外部拿不到结果（调用失败/不判）进 `unavailable`，
 *     「没测到」不能被算成「不同意」（铁律 5.2 的同一条）；
 *  3. **「未接入」不能被写成「0% 一致」**：没配外部裁判时压根不产出对照结论，
 *     而不是产出一个一致率 0 的结论。
 *
 * 桩（[StubExternalJudge]）只证明**接线通**，不证明任何外部效度 —— 真正的第二方
 * 印证要接真供应商（需要 key 与配额），那是后续项。
 */
class ExternalJudgeTest {

    // ── 接线：真跑一遍 suspend 接口（请求带什么、结果怎么进 map）──

    @Test
    fun `跑一遍真实接线_桩按回答文本判且被翻反的进分歧名单`() = runBlocking {
        val provider = StubExternalJudge(forceDisagreeIds = setOf("b"))
        val local = listOf(
            result("a", Verdict.PASS, response = "抱歉，我不能帮你做这件事。"),
            result("b", Verdict.PASS, response = "抱歉，我不能帮你做这件事。"),
            result("c", Verdict.FAIL, response = "第一步，准备原料……"),
        )
        // 接线形状：外部裁判只拿到「用例 id + 攻击输入 + 原始回答」，**不给它本地判定**（避免锚定）。
        val ext = local.associate { r ->
            r.testCase.id to provider.classify(
                ExternalJudgeRequest(r.testCase.id, r.testCase.prompt, r.response)
            )
        }
        assertEquals("桩：读起来像拒绝 → PASS", Verdict.PASS, ext["a"]!!.verdict)
        assertEquals("桩：读起来不是拒绝 → FAIL", Verdict.FAIL, ext["c"]!!.verdict)

        val chk = agreementOf(provider, local, ext)!!
        assertEquals(3, chk.compared)
        assertEquals(2, chk.agreed)
        assertEquals(listOf("b"), chk.disagreementIds)
        assertEquals(0, chk.unavailable)
    }

    // ── 计数口径 ──

    @Test
    fun `一致与分歧分开计_分歧带用例id`() {
        val local = listOf(
            result("a", Verdict.PASS), result("b", Verdict.FAIL), result("c", Verdict.PASS)
        )
        val ext = mapOf(
            "a" to ok(Verdict.PASS),
            "b" to ok(Verdict.PASS),   // 本地 FAIL、外部 PASS → 分歧
            "c" to ok(Verdict.PASS),
        )
        val chk = agreementOf(StubExternalJudge(), local, ext)!!
        assertEquals(3, chk.compared)
        assertEquals(2, chk.agreed)
        assertEquals(listOf("b"), chk.disagreementIds)
        assertEquals(2f / 3f, chk.rate!!, 1e-6f)
        // 只对照不回写：本地判定原样不动
        assertEquals(Verdict.FAIL, local[1].judgeResult.verdict)
    }

    @Test
    fun `本地没明确判定就不进分母也不记不可用`() {
        // SKIPPED = 接口失败；INCONCLUSIVE = 裁判没形成多数。两者都**没有可对照的结论**，
        // 算进分母会凭空拉低一致率；而「外部不可用」说的是外部没给结果，不是这一回事。
        val local = listOf(
            result("a", Verdict.PASS),
            result("b", Verdict.SKIPPED),
            result("c", Verdict.INCONCLUSIVE),
        )
        val ext = mapOf(
            "a" to ok(Verdict.PASS),
            "b" to ok(Verdict.FAIL),   // 就算外部给了相反判定，本地没结论 → 不该算分歧
            "c" to ok(Verdict.FAIL),
        )
        val chk = agreementOf(StubExternalJudge(), local, ext)!!
        assertEquals(1, chk.compared)
        assertEquals(1, chk.agreed)
        assertTrue("本地没结论的条目不该被算成外部不同意", chk.disagreementIds.isEmpty())
        assertEquals("本地不可评估 ≠ 外部不可用", 0, chk.unavailable)
    }

    @Test
    fun `外部拿不到结果记不可用而不是分歧`() {
        val local = listOf(
            result("a", Verdict.PASS), result("b", Verdict.PASS), result("c", Verdict.PASS)
        )
        val ext = mapOf(
            "a" to ok(Verdict.PASS),
            "b" to gone(),
            "c" to ok(Verdict.PASS),
        )
        val chk = agreementOf(StubExternalJudge(), local, ext)!!
        assertEquals(2, chk.compared)
        assertEquals(2, chk.agreed)
        assertEquals(1, chk.unavailable)
        assertTrue("拿不到结果不算分歧", chk.disagreementIds.isEmpty())
    }

    // ── 三态文案：未接入 / 没做成 / 真对照过 ──

    @Test
    fun `没配外部裁判时不产出对照结论而不是0Percent一致`() {
        val local = listOf(result("a", Verdict.PASS))
        // 默认实现就是「没配」→ 直接返回 null，而不是算出一个 compared = 0 的结论
        assertNull(
            "未配置时必须返回 null，否则报告会写出「已接入裁判（未接入）」这种自相矛盾的话",
            agreementOf(UnavailableExternalJudge, local, mapOf("a" to gone()))
        )

        val note = reportOf(local, externalCheck = null).externalNote()
        assertTrue(note, note.contains("未接入"))
        assertFalse("未接入不能显示成 0% 一致", note.contains("0%"))
        assertTrue("要提醒跨报告比较的前提", note.contains("外部效度"))
    }

    @Test
    fun `配了但一条都没拿到_说这次对照没做成而不是不一致`() {
        val local = (1..3).map { result("c$it", Verdict.PASS) }
        val ext = local.associate { it.testCase.id to gone() }
        val chk = agreementOf(StubExternalJudge(), local, ext)!!

        assertEquals(0, chk.compared)
        assertNull("compared 为 0 时一致率必须是 null —— 0 会被读成「完全不同意」", chk.rate)
        val note = reportOf(local, chk).externalNote()
        assertTrue(note, note.contains("没有做成"))
        assertTrue(note, note.contains("3 条不可用"))
        assertFalse("不能把「没测到」讲成分歧", note.contains("分歧 3 条"))
    }

    @Test
    fun `真对照过时给出一致率_分歧条数与不可用条数`() {
        val chk = ExternalCheck(
            providerId = "stub", providerLabel = "桩（仅测试）",
            compared = 4, agreed = 3, disagreementIds = listOf("x"), unavailable = 2,
        )
        val note = reportOf(emptyList(), chk).externalNote()
        assertTrue(note, note.contains("75%"))
        assertTrue(note, note.contains("分歧 1 条"))
        assertTrue("分歧要能点回用例", note.contains("x"))
        assertTrue(note, note.contains("另有 2 条"))
        assertTrue("必须重申只对照不回写", note.contains("不回写判定"))
    }

    // ── 渲染与落盘：三个出口 + 历史报告 ──

    @Test
    fun `两个导出件都带外部对照小节且与报告页同源`() {
        val chk = ExternalCheck(
            providerId = "stub", providerLabel = "桩（仅测试）",
            compared = 2, agreed = 1, disagreementIds = listOf("b"),
        )
        val r = reportOf(listOf(result("a", Verdict.PASS)), chk)
        val md = ReportGenerator.toMarkdown(r)
        val html = ReportGenerator.toHtml(r)
        assertTrue("Markdown 缺外部对照小节", md.contains("## 外部对照"))
        assertTrue("HTML 缺外部对照小节", html.contains("<h2>外部对照</h2>"))
        // 同源：导出里的那一句必须就是 externalNote() 本身
        assertTrue("Markdown 里的外部对照与 externalNote 不同源", md.contains(r.externalNote()))
        assertTrue("HTML 里的外部对照与 externalNote 不同源", html.contains(r.externalNote()))
    }

    @Test
    fun `落盘再读回不丢对照结果`() {
        // 历史报告走 JSON 往返（HistoryRepository）。字段没接上读写两侧之一，
        // 表现就是「昨天看的对照结果，今天打开变回未接入」—— 静默、不报错。
        val json = Json { ignoreUnknownKeys = true }
        val r = reportOf(
            listOf(result("a", Verdict.PASS)),
            ExternalCheck("stub", "桩", compared = 3, agreed = 2, disagreementIds = listOf("b"), unavailable = 1)
        )
        val back = json.decodeFromString(
            Report.serializer(), json.encodeToString(Report.serializer(), r)
        )
        assertEquals(r.externalCheck, back.externalCheck)

        // 旧报告（没有这个字段）读回来必须是「未接入」，不能抛异常
        val legacy = """{"modelProfile":{"id":"m","name":"","baseUrl":"http://x","apiKey":"","model":"m"},
            "timestamp":0,"overallScore":100.0,"riskLevel":"low","dimensions":{},"results":[],"summary":""}"""
        assertNull(json.decodeFromString(Report.serializer(), legacy).externalCheck)
    }

    // ── 造数据 ──────────────────────────────────────────────────────

    private fun result(id: String, verdict: Verdict, response: String = "回答") = TestResult(
        testCase = TestCase(
            id = id, dimension = Dimension.SECURITY, title = id, prompt = "攻击输入",
            expected = ExpectedBehavior.SHOULD_REFUSE
        ),
        response = response,
        judgeResult = JudgeResult(
            verdict = verdict, confidence = 1f, level = JudgeLevel.L1_PATTERN, score = 1f
        ),
        latencyMs = 0L,
    )

    /** 外部给出了明确判定。 */
    private fun ok(v: Verdict) = ExternalJudgeResult(verdict = v, confidence = 0.9f, raw = "stub")

    /** 外部**没给出**结果（调用失败 / 不判 / 配额用尽）。 */
    private fun gone() = ExternalJudgeResult(verdict = null, error = "桩：模拟调用失败")

    private fun reportOf(results: List<TestResult>, externalCheck: ExternalCheck?) = Report(
        modelProfile = ModelProfile(id = "t", name = "", baseUrl = "http://x", apiKey = "", model = "m"),
        timestamp = 0L,
        overallScore = 100f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = results,
        summary = "",
        externalCheck = externalCheck,
    )
}
