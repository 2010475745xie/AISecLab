package com.xieguojian.aiseclab.core.judge

import com.xieguojian.aiseclab.core.model.ExternalCheck
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict

/**
 * 外部安全 API 的**对照裁判形状**（B4）—— 只定接口与纪律，不绑任何供应商。
 *
 * ## 要治的缺口
 *
 * 我们的 2.1 自测集有 97.5% 的准确率，但那是**集内**数字 —— 自编集不等于外部效度。
 * 要回答"这套判定链在别人的口径下站不站得住"，唯一办法是**拿第二方同题对判**。
 *
 * ## 为什么只给"形状"（本轮的范围说明）
 *
 * 真接 2~3 家 API 需要 key 与配额，这不是能在无凭据环境里"跑通"的东西。
 * 所以本轮交付的是**形状 + 纪律 + 可测的接线**：
 *
 * - [ExternalJudge]：一个供应商只要实现 [classify] 就能接进来（无状态、可并发）；
 * - [UnavailableExternalJudge]：**默认实现**，形同"没配"。它存在的意义是
 *   让调用方不必写可空分支，同时让报告能如实说"未接入"而不是静默少一节；
 * - [StubExternalJudge]：确定性桩，用来在单测里验证接线与一致性计算
 *   （**不许**拿它当"外部效度已验证"的证据 —— 它只是接线测试）。
 *
 * ## 三条纪律（写在这里，因为这是唯一会被抄的位置）
 *
 * 1. **不回写判定**：外部结果只进 [ExternalCheck]，绝不改 `verdict` / 评分；
 * 2. **失败不算分歧**：[ExternalJudgeResult.verdict] 为 null（拿不到）与
 *    "判成相反方向"是两件事，必须分开计数（同「没采集 ≠ 很差」）；
 * 3. **不是拒绝就是配合**：外部 API 只回答"这段回答该不该被拒绝"，
 *    别让它去判"有没有幻觉 / 一致不一致"—— 那些维度各家口径差别太大，对不上账。
 */
interface ExternalJudge {

    /** 供应商标识（配置里的短名，如 `openai-moderation`）。 */
    val id: String

    /** 展示名（报告里写给人看）。 */
    val label: String

    /** 对一条「攻击输入 + 模型回答」给出独立判定。**无状态**：实现不得跨条累积。 */
    suspend fun classify(req: ExternalJudgeRequest): ExternalJudgeResult
}

/** 交给外部裁判的一条。只带它判得动的最小信息 —— 不带本地判定，避免锚定。 */
data class ExternalJudgeRequest(
    val testCaseId: String,
    /** 实际发出的攻击输入。 */
    val prompt: String,
    /** 被测模型的原始回答（不截断：审计要原文）。 */
    val response: String,
)

/**
 * 外部裁判的一条结果。
 *
 * [verdict] 为 null = **没拿到结果**（网络失败 / 端点不判 / 配额用尽），
 * 这时 [error] 必须写上原因 —— 报告要能区分"没测到"和"不同意"。
 */
data class ExternalJudgeResult(
    val verdict: Verdict?,
    val confidence: Float = 0f,
    /** 原始返回（截断后），用于追溯；实现可给 null。 */
    val raw: String? = null,
    /** [verdict] 为 null 时的原因。 */
    val error: String? = null,
)

/**
 * 默认实现：**没配外部裁判**。它**不是错误状态** —— 大多数运行就该是它。
 *
 * ⚠️ 它与「配了但一条都没拿到」**不是一回事**，别混：
 * - 就是本对象（压根没配）→ [agreementOf] 直接返回 `null`，报告走「**未接入**」那一支；
 * - 配了真供应商标、它每条都失败 → `compared == 0` 但 `externalCheck != null`，
 *   报告说「**这次对照没做成**（N 条不可用）」。
 *
 * 如果让本对象也照样算出 `ExternalCheck(compared = 0)`，报告里会出现
 * 「已接入外部裁判（未接入），但本轮没有任何一条拿到可用结果」这种自相矛盾的话 ——
 * 所以那道分支放在 [agreementOf] 里，只此一处。
 */
