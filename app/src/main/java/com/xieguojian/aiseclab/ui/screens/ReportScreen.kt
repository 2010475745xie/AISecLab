package com.xieguojian.aiseclab.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.modelLabel
import com.xieguojian.aiseclab.ui.TestViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报告页。[reportOverride] 为 null 时展示本轮测试的当前报告（可"重新测试"）；
 * 传入历史报告则以只读方式展示（无"重新测试"，导出走对应历史报告）。
 */
@Composable
fun ReportScreen(
    vm: TestViewModel,
    reportOverride: Report? = null,
    onBack: () -> Unit,
    onRestart: (() -> Unit)? = null
) {
    val state by vm.testState.collectAsState()
    val report = reportOverride ?: (state as? TestViewModel.TestState.Done)?.report
    val ctx = LocalContext.current

    // 最近一次导出的文件，用于展示"已保存"提示 + 分享按钮
    var exportedFile by remember { mutableStateOf<File?>(null) }

    if (report == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("暂无报告", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("返回测试") }
        }
        return
    }

    val riskColor = when (report.riskLevel) {
        RiskLevel.LOW -> Color(0xFF1D9E75)
        RiskLevel.MEDIUM -> Color(0xFFBA7517)
        RiskLevel.HIGH -> Color(0xFFD85A30)
        RiskLevel.CRITICAL -> Color(0xFFE24B4A)
        RiskLevel.UNKNOWN -> Color(0xFF8A8A8A)
    }

    // 每个维度"调用失败、没评上"的条数：展示时要跟"真的失守"分开说。
    val skippedByDim = report.results
        .filter { it.judgeResult.verdict == Verdict.SKIPPED }
        .groupingBy { it.testCase.dimension }
        .eachCount()
    val skippedResults = report.results.filter { it.judgeResult.verdict == Verdict.SKIPPED }
    val noEvidence = report.riskLevel == RiskLevel.UNKNOWN

    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item {
            Text("AI 体检报告", style = MaterialTheme.typography.headlineMedium)
            Text(modelLabel(report.modelProfile), style = MaterialTheme.typography.bodyMedium)
            if (reportOverride != null) {
                val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                Text(
                    "历史报告 · ${timeFmt.format(Date(report.timestamp))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(
                    // 一条都没评上时不能显示 0.0 —— 那不是"0 分"，是"没有数据"。
                    if (noEvidence) "--" else "%.1f".format(report.overallScore),
                    style = MaterialTheme.typography.displayLarge,
                    color = riskColor
                )
                Text(" / 100", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(riskText(report.riskLevel), color = riskColor, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportedFile = vm.exportHtml(reportOverride) }) { Text("导出 HTML") }
                OutlinedButton(onClick = { exportedFile = vm.exportMarkdown(reportOverride) }) { Text("导出 Markdown") }
            }
            // 导出反馈：之前写到 cacheDir 且忽略返回值，用户够不着也没提示。
            // 现在写外部 Download 目录并拿到 File，显示路径 + 一键分享/打开。
            exportedFile?.let { file ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("已导出：${file.name}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            file.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                val uri = FileProvider.getUriForFile(
                                    ctx, ctx.packageName + ".fileprovider", file
                                )
                                val mime = if (file.extension == "html") "text/html" else "text/markdown"
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(share, "分享报告"))
                            }) { Text("分享 / 打开") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { exportedFile = null }) { Text("知道了") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("分维度得分", style = MaterialTheme.typography.titleMedium)
        }

        items(report.dimensions.values.sortedBy { it.dimension.ordinal }.toList()) { d ->
            val skippedHere = skippedByDim[d.dimension] ?: 0
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(d.dimension.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (d.total == 0) "--" else "%.1f".format(d.score),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Text(
                        if (d.total == 0) {
                            "未评估（$skippedHere 条用例全部调用失败，不计入总分）"
                        } else {
                            val skipNote = if (skippedHere > 0) " · 不可评估 $skippedHere 条" else ""
                            "通过 ${d.passed}/${d.total} · 失守率 %.0f%%".format(d.failRate * 100) + skipNote
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Text("摘要", style = MaterialTheme.typography.titleMedium)
            Text(report.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("失守明细", style = MaterialTheme.typography.titleMedium)
        }

        val fails = report.results.filter { it.judgeResult.verdict == Verdict.FAIL }
        if (fails.isEmpty()) {
            item {
                Text(
                    if (noEvidence) "本轮无有效样本，未产生失守结论。" else "未发现失守用例。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items(fails) { r -> FindingItem(r) }
        }

        // 调用失败的用例单独列出来：既不冒充失守，也不静默丢掉。
        if (skippedResults.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("不可评估（接口调用失败 / 限流）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "以下 ${skippedResults.size} 条未能拿到有效回答，已从失守率与总分中剔除。它们不代表模型有风险，也不代表模型安全。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(skippedResults) { r -> SkippedItem(r) }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(onClick = onBack) { Text(if (reportOverride != null) "返回历史" else "返回") }
                Spacer(Modifier.weight(1f))
                if (onRestart != null) {
                    TextButton(onClick = onRestart) { Text("重新测试") }
                }
            }
        }
    }
}

@Composable
private fun FindingItem(r: TestResult) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("${r.testCase.dimension.label} · ${r.testCase.title}", style = MaterialTheme.typography.titleSmall)
            Text("攻击输入：${r.testCase.prompt}", style = MaterialTheme.typography.bodySmall)
            Text("模型回答：${r.response.take(200)}", style = MaterialTheme.typography.bodySmall)
            Text("判定依据：${r.judgeResult.evidence.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
            if (r.judgeResult.needsReview) {
                Text("需人工复核", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (r.judgeResult.disagreements.isNotEmpty()) {
                Text("分歧记录：${r.judgeResult.disagreements.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SkippedItem(r: TestResult) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("${r.testCase.dimension.label} · ${r.testCase.title}", style = MaterialTheme.typography.titleSmall)
            Text(
                r.judgeResult.evidence.firstOrNull() ?: "接口调用失败，本条不可评估",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
