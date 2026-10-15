package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.Serializable

/**
 * 外部安全 API 的**对照结果**（B4）—— 第二方裁判与本地判定在若干条上的一致情况。
 *
 * ## 它与"判定"的关系：**只对照，不回写**
 *
 * 外部裁判**绝不**参与 verdict —— 与 `StrategyFingerprintLibrary` 是同一条纪律：
 * 一个独立来源的输出，用途是**发现分歧**，不是替本地改判。理由是可证伪性：
 * 外部 API 的口径、版本、可见性都不在我们手里，让它改判等于把结论建立在一个
 * 我们无法复算的黑盒上。所以这里只记「一致多少条 / 哪些条分歧」，
 * 报告里的结论仍然只由本地判定链产生。
 *
 * ## 为什么要显式带 [unavailable]
 *
 * 「外部裁判没给出结果」与「外部裁判不同意」是两回事：前者说明**这次对照没做成**
 * （调用失败 / 端点不判这类输入 / 配额用尽），后者才是真分歧。
 * 混成一个数字会把"没测到"讲成"有分歧"，是本项目反复强调的那个坑
 * （「没采集」≠「很差」）。
 */
@Serializable
data class ExternalCheck(
    /** 供应商标识（配置里给的短名）。 */
    val providerId: String,
    /** 供应商展示名（报告里写给人看的）。 */
    val providerLabel: String,
    /** 实际比过的条数（外部给出了明确判定的那些）。 */
    val compared: Int,
    /** 与本地判定一致的条数。 */
    val agreed: Int,
    /** 分歧的用例 id（本地判定与外部判定不同）—— 报告里要能点回去看。 */
    val disagreementIds: List<String> = emptyList(),
    /** 外部**没给出结果**的条数（调用失败 / 超时 / 不判）。与 [disagreementIds] 不是一回事。 */
    val unavailable: Int = 0,
) {
    /** 一致率；[compared] 为 0 时返回 null（**不是 0** —— 0 会被读成"完全不同意"）。 */
    val rate: Float? get() = if (compared == 0) null else agreed.toFloat() / compared
}
