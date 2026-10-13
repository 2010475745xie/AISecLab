package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.HarmIntent
import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 取证报告的**机器可读出口**（方案 §3.6 JSON 骨架）。
 *
 * 已经有三个给人看的出口（报告页 / Markdown / HTML），这一个给**程序**看：
 * 报告落盘归档、服务端汇总、将来多模型横向对比（4.4）都要靠它。
 * 所以它的口径必须和那三个出口**完全同源** —— 全部取自
 * [SafetyMetrics] / [ErosionAnalysis] / [HardeningAdvisor] / [DefenseAttribution]，
 * 本文件**不做任何新判定、不重算任何一个数**。
 *
 * ── 与方案 §3.6 草案骨架的三处差异（都是有意的）──
 *
 * 1. **没有 `ForensicsReport` 那个壳**。草案把各项塞进一个新 data class，实际直接落
 *    在已有管线里，这里只是**序列化出口**，不复制一份报告结构（复制出来的那份迟早会走岔）。
 * 2. **`strategyAsr` 是对象数组而不是 map**。草案写成 `{"roleplay": 0.45}`，
 *    那样丢掉了分母与剔除量。数组里每条带 `failed / evaluated / unassessed`，
 *    「这个策略族到底测了几条」不必再去别处找。
 * 3. **`layerFailures` 只含 `planning` / `output`**。草案骨架里还有 `input` / `tool`，
 *    但被测是纯 chat API，这两层**不可观测**：报 0 等于宣称「它们没失效」，那是编的。
 *    它们由 [LAYER_NOTE] 显式声明为「不适用」，不是悄悄省略。
 *
 * ── 「没测到」在 JSON 里怎么表达 ──
 *
 * 一律用 `null`，**绝不用 0**：`asr: null` = 没有可评估样本；`p50: null` = 没有定位出
 * 破防步；`firstFailedLayer: null` = 不可归因。消费方必须把 null 与数值分开处理，
 * 这与铁律 5.2（0 ≠ 没测到）同一条口径。
 */
object ForensicsJson {

    /** 结构版本号。消费方靠它决定怎么解析，别靠字段猜。 */
    const val SCHEMA = "aiseclab.forensics/1"

    private const val LAYER_NOTE =
        "只归因可观测的两层：多步才破 → planning（意图/规划层，被逐步带偏）；" +
            "一次就破 → output（输出过滤层，第一句就吐了）。" +
            "input / tool 两层对纯 chat API 不可观测，属「不适用」而非「未失效」——" +
            "因此不出现在 layerFailures 里，也不计 0。"

    private const val HARDENING_NOTE =
        "加固建议来自有限规则映射表（HardeningAdvisor），不是 LLM 现场生成 —— 不调模型、结果可复算，" +
            "每条建议都能指回它依据的策略族与先失效层。按「策略族 × 先失效层」聚合，按中招条数降序。"

    private const val COMPLIANCE_NOTE =
        "半妥协是**文本测量**不是判定结论，分母是**轮次**而不是用例条数，不能换算 ASR。" +
            "measured=false 表示本次没有逐轮记录（没采集），不是「没有半妥协」。"

    private val json = Json {
        prettyPrint = true
        // 带上默认值：消费方不该因为某字段恰好等于默认值就取不到它。
        encodeDefaults = true
    }

    // ── 顶层 ──

    @Serializable
    data class ModelRef(val model: String, val name: String)

    @Serializable
    data class BreakRound(val p50: Double?, val p90: Double?, val note: String)

    @Serializable
    data class StrategyAsrJson(
        val strategy: JailbreakStrategy,
        val label: String,
        val asr: Double?,
        val failed: Int,
        val evaluated: Int,
        val unassessed: Int
    )

    @Serializable
    data class LayerFailureJson(val layer: String, val label: String, val count: Int)

    @Serializable
    data class ComplianceJson(
        val measured: Boolean,
        val total: Int,
        val partial: Int,
        val refused: Int,
        val complied: Int,
        val unknown: Int,
        val note: String
    )

    @Serializable
    data class HardeningJson(
        val strategy: JailbreakStrategy,
        val strategyLabel: String,
        /** 先失效的护栏层 id；**null = 定位不出**（不是 output）。 */
        val layer: String?,
        val layerLabel: String?,
        val cases: Int,
        val action: String,
        val snippet: String?
    )

    @Serializable
    data class FindingJson(
        val caseId: String,
        val dimension: Dimension,
        val title: String,
        val intent: HarmIntent,
        val strategy: JailbreakStrategy,
        val verdict: Verdict,
        val firstFailedLayer: String?,
        val firstFailedLayerLabel: String?,
        val evidence: List<String>,
        /** 该用例落到的那条加固建议（策略族级）；没有对应建议时为空数组。 */
        val hardening: List<String>
    )

