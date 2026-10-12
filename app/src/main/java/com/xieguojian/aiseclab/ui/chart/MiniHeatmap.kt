package com.xieguojian.aiseclab.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.report.ChartPalette

/**
 * 用例热力图：行 = 维度，列 = 用例，颜色取自 [ChartPalette]（色相=结论，深浅=置信度）。
 *
 * 纯数据进、图出 —— **不依赖 Report / DimensionScore 等业务类型**，只吃 `List<TestResult>`，
 * 这样报告页、将来别的页面都能复用。
 *
 * 小屏约束：50 条用例 × 4 维度放不进手机竖屏，所以**网格横向滚动**，维度名固定在左侧
 * （跟着滚就对不上行了）。**不压缩格子** —— 压到 4~6dp 就只剩噪点。
 */
@Composable
fun MiniHeatmap(
    results: List<TestResult>,
    modifier: Modifier = Modifier,
    cell: Dp = 12.dp,
    gap: Dp = 2.dp
) {
    val byDim = results.groupBy { it.testCase.dimension }
    val dims = Dimension.values().filter { byDim.containsKey(it) }
    if (dims.isEmpty()) {
        Text("没有可绘制的用例。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxCells = dims.maxOf { byDim[it]?.size ?: 0 }
    val rowH = cell + 4.dp
    val gridW = cell * maxCells + gap * (maxCells - 1).coerceAtLeast(0)
    val scroll = rememberScrollState()

    Row(modifier = modifier) {
        Column {
            dims.forEach { dim ->
                Box(
                    Modifier.height(rowH).width(56.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(dim.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        Column(Modifier.horizontalScroll(scroll)) {
            dims.forEach { dim ->
                val list = byDim[dim].orEmpty()
                Canvas(Modifier.width(gridW).height(rowH)) {
                    val c = cell.toPx()
                    val g = gap.toPx()
                    val top = (size.height - c) / 2f
                    list.forEachIndexed { i, r ->
                        drawRoundRect(
                            color = Color(
                                ChartPalette.colorFor(
                                    r.judgeResult.verdict,
                                    r.judgeResult.confidence
                                )
                            ),
                            topLeft = Offset(i * (c + g), top),
                            size = Size(c, c),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }
                }
            }
        }
    }
}
