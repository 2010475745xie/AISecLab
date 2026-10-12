package com.xieguojian.aiseclab.core.report

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
object ChartPalette {

    const val PASS = 0xFF1D9E75
    const val FAIL = 0xFFE24B4A
    const val INCONCLUSIVE = 0xFFBA7517
    const val SKIPPED = 0xFF8A8A8A

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
