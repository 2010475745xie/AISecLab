package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.ui.TestViewModel

/**
 * 专门的「测试后端」设置页。
 *
 * 把原来挤在模型配置表单底部的「测试后端（可选）」一段独立成页：
 * 后端地址是全局设置（与某次具体测试无关），单独成页后既能从首页直达，
 * 也能在填模型时顺手设置，不再和 BYOK 表单抢空间。
 *
 * 采用即时保存：每次输入即写入 EncryptedSharedPreferences（与服务读取同源），
 * 不需要额外「保存」按钮，返回即生效。
 */
@Composable
fun BackendConfigScreen(
    vm: TestViewModel,
    onBack: () -> Unit
) {
    val backendUrl by vm.backendUrl.collectAsState()
    val backendToken by vm.backendToken.collectAsState()

    var urlField by remember { mutableStateOf(backendUrl) }
    var tokenField by remember { mutableStateOf(backendToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("测试后端（可选）", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "留空 = 在本机跑（默认）；填了就把整轮测试委托给该后端服务器——适合没有算力、" +
                "或想共享同一套裁判模型的场景。被测模型 Key 仅随请求发往后端，仅该任务周期内在服务器内存留存。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            urlField, { v ->
                urlField = v
                vm.saveBackendUrl(v)   // 即时保存
            },
            label = { Text("后端地址（局域网 http://192.168.x.x:8000；公网 https://...）") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "scheme 要与后端实际协议一致：局域网自测填 http://，公网 HTTPS 服务器填 https://；" +
                "填反了会报 ssl: wrong version number。结尾不要带 /v1。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            tokenField, { v ->
                tokenField = v
                vm.saveBackendToken(v)  // 即时保存
            },
            label = { Text("访问令牌（仅当后端设了 API_TOKEN 才填）") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        if (urlField.isNotBlank() || tokenField.isNotBlank()) {
            TextButton(onClick = {
                urlField = ""
                tokenField = ""
                vm.saveBackendUrl("")
                vm.saveBackendToken("")
            }) { Text("清空（改回本机跑）") }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("返回", style = MaterialTheme.typography.titleMedium) }
    }
}
