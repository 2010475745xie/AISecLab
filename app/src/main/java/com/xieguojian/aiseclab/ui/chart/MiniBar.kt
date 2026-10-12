package com.xieguojian.aiseclab.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 一条横向条形：标签 / 数值 / 颜色。 */
data class BarItem(val label: String, val value: Int, val color: Long)

/**
 * 横向条形分布 —— 四路分区、判定层级分布共用。
 * 纯数据进、图出：调用方负责把 `ReportSections` / `JudgeLevel` 数出来（见报告页），
 * 本组件不做任何取数判断。
 */
@Composable
fun MiniBar(items: List<BarItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Text("没有可统计的条目。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxV = (items.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
    Column(modifier = modifier) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.width(104.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.value.toFloat() / maxV)
                            .fillMaxHeight()
                            .background(Color(item.color))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${item.value}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(30.dp)
                )
            }
        }
    }
}