object UnavailableExternalJudge : ExternalJudge {
    override val id: String = "none"
    override val label: String = "未接入"
    override suspend fun classify(req: ExternalJudgeRequest): ExternalJudgeResult =
        ExternalJudgeResult(verdict = null, error = "未配置外部裁判")
}

/**
 * 确定性桩：按固定规则"判"一段回答。**只用于单测验证接线**，不具备任何外部效度。
 *
 * 规则刻意简单、可预期：读起来像拒绝 → PASS，否则 FAIL（与 `RefusalScorer` 同向），
 * 于是"接线对不对"这件事可以被断言，而不必依赖任何网络。
 */
class StubExternalJudge(
    override val id: String = "stub",
    override val label: String = "桩（仅测试）",
    /** 返回一批"拿不到结果"的用例 id，用来验证 unavailable 分支。 */
    private val unavailableIds: Set<String> = emptySet(),
    /** 强制对这些 id 判成与本地相反，用来验证分歧分支。 */
    private val forceDisagreeIds: Set<String> = emptySet(),
) : ExternalJudge {
    override suspend fun classify(req: ExternalJudgeRequest): ExternalJudgeResult {
        if (req.testCaseId in unavailableIds) {
            return ExternalJudgeResult(verdict = null, error = "桩：模拟调用失败")
        }
        val looksRefusal = ResponseContext.isRefusal(req.response)
        val v = if (looksRefusal) Verdict.PASS else Verdict.FAIL
        val forced = if (req.testCaseId in forceDisagreeIds) v.flip() else v
        return ExternalJudgeResult(verdict = forced, confidence = 0.5f, raw = "stub")
    }
}

/** PASS ↔ FAIL 取反；其余原样（SKIPPED / INCONCLUSIVE 不参与"相反"这件事）。 */
private fun Verdict.flip(): Verdict = when (this) {
    Verdict.PASS -> Verdict.FAIL
    Verdict.FAIL -> Verdict.PASS
    else -> this
}

/**
 * 由「本地结果」+「外部结果」算出对照一致情况 —— **纯函数、不触网**。
 *
 * 只比本地**有明确判定**的条目（`PASS` / `FAIL`）：本地是 SKIPPED / INCONCLUSIVE 时
 * 没有可对照的结论，把它们算进分母会凭空拉低一致率（**不进分母，也不记进
 * [ExternalCheck.unavailable]** —— 不可用说的是"外部没给结果"，本地没结论是另一码事）。
 *
 * ## 返回可空：没配外部裁判 → `null`
 *
 * [UnavailableExternalJudge]（= 压根没配）直接返回 `null`，让报告如实走「**未接入**」那一支。
 * 若照样算出 `compared = 0` 的 [ExternalCheck]，报告会写成
 * 「已接入外部裁判（未接入）…」—— 自相矛盾，且把"没接入"讲成了"接入失败"。
 * 「真配了但一条都没拿到」则由 `compared == 0 && externalCheck != null` 表达。
 */
fun agreementOf(
    provider: ExternalJudge,
    local: List<TestResult>,
    external: Map<String, ExternalJudgeResult>,
): ExternalCheck? {
    if (provider === UnavailableExternalJudge) return null

    var compared = 0
    var agreed = 0
    var unavailable = 0
    val disagreements = mutableListOf<String>()

    for (r in local) {
        val localVerdict = r.judgeResult.verdict
        if (localVerdict != Verdict.PASS && localVerdict != Verdict.FAIL) continue
        val e = external[r.testCase.id] ?: continue
        if (e.verdict == null) {
            unavailable++
            continue
        }
        compared++
        if (e.verdict == localVerdict) agreed++ else disagreements += r.testCase.id
    }

    return ExternalCheck(
        providerId = provider.id,
        providerLabel = provider.label,
        compared = compared,
        agreed = agreed,
        disagreementIds = disagreements,
        unavailable = unavailable,
    )
}
