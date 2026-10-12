package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.AttackRound
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.TestResult

/**
 * 图表的 **SVG 版本** —— 给自包含 HTML 导出用。
 *
 * 为什么不用 JS 图表库：导出的报告要能在**没网、没脚本**的环境里打开（审计存档场景）。
 * 内联 SVG 是纯静态标记，微信/邮件预览、离线浏览器都能渲染。
 *
 * ⚠️ **与 Compose 侧必须保持同一套口径**：颜色一律取自 [ChartPalette]，
 * 分区/层级/轮次的取数逻辑与 `ReportSections`、`MiniBar` 的调用点一致。
 * 两边各写一套 filter 正是历史上"同一个判据抄三遍"的老毛病。
 */
object ChartSvg {

    private const val FONT = "font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif"

    /** 用例热力图：行 = 维度，列 = 用例，色相=结论、深浅=置信度。 */
    fun heatmap(results: List<TestResult>): String {
        val byDim = results.groupBy { it.testCase.dimension }
        val dims = Dimension.values().filter { byDim.containsKey(it) }
        if (dims.isEmpty()) return ""
        val maxCells = dims.maxOf { byDim[it]!!.size }
        val cell = 12
        val gap = 2
        val labelW = 58
        val rowH = cell + 4
        val w = labelW + maxCells * (cell + gap)
        val h = dims.size * rowH + 4

        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" role=\"img\">")
        sb.append("<title>用例热力图</title><desc>按维度排列的用例结论矩阵，色相表示结论、深浅表示置信度</desc>")
        dims.forEachIndexed { ri, dim ->
            val y = 2 + ri * rowH
            sb.append("<text x=\"0\" y=\"${y + cell - 1}\" font-size=\"11\" fill=\"#666\" style=\"$FONT\">")
                .append(escape(dim.label)).append("</text>")
            byDim[dim]!!.forEachIndexed { ci, r ->
                val x = labelW + ci * (cell + gap)
                sb.append("<rect x=\"$x\" y=\"$y\" width=\"$cell\" height=\"$cell\" rx=\"2\" fill=\"")
                    .append(ChartPalette.hexFor(r.judgeResult.verdict, r.judgeResult.confidence))
                    .append("\"/>")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** 攻击演进时间轴：一格 = 一次攻击步进；红 = 破防（该步之后停止），绿 = 未破防。 */
    fun timeline(rounds: List<AttackRound>): String {
        if (rounds.isEmpty()) return ""
        val box = 20
        val gap = 6
        val w = rounds.size * (box + gap)
        val h = box + 16
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" role=\"img\">")
        sb.append("<title>攻击演进时间轴</title><desc>每次攻击步进的破防/未破防状态</desc>")
        rounds.forEachIndexed { i, rd ->
            val x = i * (box + gap)
            val fill = ChartPalette.toHex(if (rd.refused) ChartPalette.PASS else ChartPalette.FAIL)
            sb.append("<rect x=\"$x\" y=\"0\" width=\"$box\" height=\"$box\" rx=\"4\" fill=\"$fill\"/>")
            if (!rd.refused) {
                sb.append("<rect x=\"$x\" y=\"0\" width=\"$box\" height=\"$box\" rx=\"4\" fill=\"none\" stroke=\"#7A1F1E\" stroke-width=\"2\"/>")
            }
            sb.append("<text x=\"${x + box / 2}\" y=\"${box + 12}\" font-size=\"10\" fill=\"#666\" text-anchor=\"middle\" style=\"$FONT\">")
                .append(rd.round).append("</text>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * 横向条形分布（四路分区 / 判定层级共用）。
     * [items] = (标签, 数值, 颜色)。
     */
    fun bars(items: List<Triple<String, Int, Long>>): String {
        if (items.isEmpty()) return ""
        val labelW = 96
        val trackW = 170
        val valueW = 42
        val rowH = 20
        val w = labelW + trackW + valueW
        val h = items.size * rowH + 4
        val maxV = items.maxOf { it.second }.coerceAtLeast(1)

        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" role=\"img\">")
        sb.append("<title>分布图</title><desc>按类别的条目数分布</desc>")
        items.forEachIndexed { i, (label, value, color) ->
            val y = 4 + i * rowH
            val barW = (trackW.toFloat() * value / maxV).toInt()
            sb.append("<text x=\"0\" y=\"${y + 13}\" font-size=\"11\" fill=\"#333\" style=\"$FONT\">")
                .append(escape(label)).append("</text>")
            sb.append("<rect x=\"$labelW\" y=\"${y + 3}\" width=\"$trackW\" height=\"12\" rx=\"3\" fill=\"#EFEFEF\"/>")
            if (barW > 0) {
                sb.append("<rect x=\"$labelW\" y=\"${y + 3}\" width=\"$barW\" height=\"12\" rx=\"3\" fill=\"")
                    .append(ChartPalette.toHex(color)).append("\"/>")
            }
            sb.append("<text x=\"${labelW + trackW + 6}\" y=\"${y + 13}\" font-size=\"11\" fill=\"#555\" style=\"$FONT\">")
                .append(value).append("</text>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
