package com.xieguojian.aiseclab.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.model.AttackRound
import com.xieguojian.aiseclab.core.report.ChartLegend
import com.xieguojian.aiseclab.core.report.ChartPalette

/**
 * 攻击演进时间轴：一格 = 一次攻击步进，绿=未破防、红=破防。
 *
 * ⚠️ 这**不是**分支树。PAIR 是单链递归（每轮在上轮基础上改写一个 prompt），
 * 画成树等于用可视化撒谎 —— 想要树得先改攻击器支持并行分支。
 *
 * 横向滚动：递归对抗上限 10 轮（`TestRunner.DEFAULT_MAX_ROUNDS`），静态变异最多 6 个变体，
 * 窄屏放不下就滚，不压缩格子。
 */
@Composable
fun TimelineTrack(
    rounds: List<AttackRound>,
    modifier: Modifier = Modifier,
    box: androidx.compose.ui.unit.Dp = 22.dp
) {
    if (rounds.isEmpty()) {
        Text(
            ChartLegend.TIMELINE_EMPTY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        rounds.forEach { rd ->
            val broke = !rd.refused
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(box)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(if (broke) ChartPalette.FAIL else ChartPalette.PASS))
                ) {
                    // 破防格加一圈深色描边：色弱用户只靠色相分不出来
                    if (broke) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .border(2.dp, Color(0xFF7A1F1E), RoundedCornerShape(4.dp))
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${rd.round}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
