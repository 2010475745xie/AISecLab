package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguojian.aiseclab.core.api.TokenMeter
import com.xieguojian.aiseclab.ui.TestViewModel

/**
 * 测试运行页：顶部进度条 + 下方「类终端」窗口实时显示测试进程信息（[TestViewModel.testLog]）。
 *
 * 终端窗口固定深色背景（无论 App 是明/暗主题），等宽字体 + 按前缀着色，
 * 自动滚到最新一行。测试进行中允许系统返回（回首页看进度，后台服务继续跑）；
 * 页内「停止」按钮可主动结束测试。
 */
@Composable
fun RunScreen(
    vm: TestViewModel,
    onStop: () -> Unit
) {
    val state by vm.testState.collectAsState()
    val log by vm.testLog.collectAsState()
    val tokens by TokenMeter.state.collectAsState()
    val listState = rememberLazyListState()
    // 「停止」= 丢弃本轮已跑完的所有结果（TestRunStateHolder.reset() 不保留任何一条）。
    // 花了真金白银跑出来的东西不该一点就没，所以先让用户确认。
    var confirmStop by remember { mutableStateOf(false) }

    // 新日志进来时自动滚到最底（用 snapshot，避免 items 还没重组完滚不到）
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    val title = when (state) {
        is TestViewModel.TestState.Running -> "测试进行中"
        is TestViewModel.TestState.Done -> "测试完成"
        is TestViewModel.TestState.Error -> "测试出错"
        else -> "准备中"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部：标题 + 进度条 + 计数 ──
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is TestViewModel.TestState.Running -> {
                    val fraction = if (s.total == 0) 0f else s.completed.toFloat() / s.total
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "进度：${s.completed} / ${s.total}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is TestViewModel.TestState.Done -> {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("全部用例完成，正在生成报告…", style = MaterialTheme.typography.bodyMedium)
                }
                is TestViewModel.TestState.Error -> {
                    Text(
                        "出错了：${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
            }

            // ── token 消耗：本轮累计，随每次模型调用实时增长 ──
            // 放在状态分支之外，Running / Done / Error 都能看到（出错时用户更想知道烧了多少）。
            tokens.summary()?.let { line ->
                Spacer(Modifier.height(10.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        // ── 终端窗口 ──
        val terminalBg = Color(0xFF0D1117) // GitHub 深空背景，固定深色
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(terminalBg)
        ) {
            if (log.isEmpty()) {
                Text(
                    "等待测试进程输出…",
                    color = Color(0xFF8B949E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    items(log) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = terminalTextColor(line),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        // ── 底部操作条 ──
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isError = state is TestViewModel.TestState.Error
            if (isError) {
                Button(
                    onClick = { vm.startTest() },
                    modifier = Modifier.weight(1f)
                ) { Text("重试") }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f)
                ) { Text("返回") }
            } else {
                OutlinedButton(
                    onClick = { confirmStop = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state is TestViewModel.TestState.Running
                ) { Text("停止") }
            }
        }
    }

    // 停止确认：说清楚"已完成多少条会被丢弃"和"已经烧了多少 token"，
    // 这两个数字才是用户真正在权衡的东西。
    if (confirmStop) {
        val running = state as? TestViewModel.TestState.Running
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止本轮测试？") },
            text = {
                val done = running?.let { "已完成 ${it.completed} / ${it.total} 条，这些结果会一并丢弃。" }
                    ?: "本轮结果会被丢弃。"
                val spent = tokens.summary()?.let { "\n\n已消耗 $it。" }.orEmpty()
                Text(done + spent + "\n\n不会生成报告，也无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) { Text("停止并丢弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("继续测试") }
            }
        )
    }
}

/**
 * 终端行着色：按首字符/前缀给语义色，让「进程信息」一眼能分清阶段与好坏。
 * 固定色值（深色背景上的高对比度），不随 App 主题变化。
 */
private fun terminalTextColor(line: String): Color = when {
    line.startsWith("✗") -> Color(0xFFFF7B72)   // 错误：红
    line.startsWith("✓") -> Color(0xFF3FB950)   // 成功：绿
    line.startsWith("●") -> Color(0xFF58A6FF)   // 进度：蓝
    line.startsWith("▶") || line.startsWith("🖥") -> Color(0xFFD2A8FF) // 阶段/信息：紫
    else -> Color(0xFFC9D1D9)                    // 普通：浅灰
}
