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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.ui.TestViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史报告列表：每轮跑完的测试由 TestRunService 自动存档，这里回看 / 删除。
 * 点开任意一条进入与实时报告相同的 ReportScreen（只读、无"重新测试"）。
 */
@Composable
fun HistoryScreen(vm: TestViewModel, onBack: () -> Unit, onOpen: (Report) -> Unit) {
    val history by vm.history.collectAsState()
    var pendingClear by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史记录", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = { pendingClear = true }) { Text("清空") }
            }
        }

        if (history.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "还没有历史报告。跑完一轮测试后会自动存档到这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.timestamp }) { report ->
                    HistoryItem(
                        report = report,
                        timeText = timeFmt.format(Date(report.timestamp)),
                        onClick = { onOpen(report) },
                        onDelete = { vm.deleteHistory(report.timestamp) }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("返回") }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("清空历史记录？") },
            text = { Text("将删除全部 ${history.size} 份历史报告，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    pendingClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("取消") }
            }
        )
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
                    "${report.modelProfile.name}（${report.modelProfile.model}）",
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
