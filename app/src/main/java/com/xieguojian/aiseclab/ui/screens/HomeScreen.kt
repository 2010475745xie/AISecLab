package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.report.modelLabel
import com.xieguojian.aiseclab.ui.TestViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页（底部「测试」标签页）：测试中心。
 * - 顶部展示当前一轮测试状态：正在测试（进度条，可点进实时终端）/ 完成（可看报告）/ 出错；
 * - 下方是历史记录列表（已测试过的轮次）。
 * 新建测试改由右下角悬浮「+」触发，不再堆按钮。
 */
@Composable
fun HomeScreen(
    vm: TestViewModel,
    onOpenRun: () -> Unit,
    onViewReport: () -> Unit,
    onOpenHistory: (Report) -> Unit
) {
    val state by vm.testState.collectAsState()
    val history by vm.history.collectAsState()
    val target by vm.target.collectAsState()
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text(
                    "AISecLab",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (target != null) "目标模型：${modelLabel(target!!)}"
                    else "尚未配置目标模型 —— 点右下角 + 新建测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 当前测试状态卡（已测试区分「正在测试 / 完成 / 出错」）
        item {
            when (val s = state) {
                is TestViewModel.TestState.Running -> RunningCard(s.completed, s.total, onOpenRun)
                is TestViewModel.TestState.Done -> DoneCard(onViewReport)
                is TestViewModel.TestState.Error -> ErrorCard(s.message, onOpenRun)
                else -> {}
            }
        }

        item {
            Text("历史记录（已测试）", style = MaterialTheme.typography.titleMedium)
        }

        if (history.isEmpty()) {
            item {
                Text(
                    "还没有历史报告。点右下角 + 开始一轮测试，跑完会自动存档到这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(history, key = { it.timestamp }) { report ->
                HistoryItem(
                    report = report,
                    timeText = timeFmt.format(Date(report.timestamp)),
                    onClick = { onOpenHistory(report) },
                    onDelete = { vm.deleteHistory(report.timestamp) }
                )
            }
        }
    }
}

/** 正在测试：进度卡，点一下进运行页看实时终端。 */
@Composable
private fun RunningCard(completed: Int, total: Int, onClick: () -> Unit) {
    val fraction = if (total == 0) 0f else completed.toFloat() / total
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● 正在测试", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("$completed / $total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("点此查看实时终端", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 测试完成：可点进报告。 */
@Composable
private fun DoneCard(onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("✓ 测试完成", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1D9E75))
            Spacer(Modifier.height(6.dp))
            Text("本轮测试已完成，点击查看详细报告", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 测试出错：点进运行页看详情 / 重试。 */
@Composable
private fun ErrorCard(message: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("✗ 测试出错", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("点击查看详情 / 重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryItem(
    report: Report,
    timeText: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val riskColor = when (report.riskLevel) {
        RiskLevel.LOW -> Color(0xFF1D9E75)
        RiskLevel.MEDIUM -> Color(0xFFBA7517)
        RiskLevel.HIGH -> Color(0xFFD85A30)
        RiskLevel.CRITICAL -> Color(0xFFE24B4A)
        RiskLevel.UNKNOWN -> Color(0xFF8A8A8A)
    }
    val totalPassed = report.dimensions.values.sumOf { it.passed }
    val totalCases = report.dimensions.values.sumOf { it.total }

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    modelLabel(report.modelProfile),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "通过 $totalPassed/$totalCases · ${riskText(report.riskLevel)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = riskColor
                )
            }
            Text(
                // 一条都没评上时不能显示 0.0 —— 那不是"0 分"，是"没有数据"（与 ReportScreen 口径一致）
                if (report.riskLevel == RiskLevel.UNKNOWN) "--"
                else "%.1f".format(report.overallScore),
                style = MaterialTheme.typography.headlineSmall,
                color = riskColor
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除这条记录")
            }
        }
    }
}

private fun riskText(level: RiskLevel): String = when (level) {
    RiskLevel.LOW -> "低风险"
    RiskLevel.MEDIUM -> "中风险"
    RiskLevel.HIGH -> "高风险"
    RiskLevel.CRITICAL -> "严重风险"
    RiskLevel.UNKNOWN -> "无法评级"
}
