package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xieguojian.aiseclab.core.judge.guard.GuardModelDownloader
import com.xieguojian.aiseclab.core.judge.guard.OnDeviceGuardModel
import com.xieguojian.aiseclab.core.model.GuardMode
import com.xieguojian.aiseclab.core.model.GuardProfile
import com.xieguojian.aiseclab.ui.TestViewModel

@Composable
fun ModelConfigScreen(
    vm: TestViewModel,
    onNext: () -> Unit,
    onHistory: () -> Unit,
    onBackend: () -> Unit
) {
    val target by vm.target.collectAsState()
    val aux by vm.aux.collectAsState()
    val guard by vm.guard.collectAsState()

    var name by remember { mutableStateOf(target?.name ?: "") }
    var baseUrl by remember { mutableStateOf(target?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(target?.apiKey ?: "") }
    var model by remember { mutableStateOf(target?.model ?: "") }

    var auxBaseUrl by remember { mutableStateOf(aux?.baseUrl ?: "") }
    var auxApiKey by remember { mutableStateOf(aux?.apiKey ?: "") }
    var auxModel by remember { mutableStateOf(aux?.model ?: "") }

    var useGuard by remember { mutableStateOf(guard != null) }
    var guardMode by remember { mutableStateOf(guard?.mode ?: GuardMode.HTTP_SERVICE) }
    var guardBaseUrl by remember { mutableStateOf(guard?.baseUrl ?: GuardProfile.DEFAULT_BASE_URL) }
    var guardModel by remember { mutableStateOf(guard?.model ?: GuardProfile.DEFAULT_MODEL) }
    var guardApiKey by remember { mutableStateOf(guard?.apiKey ?: "") }
    var guardTierLabel by remember {
        mutableStateOf(guard?.tierLabel?.takeIf { it.isNotBlank() } ?: OnDeviceGuardModel.DEFAULT.label)
    }

    val guardDownload by vm.guardDownload.collectAsState()
    val readyTiers by vm.readyTiers.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 体检", style = MaterialTheme.typography.headlineMedium)
                Text("接入你自己的模型，跑一轮对抗性红队测试", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onHistory) { Text("历史记录") }
        }
        Spacer(Modifier.height(20.dp))

        Text("目标模型（BYOK）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL（OpenAI 兼容）") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            apiKey, { apiKey = it }, label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(model, { model = it }, label = { Text("模型名（如 gpt-4o-mini）") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        Text("辅助模型（可选，用于 LLM 裁判 + 递归对抗攻击者）", style = MaterialTheme.typography.titleMedium)
        Text("不填则退化为纯规则评判 + 静态变异攻击", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(auxBaseUrl, { auxBaseUrl = it }, label = { Text("辅助 Base URL（可选）") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            auxApiKey, { auxApiKey = it }, label = { Text("辅助 API Key（可选）") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(auxModel, { auxModel = it }, label = { Text("辅助模型名（可选）") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("本地护栏模型（越狱判定）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "YuFeng-XGuard-Reason-0.6B 对越狱类用例做「提问+回复」联合判定，" +
                        "比规则引擎更能分辨「明确拒绝」和「真的输出了」。关掉则退化为纯规则。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = useGuard, onCheckedChange = { useGuard = it })
        }

        if (useGuard) {
            Spacer(Modifier.height(12.dp))

            // ---- 跑在哪：这是唯一影响"判定能力"的开关，放最前面 ----
            GuardMode.values().forEach { m ->
                Row(verticalAlignment = Alignment.Top) {
                    RadioButton(selected = guardMode == m, onClick = { guardMode = m })
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                        Text(m.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when (guardMode) {
                GuardMode.HTTP_SERVICE -> {
                    OutlinedTextField(
                        guardBaseUrl, { guardBaseUrl = it },
                        label = { Text("护栏服务地址（OpenAI 兼容）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        guardModel, { guardModel = it },
                        label = { Text("护栏模型别名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        guardApiKey, { guardApiKey = it },
                        label = { Text("护栏 API Key（本地服务可留空）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "PC 侧启动：python -m llama_cpp.server --model <gguf> --model_alias xguard-0.6b " +
                            "--n_ctx 2048 --port 8000\n真机联调先做端口反向映射：adb reverse tcp:8000 tcp:8000",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                GuardMode.ON_DEVICE -> {
                    Text("量化档位（首次使用需下载）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "档位越小越省空间，但判定质量会下降 —— 报告里会记录所用档位，" +
                            "换档位后应与历史结果分开比较。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))

                    OnDeviceGuardModel.TIERS.forEach { tier ->
                        val ready = tier.label in readyTiers
                        Row(verticalAlignment = Alignment.Top) {
                            RadioButton(
                                selected = guardTierLabel == tier.label,
                                onClick = { guardTierLabel = tier.label }
                            )
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Text(
                                    "${tier.label} · ${tier.sizeMb} MB" +
                                        if (ready) "  ✓ 已就绪" else "",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(tier.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val selectedTier = OnDeviceGuardModel.TIERS
                        .firstOrNull { it.label == guardTierLabel } ?: OnDeviceGuardModel.DEFAULT
                    val selectedReady = selectedTier.label in readyTiers
                    val busy = guardDownload is GuardModelDownloader.State.Progress ||
                        guardDownload is GuardModelDownloader.State.Verifying

                    // 进度条只在"当前下载的就是所选档位"时才有意义
                    when (val d = guardDownload) {
                        is GuardModelDownloader.State.Progress -> {
                            LinearProgressIndicator(
                                progress = { d.fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (d.total > 0) {
                                    "下载中 ${d.downloaded / 1048576} / ${d.total / 1048576} MB" +
                                        "（${(d.fraction * 100).toInt()}%）"
                                } else {
                                    "下载中 ${d.downloaded / 1048576} MB（服务端未给总长度）"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        GuardModelDownloader.State.Verifying -> {
                            Text("校验 SHA256 中…（460 MB 要算几秒）", style = MaterialTheme.typography.bodySmall)
                        }

                        is GuardModelDownloader.State.Failed -> {
                            Text(
                                "下载失败：${d.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        GuardModelDownloader.State.Done -> {
                            Text("模型已就绪，可离线判定。", style = MaterialTheme.typography.bodySmall)
                        }

                        GuardModelDownloader.State.Idle -> Unit
                    }

                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (!selectedReady) {
                            Button(
                                onClick = { vm.downloadGuardModel(selectedTier) },
                                enabled = !busy
                            ) {
                                Text(
                                    if (busy) "下载中…"
                                    else "下载 ${selectedTier.label}（${selectedTier.sizeMb} MB）"
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { vm.deleteGuardModel(selectedTier) },
                                enabled = !busy
                            ) { Text("删除 ${selectedTier.label}") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "下载源：${OnDeviceGuardModel.MIRROR}（HF 国内镜像）。" +
                            "下完会校验 SHA256 —— 校验不过会直接删掉重下，" +
                            "绝不让一份被篡改或截断的权重影响判定。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                GuardMode.USER_LLM -> {
                    Text("用「裁判来源」所选模型做安全分类", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "无需下载护栏权重；默认用目标模型自身（单模型用户），若配了辅助模型且「裁判来源」选辅助则用它。" +
                            "自评安全性是较弱信号，仅作辅助，关键用例建议仍用 XGuard 护栏复核。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        // ---- 测试后端：已独立成页，点进去设置（见 BackendConfigScreen）----
        OutlinedButton(
            onClick = onBackend,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("测试后端设置（可选）", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                vm.saveTarget(name, baseUrl, apiKey, model)
                vm.saveAux(auxBaseUrl, auxApiKey, auxModel)
                vm.saveGuard(
                    if (!useGuard) null else GuardProfile(
                        mode = guardMode,
                        baseUrl = guardBaseUrl,
                        apiKey = guardApiKey,
                        model = guardModel,
                        tierLabel = guardTierLabel
                    )
                )
                onNext()
            }, enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
                Text("下一步")
            }
        }
    }
}
