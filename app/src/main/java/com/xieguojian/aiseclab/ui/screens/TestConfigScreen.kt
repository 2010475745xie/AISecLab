package com.xieguojian.aiseclab.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.GuardMode
import com.xieguojian.aiseclab.core.model.RoleSource
import com.xieguojian.aiseclab.ui.TestViewModel

@Composable
fun TestConfigScreen(vm: TestViewModel, onBack: () -> Unit, onStart: () -> Unit) {
    val selected by vm.selectedDimensions.collectAsState()
    val useRecursive by vm.useRecursive.collectAsState()
    val roleSource by vm.roleSource.collectAsState()
    val aux by vm.aux.collectAsState()
    val guard by vm.guard.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()
    val state by vm.testState.collectAsState()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("测试配置", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(16.dp))
        Text("选择评估维度", style = MaterialTheme.typography.titleMedium)
        Dimension.values().forEach { dim ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = dim in selected,
                    onCheckedChange = { vm.toggleDimension(dim, it) }
                )
                Column {
                    Text(dim.label, style = MaterialTheme.typography.bodyLarge)
                    Text(dim.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        // 裁判 / 攻击者来源：AUTO=有辅助用辅助否则用目标自身；AUX=只用辅助；TARGET=目标自评/自攻。
        Text("裁判 / 攻击者来源", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleSource.values().forEach { src ->
                FilterChip(
                    selected = roleSource == src,
                    onClick = { vm.setRoleSource(src) },
                    label = {
                        Text(
                            when (src) {
                                RoleSource.AUTO -> "自动"
                                RoleSource.AUX -> "辅助模型"
                                RoleSource.TARGET -> "目标自身"
                            }
                        )
                    }
                )
            }
        }
        Text(
            when (roleSource) {
                RoleSource.AUTO -> "有辅助模型用辅助，否则用目标模型自身（单模型默认）"
                RoleSource.AUX -> "只用辅助模型；未配置则退化为纯规则判定"
                RoleSource.TARGET -> "目标模型自评/自攻，覆盖有限，仅作辅助"
            },
            style = MaterialTheme.typography.bodySmall
        )

        val effectiveIsTarget = roleSource == RoleSource.TARGET || (roleSource == RoleSource.AUTO && aux == null)
        if (effectiveIsTarget) {
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠️ 当前由目标模型自身做裁判/攻击者：自评安全性置信度较低、自攻覆盖有限，结果仅作辅助参考。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (roleSource == RoleSource.AUX && aux == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "已选「辅助模型」但未配置辅助模型：将退化为纯规则判定，递归不可用。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))
        // 递归可用条件：攻击者能解析出端点。AUX 模式必须真有辅助模型；其余（TARGET / AUTO）总能用目标自身。
        val canRecurse = aux != null || roleSource != RoleSource.AUX
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("递归对抗攻击", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (canRecurse) {
                        val who = if (effectiveIsTarget) "目标模型自身" else "辅助模型"
                        "用${who}迭代生成更强攻击 prompt"
                    } else "无可用模型（需配辅助模型或选「目标自身」）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = useRecursive,
                onCheckedChange = { vm.setUseRecursive(it) },
                enabled = canRecurse
            )
        }

        Spacer(Modifier.height(16.dp))
        // 护栏状态只读展示：它是"判定口径"的一部分，跑之前必须让用户看得见当前用的是哪一档。
        val g = guard
        val guardSummary = if (g == null) {
            "纯规则判定（未启用本地护栏，可在上一步配置）"
        } else when (g.mode) {
            GuardMode.HTTP_SERVICE ->
                "护栏：PC 侧服务 ${g.model} @ ${g.baseUrl}（阈值 ${g.threshold}）"
            GuardMode.ON_DEVICE ->
                "护栏：端上模型 ${g.tier.label}（${g.tier.sizeMb} MB，完全离线，阈值 ${g.threshold}）"
            GuardMode.USER_LLM ->
                "护栏：用户大模型 ${g.model}（自评安全，较弱信号，阈值 ${g.threshold}）"
        }
        Column {
            Text("越狱判定", style = MaterialTheme.typography.titleMedium)
            Text(guardSummary, style = MaterialTheme.typography.bodySmall)
        }

        if (backendUrl.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "🖥 本轮测试将委托后端运行：$backendUrl（被测 Key 仅随请求发往后端，仅该任务周期内在服务器内存留存）",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is TestViewModel.TestState.Error -> {
                Text("出错了：${s.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.startTest() }) { Text("重试") }
            }
            else -> {
                Row {
                    TextButton(onClick = onBack) { Text("返回") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            // 长时后台测试：若还没加电池优化白名单，弹一次系统确认（前台服务 + 唤醒锁已保底，白名单只是进一步降低被回收概率）
                            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${ctx.packageName}")
                                        )
                                    )
                                }
                            }
                            vm.startTest()
                            onStart()
                        },
                        enabled = selected.isNotEmpty() && state !is TestViewModel.TestState.Running
                    ) { Text("开始测试") }
                }
            }
        }
    }
}
