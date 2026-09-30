package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
 * 「设置」底部标签页（对标微信「我」）：模型与护栏配置、测试后端入口，
 * 并显示当前已配置的目标模型。本身不直接跑测试，点「模型与护栏配置」进 CONFIG 表单。
 */
@Composable
fun SettingsScreen(
    vm: TestViewModel,
    onModelConfig: () -> Unit,
    onBackend: () -> Unit
) {
    val target by vm.target.collectAsState()

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
                    target?.let { modelLabel(it) } ?: "尚未配置 —— 点下方「模型与护栏配置」填写",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onModelConfig,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("模型与护栏配置", style = MaterialTheme.typography.titleMedium) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onBackend,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("测试后端", style = MaterialTheme.typography.titleMedium) }
    }
}
