package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.report.modelLabel
import com.xieguojian.aiseclab.ui.TestViewModel

/**
 * 「设置」底部标签页（对标微信「我」）：显示当前目标模型、测试后端入口。
 * 模型与护栏配置已并入「新建测试」的 CONFIG 表单，本页只保留后端入口。
 */
@Composable
fun SettingsScreen(
    vm: TestViewModel,
    onBackend: () -> Unit
) {
    val target by vm.target.collectAsState()
    val localRun by vm.localRunEnabled.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("当前目标模型", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    target?.let { modelLabel(it) } ?: "尚未配置 —— 在首页「+」新建测试中填写",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("本机运行测试", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "默认关闭。关闭时：填了后端地址就委托后端跑，没填则不允许开测" +
                            "（端上跑没有多次采样稳定性保护，单次采样定论、结果可能不稳）。" +
                            "开启后：所有测试都在本机跑，已填的后端地址本轮不生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = localRun, onCheckedChange = vm::setLocalRunEnabled)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onBackend,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("测试后端", style = MaterialTheme.typography.titleMedium) }
    }
}
