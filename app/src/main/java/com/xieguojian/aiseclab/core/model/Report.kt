package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AISecLab 测评报告：总分 + 风险等级 + 四维独立分 + 明细 + 摘要。
 */
@Serializable
data class Report(
    val modelProfile: ModelProfile,
    val timestamp: Long,
    val overallScore: Float,                        // 0..100
    val riskLevel: RiskLevel,
    val dimensions: Map<Dimension, DimensionScore>,
    val results: List<TestResult>,
    val summary: String,
    /**
     * 本轮实际烧掉的 token（按角色分账）。**跑完就落进报告**，所以历史报告翻出来
     * 也能看到当时花了多少 —— 之前只在跑的时候界面上显示，一离开那一刻就再也查不到了。
     *
     * `null` = 这份报告没有用量记录（旧版本生成的报告，或本轮一次模型调用都没发起）。
     * 展示时必须说「未记录」，**不能显示成 0** —— 「没记录」和「一次没烧」是两回事。
     */
    val tokenUsage: TokenUsage? = null,
    /**
     * 外部安全 API 的**对照结果**（B4）。`null` = 本轮未接入外部裁判。
     *
     * 展示时必须说「未接入」—— **不能显示成一致率 0%**：「没接入」与「完全不同意」
     * 是两回事（同「没采集 ≠ 很差」）。文案唯一源 = `core/report/ExternalNote.kt`。
     *
     * 字段名与后端 `app/models.py::Report` 不要求一致 —— 这一项**没有后端对应物**：
     * 外部裁判是端上的对照件（在本地判定链之外旁挂一路），后端那条链不参与，
     * 所以这里的 null 也可能是「后端产的报告」，同样按「未接入」展示。
     */
    val externalCheck: ExternalCheck? = null,
    /**
     * 断点续测自证：本轮有多少条用例的结果是**从检查点复用**的（未重跑）。0 = 全新一轮。
     *
     * 为什么必须写进报告：不写的话，「这份报告里 50 条中有 30 条其实是上一轮的结论」这件事
     * 在产物里查无实据 —— 而两次运行的被测模型/裁判配置理论上可能不同（BYOK，key 由端上带）。
     * 与 [tokenSpendLine] / `cotNote` / `reproLine` 同一纪律：会误导读者的口径，产物里必须自己说清。
     *
     * 字段名与后端 `backend/app/models.py::Report.resumedCount` 逐字一致；本端忽略未知键，
     * 名字对不上就是**静默丢弃**（这一行永远不显示，且不报错）。
     *
     * 本机跑（不委托后端）永远是 0 —— 端上不做落盘，没有断点可续。展示文案见 [resumeNote]。
     */
    val resumedCount: Int = 0,
    /**
     * 本轮测试的**墙钟用时**（毫秒）。`null` = 没有记录（旧版本生成的报告）。
     *
     * 口径 = **从服务开始准备（用户点「开始测试」）到报告生成**。用的是**单调时钟**
     * （`SystemClock.elapsedRealtime()`）而不是壁钟：后者会被系统对时 / 用户改时间跳变，
     * 算出一个负的、或者几小时的"用时"，比不显示更糟。
     *
     * 为什么落进报告、而不是只在跑的时候显示：与 [TokenUsage] 同一条理由 ——
     * 历史报告翻出来也要能看到"这轮花了多久"，而跑的时候那行实时数字一离开当轮就查不到了。
     *
     * ⚠️ **这不是各用例 `latencyMs` 的和**：用例是并发的，加起来会远大于墙钟。
     * 委托后端跑时它还含网络往返 + 服务端整轮的时间（即"用户实际等了多久"）。
     *
     * 这一项**没有后端对应物**（与 [TokenUsage] 同）：用时只有端上量得准 ——
     * 后端报的是它自己那一段，端上量的是含网络与准备的全过程。两端都忽略未知键，安全。
     */
    val elapsedMs: Long? = null
)

/**
 * 一轮测试的 token 用量账单。与 [com.xieguojian.aiseclab.core.api.TokenMeter.Snapshot]
 * 同源（后者是跑的时候的实时计数），这里只保留落盘需要的部分。
 *
 * 两个刻意的取舍（与 TokenMeter 一致）：
 * 1. 只统计**带回 usage 的成功响应** —— 宁可不计也不估算，估出来的数会被当账单看。
 * 2. 端点不回 usage（本地 llama.cpp 默认不回）时计入 [unmeteredCalls]，
 *    让读者知道「这个数偏低，有 N 次调用没算进来」，而不是假装完整。
 */
@Serializable
data class TokenUsage(
    val total: Long = 0,
    val prompt: Long = 0,
    val completion: Long = 0,
    /** 成功且**带回 usage** 的调用数。 */
    val meteredCalls: Int = 0,
    /** 成功但**没带 usage** 的调用数。 */
    val unmeteredCalls: Int = 0,
    /** 角色（中文标签，如「被测 / 裁判 / 攻击 / 护栏」）→ token 数。 */
    val byRole: Map<String, Long> = emptyMap(),
) {
    /** 过万折算成 `1.2万`，避免长数字撑破一行（与跑的时候界面上同一把尺）。 */
    private fun fmt(v: Long): String =
        if (v >= 10_000) "%.1f万".format(v / 10_000.0) else "%,d".format(v)

    /** 一行摘要，如 `1.2万 token（被测 8,000 · 裁判 4,340）· 3 次未返回用量`。 */
    fun summary(): String {
        val parts = byRole.entries.filter { it.value > 0 }
            .joinToString(" · ") { "${it.key} ${fmt(it.value)}" }
        val base = "${fmt(total)} token" + if (parts.isNotEmpty()) "（$parts）" else ""
        return if (unmeteredCalls > 0) "$base · $unmeteredCalls 次未返回用量" else base
    }
}

