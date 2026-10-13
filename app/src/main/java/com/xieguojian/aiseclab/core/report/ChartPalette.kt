package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.Verdict
import java.util.Locale

/**
 * 图表配色 —— **全套图共用这一处**（Compose 界面与 HTML 导出都从这里取），
 * 避免"界面和导出颜色对不上"这种只有肉眼才发现的问题。
 *
 * 放在 `core/report` 而不是 `ui/chart`：HTML 导出（`ChartSvg`）在 core 层，
 * 若配色留在 ui 层就变成 **core → ui 的反向依赖**。这里只有纯数据与字符串运算，
 * 不含任何 Compose 类型，放 core 是正确方向（ui → core 单向）。
 *
 * 沿用报告页已有的风险色（`ScoringModel` / `ReportScreen`），**不引入第二套色彩语言**：
 *   红 = 失守/危险，绿 = 通过，琥珀 = 拿不准，灰 = 不可评估。
 *
 * 编码规则（图例必须写明）：
 * - **色相 = 结论**（PASS 绿 / FAIL 红 / INCONCLUSIVE 琥珀 / SKIPPED 灰）；
 * - **深浅 = 置信度**（越深越确定）。`confidence` 是**用例级**的判定置信度，
 *   不是逐 token 危险度 —— 图表标题与图例都要说清楚，别让读者以为是过程量。
 */
/**
 * 语义槽位 —— 「这个结论/等级该用哪一类颜色」的**全项目唯一判据**。
 *
 * 抽它的原因：同一份 `RiskLevel → 颜色` 映射此前在 `HomeScreen`、`ReportScreen`、
 * `ReportGenerator` 里**各写了一遍**（三个 `when`，五个分支一字不差），改一个忘两个
 * 只是时间问题 —— 而它**已经发生过**：`ReportGenerator` 写的是 `#E24B4B`，
 * `ChartPalette.FAIL` 是 `#E24B4A`，差了一个字符。
 *
 * 现在各处只负责"这个槽位在我这套配色里是哪个值"：
 * · 导出件（白底）→ [ChartPalette] 的常量；
 * · App 界面 → `ui.theme.SemanticColors`（随明暗主题切换，深底要用亮档）。
 */
enum class SemanticSlot { GOOD, CAUTION, HIGH, BAD, NEUTRAL }

object ChartPalette {

    const val PASS = 0xFF1D9E75
    const val FAIL = 0xFFE24B4A
    const val INCONCLUSIVE = 0xFFBA7517
    const val SKIPPED = 0xFF8A8A8A

    /** 高风险 —— 介于「拿不准」与「严重」之间，即报告页一直在用的那支橙。 */
    const val HIGH = 0xFFD85A30

    /** 风险等级 → 语义槽位。**唯一判据**；各处别再来一份 `when (riskLevel)`。 */
    fun slotOf(level: RiskLevel): SemanticSlot = when (level) {
        RiskLevel.LOW -> SemanticSlot.GOOD
        RiskLevel.MEDIUM -> SemanticSlot.CAUTION
        RiskLevel.HIGH -> SemanticSlot.HIGH
        RiskLevel.CRITICAL -> SemanticSlot.BAD
        RiskLevel.UNKNOWN -> SemanticSlot.NEUTRAL
    }

    /**
     * 浅底用的风险色（HTML 导出 / 浅色主题）。
     * 深色主题请走 `SemanticTheme.colors.risk(level)` —— 深底上要用亮档。
     */
    fun riskColor(level: RiskLevel): Long = when (slotOf(level)) {
        SemanticSlot.GOOD -> PASS
        SemanticSlot.CAUTION -> INCONCLUSIVE
        SemanticSlot.HIGH -> HIGH
        SemanticSlot.BAD -> FAIL
        SemanticSlot.NEUTRAL -> SKIPPED
    }

    // 深浅两端（PASS / FAIL 各自一条渐变），中间值由置信度插出来
    private const val PASS_LIGHT = 0xFFA8E0CE
    private const val PASS_DARK = 0xFF0F6E56
    private const val FAIL_LIGHT = 0xFFF3B6B4
    private const val FAIL_DARK = 0xFF9E2B2A

    /** 单元色：色相看结论，深浅看置信度。 */
    fun colorFor(verdict: Verdict, confidence: Float): Long = when (verdict) {
        Verdict.PASS -> lerpArgb(PASS_LIGHT, PASS_DARK, confidence)
        Verdict.FAIL -> lerpArgb(FAIL_LIGHT, FAIL_DARK, confidence)
        Verdict.INCONCLUSIVE -> INCONCLUSIVE
        Verdict.SKIPPED -> SKIPPED
    }

    fun hexFor(verdict: Verdict, confidence: Float): String = toHex(colorFor(verdict, confidence))

    /**
     * ASR 热力色：**绿 0%（模型扛住了）→ 琥珀 50% → 红 100%（一攻就破）**。
     *
     * ⚠️ 别拿 [colorFor] 来当 ASR 色。`colorFor` 的语义是「**同一个结论**内按置信度调深浅」，
     * 把 ASR 塞进它的 confidence 位就会出现「0% ASR 被涂成浅红」—— 而 0% 是好事，
     * 浅红会被反着读成「有点危险」。两个量不是一回事，必须分开两个函数。
     *
     * 三档取的是报告里已在用的色彩语言（绿通过 / 琥珀拿不准 / 红失守），不引入第四套色。
     */
    fun asrColor(asr: Float): Long {
        val t = asr.coerceIn(0f, 1f)
        return if (t <= 0.5f) lerpArgb(PASS, INCONCLUSIVE, t * 2f)
        else lerpArgb(INCONCLUSIVE, FAIL, (t - 0.5f) * 2f)
    }

    /** 底色深到一定程度就得换浅色字，否则对比度不够、数字看不清。 */
    fun asrNeedsLightText(asr: Float): Boolean = asr.coerceIn(0f, 1f) >= 0.5f

    fun toHex(argb: Long): String =
        String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    private fun lerpArgb(a: Long, b: Long, t: Float): Long {
        val f = t.coerceIn(0f, 1f)
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * f).toInt()
        val alpha = mix(24)
        return (alpha.toLong() shl 24) or (mix(16).toLong() shl 16) or (mix(8).toLong() shl 8) or mix(0).toLong()
    }
}
