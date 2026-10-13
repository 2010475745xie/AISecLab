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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一条横向条形：标签 / 数值 / 颜色。
 *
 * [text] 是数值的**显示文本**，null 时显示 `"$value"`（分区分布、层级分布画的是**条数**）。
 * 两种非空用法：
 * - `"25%"` —— 矩阵画的是**百分比**，必须带单位。2026-10-13 真机跑完发现只渲染成
 *   `0` / `25`，读者会当成条数；而代码注释写的是「右侧数字即百分比」，说明本来就是百分比；
 * - `"--"` —— 该组合**没有可评估样本**。绝不能显示 `0`（那是「测了、没攻破」）。
 */
data class BarItem(
    val label: String,
    val value: Int,
    val color: Long,
    val text: String? = null
)

/**
 * 横向条形分布 —— 四路分区、判定层级分布、策略×意图矩阵共用。
 * 纯数据进、图出：调用方负责把 `ReportSections` / `JudgeLevel` 数出来（见报告页），
 * 本组件不做任何取数判断。
 *
 * [labelWidth] 默认 136dp：最长标签是「不可评估·判定拿不准」（10 个字），
 * 在 xxhdpi（密度 3.0）+ bodySmall 下约 360px ≈ 120dp，留出余量。
 * **别再调小** —— 调小会静默变省略号，而 `uiautomator dump` 里文本仍是完整的，
 * 只有截图能看出来（2026-10-12 真机踩过）。
 *
 * [scaleMax] 是条形长度的**分母**：
 * - `null`（默认）= 按本组最大值归一化，适合「条数」（分区分布、层级分布）；
 * - 传具体值 = 绝对刻度。**策略×意图矩阵必须传 100**：矩阵的条形代表 ASR 百分比，
 *   而颜色（[ChartPalette.asrColor]）是按**绝对 ASR** 取的。若长度按组内归一化，
 *   就会出现「25%（该族最差的一格）画成满格、还是绿色」——长度说「最大」、颜色说「很好」，
 *   两套语义叠在一根条上，2026-10-13 真机踩到。
 */
@Composable
fun MiniBar(
    items: List<BarItem>,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 136.dp,
    scaleMax: Int? = null
) {
    if (items.isEmpty()) {
        Text("没有可统计的条目。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxV = (scaleMax ?: items.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
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
                    modifier = Modifier.width(labelWidth)
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
                            .fillMaxWidth((item.value.toFloat() / maxV).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(item.color))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    // 带单位时（如 "25%"）留宽一点，别把百分号截掉
                    item.text ?: "${item.value}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(if (item.text == null) 30.dp else 44.dp)
                )
            }
        }
    }
}
