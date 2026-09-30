package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.ui.TestViewModel

/**
 * 新启动落地页（替代原来「一进来就填表单」的体验）。
 *
 * 不再一进去就逼用户填密钥：先给一个清爽的首页，点「新建测试」才进入配置表单（[ModelConfigScreen]）。
 * 也提供「历史记录」入口。
 */
@Composable
fun HomeScreen(
    vm: TestViewModel,
    onNewTest: () -> Unit,
    onHistory: () -> Unit,
    onBackend: () -> Unit
) {
    val target by vm.target.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "AISecLab",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "AI 安全红队体检 · 自带密钥，端上 / 后端皆可跑",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onNewTest,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("新建测试", style = MaterialTheme.typography.titleMedium) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onHistory,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("历史记录", style = MaterialTheme.typography.titleMedium) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onBackend,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("后端设置", style = MaterialTheme.typography.titleMedium) }

        Spacer(Modifier.height(28.dp))
        Text(
            if (target != null) "已配置目标模型：${target?.name ?: ""}"
            else "尚未配置模型 —— 点击「新建测试」会引导你填写",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