    @Serializable
    data class ForensicsExport(
        val schema: String,
        val timestamp: Long,
        val model: ModelRef,
        val riskLevel: RiskLevel,
        /** null = 一条都没评上（无有效样本），**不是 0 分**。 */
        val overallScore: Double?,
        val asr: Double?,
        val evaluated: Int,
        val failed: Int,
        val unassessed: Int,
        val strategyAsr: List<StrategyAsrJson>,
        val breakRound: BreakRound,
        val layerFailures: List<LayerFailureJson>,
        val layersUnattributed: Int,
        val layerNote: String,
        val compliance: ComplianceJson,
        val hardening: List<HardeningJson>,
        val hardeningNote: String,
        /** 只含**判定失守**的用例（PASS / 不可评估不进来）。 */
        val findings: List<FindingJson>
    )

    /**
     * 把报告编码成取证 JSON。与三个展示出口同源，本函数不含任何新的判定逻辑。
     */
    fun encode(report: Report): String = json.encodeToString(
        ForensicsExport.serializer(),
        build(report)
    )

    /** 结构化版本（便于单测断言，不必反复 parse 字符串再断言）。 */
    fun build(report: Report): ForensicsExport {
        val erosion = ErosionAnalysis.of(report.results)
        val metrics = SafetyMetrics.of(report.results, erosion)
        val sections = ReportSections.of(report)
        val hardening = HardeningAdvisor.advise(sections.fails)

        // 「策略族 × 先失效层」→ 建议，供 findings 逐条回填它自己那条。
        // 键的第二项允许为 null（不可归因），与 DefenseAttribution 的返回值一致。
        val adviceByKey = hardening.items.associateBy { it.strategy to it.layer }

        return ForensicsExport(
            schema = SCHEMA,
            timestamp = report.timestamp,
            model = ModelRef(report.modelProfile.model, report.modelProfile.name),
            riskLevel = report.riskLevel,
            overallScore = if (report.riskLevel == RiskLevel.UNKNOWN) null
            else report.overallScore.toDouble(),
            asr = metrics.asr?.toDouble(),
            evaluated = metrics.evaluated,
            failed = metrics.failed,
            unassessed = metrics.unassessed,
            strategyAsr = metrics.strategies.map {
                StrategyAsrJson(
                    strategy = it.strategy,
                    label = it.strategy.label,
                    asr = it.asr?.toDouble(),
                    failed = it.failed,
                    evaluated = it.evaluated,
                    unassessed = it.unassessed
                )
            },
            breakRound = BreakRound(
                p50 = metrics.breakRoundP50?.toDouble(),
                p90 = metrics.breakRoundP90?.toDouble(),
                note = metrics.breakRoundNote
            ),
            layerFailures = metrics.layers.map {
                LayerFailureJson(layer = it.layer.id, label = it.layer.label, count = it.count)
            },
            layersUnattributed = metrics.layersUnattributed,
            layerNote = LAYER_NOTE,
            compliance = ComplianceJson(
                measured = metrics.compliance.measured,
                total = metrics.compliance.total,
                partial = metrics.compliance.partial,
                refused = metrics.compliance.refused,
                complied = metrics.compliance.complied,
                unknown = metrics.compliance.unknown,
                note = COMPLIANCE_NOTE
            ),
            hardening = hardening.items.map {
                HardeningJson(
                    strategy = it.strategy,
                    strategyLabel = it.strategy.label,
                    layer = it.layer?.id,
                    layerLabel = it.layer?.label,
                    cases = it.cases,
                    action = it.action,
                    snippet = it.snippet
                )
            },
            hardeningNote = HARDENING_NOTE,
            findings = sections.fails.map { finding(it, adviceByKey) }
        )
    }

    private fun finding(
        r: TestResult,
        adviceByKey: Map<Pair<JailbreakStrategy, DefenseLayer?>, HardeningAdvisor.HardeningItem>
    ): FindingJson {
        // 归因判据与指标面板 / 加固建议**同一份实现**（DefenseAttribution），不各自再判一遍。
        val layer = DefenseAttribution.layerOf(r)
        return FindingJson(
            caseId = r.testCase.id,
            dimension = r.testCase.dimension,
            title = r.testCase.title,
            intent = r.testCase.intent,
            strategy = r.testCase.strategy,
            verdict = r.judgeResult.verdict,
            firstFailedLayer = layer?.id,
            firstFailedLayerLabel = layer?.label,
            evidence = r.judgeResult.evidence,
            hardening = listOfNotNull(adviceByKey[r.testCase.strategy to layer]?.action)
        )
    }
}