/**
 * 「本次消耗」这一行的文案。**报告页 / Markdown 导出 / HTML 导出三处共用**，
 * 不许各自再拼一遍（那样「界面说 1.2万、导出写 12000」只是时间问题）。
 *
 * 没有用量记录时说「未记录」，**不显示 0**：「没记录」和「一次没烧」是两回事。
 */
fun Report.tokenSpendLine(): String =
    tokenUsage?.summary()
        ?: "未记录 —— 这份报告生成时没有留下用量（旧版本生成的报告，或本轮没有发起过模型调用）。"

/**
 * 「断点续测」这一行的文案。**报告页 / Markdown 导出 / HTML 导出三处共用**（做法同 [tokenSpendLine]）。
 *
 * 没有任何复用时时返回**空串、而不是「复用 0 条」**：没续跑就不该多出一句话 ——
 * 这与「没采集 ≠ 很差」同源：0 条复用是"本轮从头跑"，不是一条需要读者注意的异常。
 *
 * ⚠️ 措辞上把「本轮未再消耗」写出来，而不是笼统说「不计费」：那些用例**在上一次运行时是真的
 * 烧过 token 的**，本轮的账单里不含它们，仅此而已。写成"不计费"会让读者以为白拿了一份结果。
 */
fun Report.resumeNote(): String =
    if (resumedCount <= 0) ""
    else "断点续测：本轮 ${results.size} 条用例中有 $resumedCount 条沿用上次中断前的判定结果" +
        "（未重跑，本轮消耗不含这批）。"

/**
 * 把毫秒时长格式化成中文可读文本。**测试页与报告页共用同一把尺**。
 *
 * 为什么必须共用：测试页的实时计时和报告里的最终用时是同一个数 —— 各写一个格式化函数，
 * 迟早出现"跑的时候显示 `00:42`、报告里写 `42 秒`"，用户不知道该信哪个（客户端两侧
 * 各拼一遍文案在本项目已经出过两次，见 [Report.tokenSpendLine] 的注释）。
 *
 * 只精确到**整秒**：实时计时每 0.5 秒刷新一次，整秒是能看清的最小刻度；再细下去
 * （毫秒）数字会跳到看不清，而对"这轮跑了多久"这个问题毫无帮助。
 *
 * 超过 1 小时才显示小时（`1 小时 02 分 03 秒`），否则 `1 分 05 秒` / `12 秒` ——
 * 小时位对绝大多数轮次都是噪声。负数按 0 处理：那只会来自时钟异常，
 * 显示一个负的"用时"比显示 `0 秒` 更容易让人误以为程序坏了。
 */
fun formatElapsed(ms: Long): String {
    val total = (if (ms < 0L) 0L else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "%d 小时 %02d 分 %02d 秒".format(h, m, s)
        m > 0 -> "%d 分 %02d 秒".format(m, s)
        else -> "%d 秒".format(s)
    }
}

/**
 * 「本次耗时」这一行的文案。**报告页 / Markdown 导出 / HTML 导出三处共用**（做法同 [tokenSpendLine]）。
 *
 * 没有记录时说「未记录」，**不显示 0 秒**：「没记时间」和「一瞬间就跑完了」是两回事
 * —— 与「没采集 ≠ 很差」同源，也与 [tokenSpendLine] 的口径一致。
 */
fun Report.elapsedLine(): String =
    elapsedMs?.let { "本次测试用时 ${formatElapsed(it)}。" }
        ?: "未记录 —— 这份报告没有留下用时（旧版本生成的报告，或这一轮没有记录起止时刻）。"

@Serializable
data class DimensionScore(
    val dimension: Dimension,
    val score: Float,               // 0..100
    val passed: Int,
    val total: Int,
    val failRate: Float,            // 0..1
    val notableFindings: List<String>
)

/**
 * 风险等级。
 *
 * [label] 是**全项目唯一**的中文说法：此前 `HomeScreen` / `ReportScreen` /
 * `ReportGenerator` 各写了一份 `riskText()`（三个 `when` 一字不差）—— 改文案时漏掉一个，
 * 用户就会在同一屏上看到两种说法。与 [Dimension] / `JailbreakStrategy` 一样，
 * 把展示名挂在枚举上，而不是让每个渲染处各持一份。
 */
@Serializable
enum class RiskLevel(val label: String) {
    @SerialName("low") LOW("低风险"),
    @SerialName("medium") MEDIUM("中风险"),
    @SerialName("high") HIGH("高风险"),
    @SerialName("critical") CRITICAL("严重风险"),

    /**
     * 无法评级：一条样本都没评估成功（全部接口调用失败 / 限流 / 超时）。
     * 必须与 CRITICAL 区分开 —— "没测到"和"测出来很糟"是两回事，
     * 把前者显示成"严重风险"正是这份报告之前最大的误导。
     */
    @SerialName("unknown") UNKNOWN("无法评级")
}
