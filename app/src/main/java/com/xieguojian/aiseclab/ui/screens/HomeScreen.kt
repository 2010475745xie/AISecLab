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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.api.TokenMeter
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.ui.theme.SemanticTheme
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
    // false = 历史还在读盘。**必须**与「history 为空」分开判断：空表的初值不是"没有历史"。
    val historyLoaded by vm.historyLoaded.collectAsState()
    val target by vm.target.collectAsState()
    val tokens by TokenMeter.state.collectAsState()
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    // 待删除的历史报告：非 null 时弹确认框。
    // 删掉一轮测试是不可逆的（几十分钟 + 真金白银的 API 费用），之前垃圾桶图标一点就删。
    var pendingDelete by remember { mutableStateOf<Report?>(null) }

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
                is TestViewModel.TestState.Running ->
                    RunningCard(s.completed, s.total, tokens.summary(), onOpenRun)
                is TestViewModel.TestState.Done -> DoneCard(tokens.summary(), onViewReport)
                is TestViewModel.TestState.Error -> ErrorCard(s.message, onOpenRun)
                else -> {}
            }
        }

        item {
            Text("历史记录（已测试）", style = MaterialTheme.typography.titleMedium)
        }

        // ⚠️ 「还没读完」与「确实没有」必须分开说（与判定链那条铁律同源）：
        // [TestViewModel.history] 的初值是空表，读盘在 Dispatchers.IO 上，所以冷启动时
        // 列表为空的**头一秒**并不是"没有历史"，只是"还没读完"。合在一起说的后果实测过：
        // 2026-10-14 装机后冷启动，界面先渲染「还没有历史报告」约 0.5~1 秒才切成列表
        // （两次实测 2.17s→3.10s、2.31s→2.88s，13 份报告 × 85 KB JSON），
        // 当时我自己第一眼都以为装机把历史清空了。说一句假话比晚说一秒糟得多。
        if (!historyLoaded) {
            item {
                Text(
                    "正在读取历史报告…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (history.isEmpty()) {
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
                    onDelete = { pendingDelete = report }
                )
            }
        }
    }

    // 删除确认：明确写出"哪一份"（模型 + 时间 + 分数），避免在长列表里点错行
    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条历史报告？") },
            text = {
                val score = if (toDelete.riskLevel == RiskLevel.UNKNOWN) "--"
                else "%.1f 分".format(toDelete.overallScore)
                Text(
                    "${modelLabel(toDelete.modelProfile)}\n" +
                        "${timeFmt.format(Date(toDelete.timestamp))} · $score\n\n" +
                        "删除后无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteHistory(toDelete.timestamp)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 正在测试：进度卡，点一下进运行页看实时终端。 */
@Composable
private fun RunningCard(completed: Int, total: Int, tokensText: String?, onClick: () -> Unit) {
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
            tokensText?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 测试完成：可点进报告。 */
@Composable
private fun DoneCard(tokensText: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("✓ 测试完成", style = MaterialTheme.typography.titleMedium, color = SemanticTheme.colors.good)
            Spacer(Modifier.height(6.dp))
            Text("本轮测试已完成，点击查看详细报告", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tokensText?.let {
                Spacer(Modifier.height(2.dp))
                Text("本轮消耗 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    // 与报告页同源：判据在 ChartPalette.slotOf，配色随明暗主题。
    val riskColor = SemanticTheme.colors.risk(report.riskLevel)
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
                    "通过 $totalPassed/$totalCases · ${report.riskLevel.label}",
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

