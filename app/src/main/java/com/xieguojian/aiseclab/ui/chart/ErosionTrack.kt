package com.xieguojian.aiseclab.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.report.ChartLegend
import com.xieguojian.aiseclab.core.report.ChartPalette
import com.xieguojian.aiseclab.core.report.ErosionAnalysis
import com.xieguojian.aiseclab.core.report.ErosionPoint
import kotlin.math.roundToInt

/**
 * 多轮侵蚀曲线（Compose 侧）：一柱 = 一个攻击步序，柱高 = 该步以内已破防的比例。
 *
 * 与 HTML 导出侧的 `ChartSvg.erosionCumulative` **同源**：数据都来自
 * [ErosionAnalysis.overallCumulative]，本组件不做任何统计，只负责画。
 *
 * 为什么不画成折线：手机上一条折线的顶点很难对齐读数，柱状还能顺便把"第几步"标在下面。
 * 曲线是单调不减的（累计破防率），所以看起来就是一段"越爬越高"的台阶 —— 爬得快 = 韧性差。
 *
 * ⚠️ 别把柱高直接读成"该模型被攻破的概率"：分母是**跑到该步的用例数**，
 * 也不是被抽样自真实分布的流量。图例（[ChartLegend.EROSION]）必须跟着显示。
 */
@Composable
fun ErosionTrack(
    analysis: ErosionAnalysis,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 92.dp,
    cellWidth: Dp = 34.dp,
    barWidth: Dp = 18.dp
) {
    val points: List<ErosionPoint> = analysis.overallCumulative
    if (points.isEmpty()) {
        Text(
            ChartLegend.EROSION_EMPTY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // 步数少时把百分比也标上，读数不用去比高度；步数多（递归上限 10 已是上限附近）就只留步序。
    val showPercent = points.size <= 8
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { p ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(cellWidth)
            ) {
                Text(
                    if (showPercent) "${(p.rate.coerceIn(0f, 1f) * 100).roundToInt()}%" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .height(plotHeight)
                        .width(barWidth)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(p.rate.coerceIn(0f, 1f))
                            .background(Color(ChartPalette.FAIL))
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${p.step}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
