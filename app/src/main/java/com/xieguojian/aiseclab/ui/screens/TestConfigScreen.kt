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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.RunMode
import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.JailbreakVariantsModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.RoleSource
import com.xieguojian.aiseclab.core.resolveRunMode
import com.xieguojian.aiseclab.ui.TestViewModel

@Composable
fun TestConfigScreen(vm: TestViewModel, onBack: () -> Unit, onStart: () -> Unit) {
    val selected by vm.selectedDimensions.collectAsState()
    val useRecursive by vm.useRecursive.collectAsState()
    val useVariants by vm.useVariants.collectAsState()
    val roleSource by vm.roleSource.collectAsState()
        val aux by vm.aux.collectAsState()
        val backendUrl by vm.backendUrl.collectAsState()
        val localRun by vm.localRunEnabled.collectAsState()
    val state by vm.testState.collectAsState()
    val ctx = LocalContext.current

    // 每个维度实际有多少条用例：让用户在点「开始测试」前就知道这一轮的规模。
    // 条数直接决定耗时和 API 花费，而之前勾完维度是一点概念都没有的。
    val variantCount = remember { JailbreakVariantsModule().buildCases().size }
    val casesPerDim = remember(useVariants) {
        val base = listOf(SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule())
            .associate { it.dimension to it.buildCases().size }
        // 变体集只挂在安全维度上：打开后该维度条数必须跟着涨，
        // 否则界面上写的「本轮 X 条」是假的 —— 用户按这个数字估时间与花费。
        if (useVariants) base + (Dimension.SECURITY to (base.getValue(Dimension.SECURITY) + variantCount))
        else base
    }
    val totalCases = selected.sumOf { casesPerDim[it] ?: 0 }

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

        Spacer(Modifier.height(10.dp))
        Text(
            if (selected.isEmpty()) "未选择维度，无法开始测试"
            else "本轮将评估 $totalCases 条用例：" + selected.sortedBy { it.ordinal }
                .joinToString(" / ") { "${it.label} ${casesPerDim[it] ?: 0}" },
            style = MaterialTheme.typography.bodySmall,
            color = if (selected.isEmpty()) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )

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
        Spacer(Modifier.height(6.dp))
        // 三选一却一个字都不解释，用户只能瞎猜 —— 尤其「目标自身」是让被测模型自己评自己，
        // 这直接决定结论的说服力，必须写明白。
        Text(
            when (roleSource) {
                RoleSource.AUTO ->
                    "有辅助模型就用它做裁判 / 攻击者，没有则用目标模型自身。不确定时选这个。"
                RoleSource.AUX ->
                    "只用辅助模型；若未配置辅助模型，会回退到目标模型自身。"
                RoleSource.TARGET ->
                    "让目标模型自己评自己、自己攻自己。不需要额外模型，但「自己给自己打分」" +
                        "的说服力较弱，建议只在没有辅助模型时使用。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        // 递归可用条件：攻击者能解析出端点。AUX 模式必须真有辅助模型；其余（TARGET / AUTO）总能用目标自身。
        val canRecurse = aux != null || roleSource != RoleSource.AUX
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("递归对抗攻击", style = MaterialTheme.typography.titleMedium)
            }
            Switch(
                checked = useRecursive,
                onCheckedChange = { vm.setUseRecursive(it) },
                enabled = canRecurse
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "⏱ 递归对抗攻击会多轮迭代生成更强的攻击 prompt，所用时间较长，请谨慎选择。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        // 越狱变体集：同一批有害意图 × 10 类策略（角色扮演/系统提示覆盖/虚构情境/编码混淆/拒绝抑制 +
        // 说服改写/说服分类学/历史学术包装/思维实验/游戏化）
        // × 各 20 条 = 200 条。加了它才能回答「换个包装还攻不攻得破」，代价是安全维度条数 ×12。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("越狱变体集", style = MaterialTheme.typography.titleMedium)
            }
            Switch(
                checked = useVariants,
                onCheckedChange = { vm.setUseVariants(it) },
                enabled = Dimension.SECURITY in selected
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (Dimension.SECURITY !in selected)
                "需先勾选「安全性」维度 —— 变体集是安全用例，只勾别的维度它不会跑。"
            else "💥 再并入 $variantCount 条：20 个有害目标 × 10 类策略" +
                "（角色扮演 / 系统提示覆盖 / 虚构情境 / 编码混淆 / 拒绝抑制 /" +
                "说服改写 / 说服分类学 / 历史学术包装 / 思维实验 / 游戏化）。" +
                "用同一批意图只换包装，用来量「哪种手法最容易得手」。",
            style = MaterialTheme.typography.bodySmall,
            color = if (Dimension.SECURITY !in selected) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        // 运行位置：与 TestRunService 的分支同源（core/RunMode.kt），别再各写一遍判据。
        // 开关打开时本机优先、忽略已填地址 —— 否则地址填过一次就再也回不到本机跑。
        when (resolveRunMode(backendUrl, localRun)) {
            RunMode.BACKEND -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "🖥 本轮测试将委托后端运行：$backendUrl（被测 Key 仅随请求发往后端，仅该任务周期内在服务器内存留存）",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            RunMode.LOCAL -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "💻 本轮在本机运行：" +
                        (if (backendUrl.isNotBlank())
                            "设置页的「本机运行测试」已开启，已填的后端地址本轮不生效（想改走后端就关掉该开关）。"
                        else "未配置测试后端，由端上直接跑。"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("端上跑没有多次采样稳定性保护（单次采样定论、方差无人兜底）。")
                        if (useRecursive) {
                            append("已开「递归对抗」：要配好辅助模型，否则攻击编排会退化成静态变异。")
                        } else {
                            append("本轮没开「递归对抗」，逐轮记录不会被采集，报告里的「多轮侵蚀 / 破防步数 / " +
                                "分层归因」会是空的 —— 想看这几块，把上面的「递归对抗」打开（需先配好辅助模型）。")
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            null -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ 未配置测试后端，且「本机运行测试」已关闭（设置页可开启）。\n" +
                        "端上跑缺少多次采样稳定性保护，默认不启用；请先到设置 → 测试后端填写地址并测试连通性，" +
                        "或在设置里开启「本机运行测试」。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
                        enabled = selected.isNotEmpty() &&
                            state !is TestViewModel.TestState.Running &&
                            resolveRunMode(backendUrl, localRun) != null
                    ) { Text("开始测试") }
                }
            }
        }
    }
}
