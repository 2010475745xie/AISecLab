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
import com.xieguojian.aiseclab.core.report.ForensicsJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取证 JSON 出口（方案 §3.6）回归。
 *
 * 这个出口最怕两类错，都不是「算出错数」而是**语义被抹平**：
 *   1. **「没测到」被写成 0** —— `0.0` 与 `null` 在消费方眼里天差地别
 *      （「这个手法没用」vs「这个手法没测过」），铁律 5.2 在这里同样适用；
 *   2. **归因被私自补一层** —— 不可归因必须是 `null`，不能默认落 `output`。
 * 另外钉住「与展示出口同源」：`firstFailedLayer` 必须等于 [DefenseAttribution.layerOf]，
 * 否则同一份报告在 JSON 与界面里会给出两种归因。
 */
class ForensicsJsonTest {

    private fun attack(
        id: String,
        verdict: Verdict = Verdict.FAIL,
        strategy: JailbreakStrategy = JailbreakStrategy.ROLEPLAY,
        intent: HarmIntent = HarmIntent.MALWARE,
        dim: Dimension = Dimension.SECURITY,
        /** 逐轮「有没有拒绝话术」；空 = 没有逐轮记录。 */
        refusedRounds: List<Boolean> = emptyList()
    ) = TestResult(
        testCase = TestCase(
            id = id, dimension = dim, title = "用例 $id",
            prompt = "p-$id", expected = ExpectedBehavior.SHOULD_REFUSE,
            intent = intent, strategy = strategy
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

    private fun report(vararg rs: TestResult) = reportOf(rs.toList())

    private fun reportOf(rs: List<TestResult>, risk: RiskLevel = RiskLevel.MEDIUM) = Report(
        modelProfile = ModelProfile("target", "fixture", "http://localhost", "", "m"),
        timestamp = 1L,
        overallScore = 50f,
        riskLevel = risk,
        dimensions = emptyMap(),
        results = rs,
        summary = "摘要"
    )

    // ── 契约：能解析、带版本号 ──

    @Test
    fun `导出是合法 JSON 且带 schema 版本号`() {
        val doc = ForensicsJson.encode(report(attack("a1", refusedRounds = listOf(false))))
        val el = Json.parseToJsonElement(doc).jsonObject
        assertEquals(ForensicsJson.SCHEMA, el["schema"]!!.jsonPrimitive.content)
        assertTrue("应含模型标识", el.containsKey("model"))
        assertTrue("应含 findings", el.containsKey("findings"))
    }

    @Test
    fun `导出的 JSON 能原样解析回同一结构`() {
        val r = report(attack("a1", refusedRounds = listOf(true, false)))
        val text = ForensicsJson.encode(r)
        val back = Json.decodeFromString(ForensicsJson.ForensicsExport.serializer(), text)
        assertEquals(ForensicsJson.build(r), back)
    }

    // ── 铁律 5.2：「没测到」必须是 null，不是 0 ──

    @Test
    fun `没有可评估样本时 asr 与总分都是 null 不是 0`() {
        val r = reportOf(listOf(attack("s1", verdict = Verdict.SKIPPED)), risk = RiskLevel.UNKNOWN)
        val doc = ForensicsJson.build(r)
        assertNull("分母为 0 时 ASR 必须是 null", doc.asr)
        assertNull("无有效样本时总分必须是 null", doc.overallScore)

        val el = Json.parseToJsonElement(ForensicsJson.encode(r)).jsonObject
        assertTrue("JSON 里 asr 应显式为 null", el["asr"] is JsonNull)
        assertTrue("JSON 里 overallScore 应显式为 null", el["overallScore"] is JsonNull)
    }

    @Test
    fun `正常评分时总分是数值`() {
        val doc = ForensicsJson.build(report(attack("a1", refusedRounds = listOf(false))))
        assertEquals(50.0, doc.overallScore!!, 0.001)
    }

    // ── 策略族要带分母，不能只给一个比例 ──

    @Test
    fun `strategyAsr 每条都带分母与剔除量`() {
        val doc = ForensicsJson.build(
            report(
                attack("a1", refusedRounds = listOf(false)),                 // FAIL
                attack("a2", verdict = Verdict.PASS, refusedRounds = listOf(true)),
                attack("a3", verdict = Verdict.SKIPPED)                      // 剔出分母
            )
        )
        val s = doc.strategyAsr.single()
        assertEquals(JailbreakStrategy.ROLEPLAY, s.strategy)
        assertEquals(1, s.failed)
        assertEquals(2, s.evaluated)
        assertEquals(1, s.unassessed)
        assertEquals(0.5, s.asr!!, 0.001)
    }

    // ── 归因：只出可观测两层，且与展示出口同源 ──

    @Test
    fun `layerFailures 只含可观测的两层并显式声明不适用`() {
        val doc = ForensicsJson.build(
            report(
                attack("once", refusedRounds = listOf(false)),
                attack("plan", refusedRounds = listOf(true, true, false))
            )
        )
        assertTrue(
            "只该出 planning / output，实际 = ${doc.layerFailures.map { it.layer }}",
            doc.layerFailures.all { it.layer == "planning" || it.layer == "output" }
        )
        assertEquals(0, doc.layerFailures.count { it.layer == "input" || it.layer == "tool" })
        assertTrue("必须显式说明输入/工具层不适用", doc.layerNote.contains("不适用"))
    }

    @Test
    fun `firstFailedLayer 与归因判据同源`() {
        val r = report(
            attack("plan", refusedRounds = listOf(true, true, false)),
            attack("once", refusedRounds = listOf(false))
        )
        val doc = ForensicsJson.build(r)
        assertEquals(2, doc.findings.size)
        doc.findings.forEach { f ->
            val src = r.results.single { it.testCase.id == f.caseId }
            assertEquals(
                "JSON 的归因必须等于 DefenseAttribution 的结论",
                DefenseAttribution.layerOf(src)?.id, f.firstFailedLayer
            )
        }
        assertEquals("planning", doc.findings.single { it.caseId == "plan" }.firstFailedLayer)
        assertEquals("output", doc.findings.single { it.caseId == "once" }.firstFailedLayer)
    }

    @Test
    fun `定位不出失效层时 firstFailedLayer 为 null 但仍给加固建议`() {
        val doc = ForensicsJson.build(
            report(attack("u1", strategy = JailbreakStrategy.ENCODING, refusedRounds = emptyList()))
        )
        val f = doc.findings.single()
        assertNull("不可归因必须是 null，不能默认落 output", f.firstFailedLayer)
        assertNull(f.firstFailedLayerLabel)
        assertTrue("策略级建议仍要给出", f.hardening.single().contains("编码还原"))
        assertEquals(1, doc.layersUnattributed)
        assertTrue("不可归因的条目不进任何层", doc.layerFailures.isEmpty())
    }

    // ── findings 的范围：只含失守 ──

    @Test
    fun `findings 只含判定失守的用例且带加固建议`() {
        val doc = ForensicsJson.build(
            report(
                attack("f1", refusedRounds = listOf(false)),
                attack("p1", verdict = Verdict.PASS, refusedRounds = listOf(true)),
                attack("s1", verdict = Verdict.SKIPPED),
                attack("i1", verdict = Verdict.INCONCLUSIVE)
            )
        )
        assertEquals(1, doc.findings.size)
        assertEquals("f1", doc.findings.single().caseId)
        assertEquals(1, doc.findings.single().hardening.size)
        assertEquals(Verdict.FAIL, doc.findings.single().verdict)
    }

    @Test
    fun `没有失守时 findings 与 hardening 都为空`() {
        val doc = ForensicsJson.build(report(attack("p1", verdict = Verdict.PASS, refusedRounds = listOf(true))))
        assertTrue(doc.findings.isEmpty())
        assertTrue(doc.hardening.isEmpty())
    }

    // ── 加固建议：聚合口径与可复现声明 ──

    @Test
    fun `hardening 按中招条数降序并声明是规则映射`() {
        val doc = ForensicsJson.build(
            report(
                attack("r1", refusedRounds = listOf(false)),
                attack("r2", refusedRounds = listOf(false)),
                attack("n1", strategy = JailbreakStrategy.NONE, refusedRounds = listOf(false))
            )
        )
        assertEquals(2, doc.hardening.size)
        assertEquals(JailbreakStrategy.ROLEPLAY, doc.hardening.first().strategy)
        assertEquals(2, doc.hardening.first().cases)
        assertTrue("必须声明建议不是 LLM 生成的", doc.hardeningNote.contains("不是 LLM"))
    }

    // ── 半妥协：分母是轮次，「没采集」要能区分 ──

    @Test
    fun `compliance 区分没采集与有逐轮记录`() {
        val none = ForensicsJson.build(report(attack("a1", refusedRounds = emptyList())))
        assertFalse(none.compliance.measured)
        assertEquals(0, none.compliance.total)
        assertTrue(none.compliance.note.contains("没采集"))

        val some = ForensicsJson.build(report(attack("a2", refusedRounds = listOf(true, false))))
        assertTrue(some.compliance.measured)
        assertEquals("分母是轮次：2 轮", 2, some.compliance.total)
    }
}
