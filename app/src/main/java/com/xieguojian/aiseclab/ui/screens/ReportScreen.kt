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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.tokenSpendLine
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.ReportDiff
import com.xieguojian.aiseclab.core.report.ReportSections
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.report.ChartLegend
import com.xieguojian.aiseclab.core.report.ChartPalette
import com.xieguojian.aiseclab.core.report.ErosionAnalysis
import com.xieguojian.aiseclab.core.report.ErosionCurve
import com.xieguojian.aiseclab.core.report.HardeningAdvisor
import com.xieguojian.aiseclab.core.report.SafetyMetrics
import com.xieguojian.aiseclab.core.report.cotNote
import com.xieguojian.aiseclab.core.report.modelLabel
import com.xieguojian.aiseclab.ui.chart.BarItem
import com.xieguojian.aiseclab.ui.chart.ErosionTrack
import com.xieguojian.aiseclab.ui.chart.MiniBar
import com.xieguojian.aiseclab.ui.chart.MiniHeatmap
import com.xieguojian.aiseclab.ui.chart.TimelineTrack
import com.xieguojian.aiseclab.ui.TestViewModel
import com.xieguojian.aiseclab.ui.theme.SemanticTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    val history by vm.history.collectAsState()
    val baseline by vm.baseline.collectAsState()
    val ctx = LocalContext.current

    // 最近一次导出的文件，用于展示"已保存"提示 + 分享按钮
    var exportedFile by remember { mutableStateOf<File?>(null) }
    // 是否展开"选基线"面板
    var showBaselinePicker by remember { mutableStateOf(false) }
    // 策略×意图矩阵在手机上占屏较多（5 族 × 至多 8 个意图），默认收起
    var showMatrix by remember { mutableStateOf(false) }

    if (report == null) {
        // 空状态**居中**：原先顶在左上角，平板上尤其像"页面没加载完"。
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text("暂无报告", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "跑完一轮测试后，这里会显示测评报告。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("返回测试") }
        }
        return
    }

    // 基线对比：排除"自己跟自己比"，只留比当前报告早的历史
    val candidates = remember(history, report.timestamp) {
        history.filter { it.timestamp != report.timestamp && it.timestamp < report.timestamp }
    }
    val diff = remember(baseline, report) {
        baseline?.let { ReportDiff.compare(it, report) }
    }

    // 风险色随明暗主题切档（深色模式下自动换亮档，见 ui/theme/SemanticColors.kt）。
    // 「等级 → 语义槽位」的判据在 ChartPalette.slotOf —— 全项目只此一份。
    val riskColor = SemanticTheme.colors.risk(report.riskLevel)

    // 四路分区与导出文档（Markdown / HTML 导出）共用同一份实现，见 core/report/ReportSections.kt。
    // 这里只负责渲染 —— 分区判据抄三遍正是之前那个「needsReview 的 SKIPPED 会重复出现」缺口的根因。
    // 口径：「不可评估」= 调用失败（SKIPPED）+ 判定拿不准（INCONCLUSIVE），两者都从分母里剔出；
    // 展示时必须跟"真的失守"分开说，也必须**彼此**分开说 —— 成因与修法完全不同
    //（一个是网络/限流，一个是三角色裁判没形成多数）。
    val sections = ReportSections.of(report)
    val unassessedByDim = sections.unassessedByDim
    val skippedResults = sections.skipped
    val inconclusiveResults = sections.inconclusive
    // 待人工复核：只列"判成 PASS、但证据链里仍有未消解分歧"的那批。
    //  · FAIL 已经在「失守明细」里逐条列过；
    //  · INCONCLUSIVE / SKIPPED 已经在下面「不可评估」两个区块里列过 ——
    //    若不排除，同一条用例会在界面上出现两遍，而且是两种不同样式的卡片
    //    （FindingItem 有证据链、SkippedItem 只有票型），看起来像两条不同的问题。
    val reviewOnly = sections.reviewOnly
    val noEvidence = report.riskLevel == RiskLevel.UNKNOWN

    // 攻击演进：只列 ≥2 步的用例、按步数降序。单步没有"演进"可言，
    // 50 条全画又会让报告长出好几屏（手机上尤其）。
    // 判据收敛在 ReportSections.trailCases —— 报告页与导出两处必须同源
    //（这里过去自己 filter，而导出侧把时间轴塞在失守明细里 → 失守 0 条时导出件丢图，真机抓到）。
    // ⚠️ 必须在 LazyColumn **外面**算：里面是 LazyListScope、不是 @Composable 上下文，
    // 在那儿调 remember 直接编译不过（2026-10-12 踩过）。
    val trailCases = sections.trailCases

    // 多轮侵蚀：只列"≥2 步且可评估"的用例，按设计策略族聚合。
    // 取数全部收敛在 core/report/ErosionCurve.kt（报告页与两个导出件同源），
    // 这里连 P50/P90 都不重算 —— 同一份数据三处各算一遍正是历史上走岔的方式。
    // ⚠️ 同样必须在 LazyColumn 外算。
    val erosion = remember(report) { ErosionAnalysis.of(report.results) }

    // 安全指标（方案 §5）：破防步数**复用上面已算好的 erosion**，不在这里重算一遍。
    // 取值格式与口径文案全部来自 SafetyMetrics.rows()，与两个导出件同源 ——
    // 「界面说 27%、导出说 0.27」这类不一致只有肉眼能发现，所以不给它发生的机会。
    val metrics = remember(report, erosion) { SafetyMetrics.of(report.results, erosion) }

    // 加固建议（方案 §3.5）：取证闭环的最后一问「怎么修」。
    // 只对**失守**用例出（通过的用例没有要修的东西），且归因判据与上面的指标面板**同源**
    // （都走 core/report/DefenseAttribution.kt）—— 否则会出现"指标面板说规划层失效、
    // 加固建议却让人去改输出过滤"这种自相矛盾的报告，答辩被追问是硬伤。
    val hardening = remember(report) { HardeningAdvisor.advise(sections.fails) }

    // 大屏（平板）上限宽：不限宽时一行正文能拉到 1000dp 以上，眼睛得来回扫。
    // 「限宽 + 居中」的完整写法与顺序约束见下面修饰符链上的注释。
    // 手机宽度（≈360~420dp）够不到这个上限，所以对手机**零影响**。
    LazyColumn(
        Modifier
            // 三个修饰符各管一件事，**顺序不能动**：
            //  1) `fillMaxSize()` 让列表占满屏幕（副作用：把子项的 minWidth 顶到父宽）；
            //  2) `wrapContentWidth(...)` 把"给子项的 minWidth"重新放回 0 —— 少了它，第 3 步的
            //     max 会被父约束 coerceIn 夹回去、上限**完全失效**（这条 2026-10-12 踩过）；
            //     同时它负责**居中**：`widthIn` 只能限宽，"内容摆在父容器的哪一侧"是父容器说了算的，
            //     而这里没有外层容器 —— 于是宽屏下内容贴左、右侧糊一大片空白。
            //     2026-10-14 在等效 1067dp 宽（wm size/density 覆盖）下实测：
            //       加之前 内容块 x=[30,1229]  → 左 20dp / 右 247dp（贴左，右边糊一大片）
            //       加之后 内容块 x=[200,1399] → 左 133dp / 右 133dp（对称）
            //     限宽本身没被破坏：两版的内容宽都是 799dp = 840dp 上限 − 两侧 20dp 内边距。
            //     （这与当年"空状态顶在左上角、平板上尤其像页面没加载完"是同一个问题。）
            //  3) `widthIn(max = CONTENT_MAX_WIDTH)` 才是真正的限宽；`padding` 收在最内层。
            .fillMaxSize()
            .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
            .widthIn(max = CONTENT_MAX_WIDTH)
            .padding(20.dp)
    ) {
        item {
            Text("AISecLab 测评报告", style = MaterialTheme.typography.headlineMedium)
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
                Text(report.riskLevel.label, color = riskColor, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            // 三个导出按钮**分两行**：手机可用宽度约 320dp，「导出 HTML / 导出 Markdown / 导出 JSON」
            // 一行放不下，会挤成截断 —— 而按钮文字被截断在 uiautomator dump 里仍是完整的，
            // 只有截图能发现（本项目在报告页踩过这个坑）。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportedFile = vm.exportHtml(reportOverride) }) { Text("导出 HTML") }
                OutlinedButton(onClick = { exportedFile = vm.exportMarkdown(reportOverride) }) { Text("导出 Markdown") }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                // 取证 JSON：给程序读的出口（报告落盘 / 服务端汇总 / 多模型对比）。
                OutlinedButton(onClick = { exportedFile = vm.exportJson(reportOverride) }) { Text("导出 JSON") }
            }
            // 导出反馈：之前写到 cacheDir 且忽略返回值，用户够不着也没提示。
            // 现在写外部 Download 目录并拿到 File，显示路径 + 一键分享/打开。
            exportedFile?.let { file ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("已导出：${file.name}", style = MaterialTheme.typography.bodyMedium)
                        // 落盘路径在手机窄屏下必然超一行，且路径里没有可断行的空格，
                        // Compose 会直接按字符硬断 —— 视觉上是一条破折的乱码串。
                        // 给 2 行上限 + 省略号（Compose BOM 2024.06 无 MiddleEllipsis）。
                        Text(
                            file.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                val uri = FileProvider.getUriForFile(
                                    ctx, ctx.packageName + ".fileprovider", file
                                )
                                // JSON 走 application/json：分享到微信/邮件时别被当成纯文本附件。
                                val mime = when (file.extension) {
                                    "html" -> "text/html"
                                    "json" -> "application/json"
                                    else -> "text/markdown"
                                }
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
        }

        // ── 摘要：先给结论，再给数据 ──
        // 原来摘要在维度卡片之后，用户得滚到底才知道"这轮到底怎么样"。
        item {
            Spacer(Modifier.height(16.dp))
            Text("摘要", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(report.summary, style = MaterialTheme.typography.bodyMedium)
        }

        // ── 本轮账单：这一轮烧了多少 token ──
        // 口径与跑的时候界面上那行实时计数同源（core/api/TokenMeter 的 toTokenUsage），
        // 但这一份是**落进报告**的 —— 历史报告翻出来也能看到当时花了多少，
        // 而跑的时候那行只在当下可见。
        // tokenUsage == null 时显示「未记录」而不是 0：「没记录」和「一次没烧」是两回事。
        item {
            Spacer(Modifier.height(16.dp))
            Text("本次消耗", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                report.tokenSpendLine(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (report.tokenUsage == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }

        // ── CoT 处理口径：报告的分数依赖「被测模型有没有内联思考段」，必须自证 ──
        // 剥了多少条由 results[].response 现算，不设字段（见 core/report/CoTNote.kt）。
        // 文案唯一源 = CoTNote.kt（与 Markdown / HTML 导出同源）。
        item {
            Spacer(Modifier.height(16.dp))
            Text("CoT 处理", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(report.cotNote(), style = MaterialTheme.typography.bodyMedium)
        }

        // ── 安全指标（方案 §5）：别再只报 ASR ──
        // 放在摘要之后、图表之前：报告要先回答"到底多危险、哪一层先垮"，
        // 再给逐条证据。取值与口径全部来自 SafetyMetrics，这里只负责排版。
        if (metrics.evaluated + metrics.unassessed > 0) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("安全指标", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
            }
            item { MetricsPanel(metrics) }
            if (metrics.matrixUsable) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("策略 × 意图", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showMatrix = !showMatrix }) {
                            Text(if (showMatrix) "收起" else "展开")
                        }
                    }
                    Text(
                        ChartLegend.MATRIX,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showMatrix) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        StrategyIntentMatrix(metrics)
                    }
                }
            }
        }

        // ── 加固建议（方案 §3.5）：取证闭环的最后一问「怎么修」──
        // 只对**失守**用例出（通过的用例没有要修的东西）。归因判据与上面的安全指标面板
        // **同源**（都走 core/report/DefenseAttribution.kt）—— 否则会出现"指标面板说规划层失效、
        // 这里却让人去改输出过滤"的自相矛盾报告，答辩被追问是硬伤。
        // 放在安全指标之后、图表之前：报告先回答"多危险、哪层先垮"，紧接着回答"那我怎么改"。
        if (!hardening.isEmpty) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("加固建议", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "按「攻击手法 × 先失效的护栏层」聚合，按中招条数降序 —— 第一条就是该先修的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { HardeningPanel(hardening) }
        }

        // ── 图表：用例矩阵 / 分区分布 / 判定层级 ──
        // 取数一律来自已有的 ReportSections 与 JudgeResult，**这里不做任何新判定**
        //（自己写 filter 数一遍正是 ReportSections 存在的理由）。
        // 图例文案与 HTML 导出共用 core/report/ChartLegend，避免两处说法不一致。
        item {
            Spacer(Modifier.height(20.dp))
            Text("用例热力图", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            MiniHeatmap(report.results)
            Spacer(Modifier.height(6.dp))
            Text(
                ChartLegend.HEATMAP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Spacer(Modifier.height(18.dp))
            Text("分区分布", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            MiniBar(
                listOf(
                    BarItem("失守", sections.fails.size, ChartPalette.FAIL),
                    BarItem("待人工复核", sections.reviewOnly.size, ChartPalette.INCONCLUSIVE),
                    BarItem("通过", sections.passPlain.size, ChartPalette.PASS),
                    BarItem("不可评估·调用失败", sections.skipped.size, ChartPalette.SKIPPED),
                    BarItem("不可评估·判定拿不准", sections.inconclusive.size, ChartPalette.INCONCLUSIVE)
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ChartLegend.BARS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Spacer(Modifier.height(18.dp))
            Text("判定层级分布", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            MiniBar(
                JudgeLevel.values().map { lv ->
                    BarItem(levelLabel(lv), levelCount(report, lv), ChartPalette.PASS)
                }
            )
        }

        // ── 基线对比 ──
        item {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("基线对比", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (baseline != null) {
                    TextButton(onClick = { vm.toggleBaseline(baseline!!) }) { Text("清除基线") }
                }
                TextButton(onClick = { showBaselinePicker = !showBaselinePicker }) {
                    Text(if (showBaselinePicker) "收起" else "选择基线")
                }
            }
        }

        if (showBaselinePicker) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (candidates.isEmpty()) {
                            Text(
                                "没有更早的历史报告可作基线。跑完第二轮后就能对比了。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("选一份更早的报告作基线：", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            candidates.take(20).forEach { cand ->
                                val selected = baseline?.timestamp == cand.timestamp
                                TextButton(onClick = {
                                    vm.toggleBaseline(cand)
                                    showBaselinePicker = false
                                }) {
                                    Text(
                                        (if (selected) "● " else "○ ") +
                                            "${timeFmtOnly(cand.timestamp)} · " +
                                            "%.1f".format(cand.overallScore) + " 分",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 对比结果
        if (diff != null) {
            item {
                DiffSummaryCard(diff)
            }
            if (diff.regressions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("新增失守（基线通过 → 本轮失守）", style = MaterialTheme.typography.titleSmall,
                        color = SemanticTheme.colors.bad)
                }
                items(diff.regressions) { c -> ChangeItem(c, ReportDiff.verdictText(c.from), "失守") }
            }
            if (diff.fixed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("已修复（基线失守 → 本轮通过）", style = MaterialTheme.typography.titleSmall,
                        color = SemanticTheme.colors.good)
                }
                items(diff.fixed) { c -> ChangeItem(c, ReportDiff.verdictText(c.from), "通过") }
            }
            if (diff.uncomparable.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("无法比较（含不可评估/待复核）", style = MaterialTheme.typography.titleSmall,
                        color = SemanticTheme.colors.neutral)
                }
                items(diff.uncomparable) { c -> ChangeItem(c, ReportDiff.verdictText(c.from), ReportDiff.verdictText(c.to)) }
            }
        }

        // ── 分维度得分：标题紧跟卡片，别再被别的区块隔开 ──
        item {
            Spacer(Modifier.height(20.dp))
            Text("分维度得分", style = MaterialTheme.typography.titleMedium)
        }

        items(report.dimensions.values.sortedBy { it.dimension.ordinal }.toList()) { d ->
            val unassessedHere = unassessedByDim[d.dimension] ?: 0
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
                            "未评估（$unassessedHere 条用例全部不可评估，不计入总分）"
                        } else {
                            val skipNote = if (unassessedHere > 0) " · 不可评估 $unassessedHere 条" else ""
                            "通过 ${d.passed}/${d.total} · 失守率 %.0f%%".format(d.failRate * 100) + skipNote
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    // 维度结论告警：后端把「失守用例 / ⚠️判定不稳定 / ℹ️稳定性未测量」写进
                    // notableFindings，但界面此前**一处都没读**，等于白算。这里补上。
                    d.notableFindings.forEach { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                note.startsWith("⚠️") -> SemanticTheme.colors.high
                                note.startsWith("ℹ️") -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        // ── 攻击演进（时间轴）──
        if (trailCases.isNotEmpty()) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("攻击演进", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    ChartLegend.TIMELINE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (trailCases.size > TRAIL_CASES_SHOWN) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 ${trailCases.size} 条用例有多步攻击，这里按步数取前 $TRAIL_CASES_SHOWN 条。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(trailCases.take(TRAIL_CASES_SHOWN)) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            // 标题可能长达几十字。无上限时它会换行把行高撑起，
                            // 而右侧"N 步 · verdict"是单行且垂直居中 → 两栏错位。
                            // 给 2 行上限，两栏高度都可预期。
                            Text(
                                "${r.testCase.dimension.label} · ${r.testCase.title}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            // verdict 最长的是 INCONCLUSIVE（12 字）。这一栏没有宽度上限，
                            // 会把左侧 weight(1f) 压到几乎没宽度 —— 左侧变成"..."而右侧完整，
                            // 正好把最有信息量的用例标题让给了枚举名。
                            Text(
                                "${r.attackRounds.size} 步 · ${r.judgeResult.verdict}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TimelineTrack(r.attackRounds)
                    }
                }
            }
        }

        // ── 多轮侵蚀曲线 ──
        // 与「攻击演进」的分工：演进看**单条用例**逐步发生了什么，侵蚀看**跨用例**
        // 「第几步被磨破」。两者都只在多步记录存在时才有意义，所以共享同一个 2 步门槛。
        //
        // ⚠️ 2026-10-13 起**无条件渲染**：以前是 `multiStepTotal > 0` 才显示，
        // 结果一次真机运行（本机跑、没开递归对抗）里侵蚀整块静默消失 ——
        // 而「本次没采集多步证据」本身就是审计要看见的结论，不能靠「不显示」来表达。
        item {
            Spacer(Modifier.height(20.dp))
            Text("多轮侵蚀", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                ChartLegend.EROSION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(erosion.headline, style = MaterialTheme.typography.bodyMedium)
            // 两条「信号不一致」的说明必须跟着 headline 显示：
            // 它们是「失守 0 条」与「某步无拒绝话术」同时出现时的解释，
            // 不显示的话读者只会认为报告在瞎算（真机验证时就是这么撞上的）。
            listOf(erosion.exclusionNote, erosion.disagreementNote, erosion.unresolvedNote)
                .filter { it.isNotEmpty() }
                .forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        }
        if (erosion.evaluated > 0) {
            item {
                Spacer(Modifier.height(10.dp))
                ErosionTrack(erosion)
            }
            item {
                Spacer(Modifier.height(12.dp))
                ErosionTable(erosion)
            }
        } else {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    ChartLegend.EROSION_EMPTY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Text("失守明细", style = MaterialTheme.typography.titleMedium)
        }

        val fails = sections.fails
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

        // 待复核（非失守的那部分）：判成 PASS，但证据链里有未消解的分歧。
        // 不给它单列的话，那 3 条「2:1 判过」的用例的复核标记在界面上永远看不到 ——
        // FindingItem 只被失守明细调用，而它们是 PASS。
        if (reviewOnly.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("待人工复核 ${reviewOnly.size} 条", style = MaterialTheme.typography.titleMedium)
                Text(
                    "这些用例判定为通过，但证据链里存在未消解的分歧（规则层与 LLM 结论相反，或三角色投票分裂）。" +
                        "不影响总分，但可靠性不如一致通过，值得扫一眼。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(reviewOnly) { r -> FindingItem(r) }
        }

        // 不可评估的用例单独列出来：既不冒充失守，也不静默丢掉。
        // 调用失败与"判定拿不准"分开列 —— 成因和修法都不同。
        if (skippedResults.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("不可评估 · 调用失败 ${skippedResults.size} 条", style = MaterialTheme.typography.titleMedium)
                Text(
                    "这几条没能拿到有效回答（接口异常 / 限流 / 超时），已从失守率与总分中剔除。" +
                        "它们不代表模型有风险，也不代表模型安全；查网络与配额，别去查模型。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(skippedResults) { r -> SkippedItem(r) }
        }

        if (inconclusiveResults.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("不可评估 · 判定拿不准 ${inconclusiveResults.size} 条", style = MaterialTheme.typography.titleMedium)
                Text(
                    "三角色裁判没形成多数结论，同样已剔出分母。这些是「判定链」的问题（不是模型的），" +
                        "建议看分歧记录、必要时人工复核。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(inconclusiveResults) { r -> SkippedItem(r) }
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
            // 多次采样的一致性：只有真的测过（stabilityMeasured）才显示，且只在不一致时
            // 才提示 —— 单样本模式的 1.0 是占位值，显示出来反而会被误读成"很稳定"。
            if (r.stabilityMeasured) {
                Text(
                    if (r.stability < 0.8f) "⚠️ 判定不稳定：采样一致性仅 %.0f%%（结论可能不可靠）".format(r.stability * 100)
                    else "采样一致性 %.0f%%".format(r.stability * 100),
                    color = if (r.stability < 0.8f) SemanticTheme.colors.high else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
            // 不可评估的两种成因给不同的解释来源：调用失败看接口错误，判定拿不准看票型分歧。
            // 原来一律显示 evidence.first()，"拿不准"的那批会显示成裁判的某一句理由，看不出重点。
            val detail = if (r.judgeResult.verdict == Verdict.INCONCLUSIVE) {
                r.judgeResult.disagreements.joinToString("；").ifBlank { "三角色裁判未形成多数结论" }
            } else {
                r.judgeResult.evidence.firstOrNull() ?: "接口调用失败，本条不可评估"
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 侵蚀统计的按策略族明细。
 *
 * 为什么不做成 7 列表格：手机宽度下表格列会被静默截成省略号
 * （`uiautomator dump` 里文本还完整，只有截图能看出来 —— 报告页踩过这个坑）。
 * 一族一个卡片、两行文字，列宽不必猜。
 */
@Composable
private fun ErosionTable(analysis: ErosionAnalysis) {
    Column { analysis.curves.forEach { c -> ErosionRow(c) } }
}

@Composable
private fun ErosionRow(c: ErosionCurve) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(c.strategy.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${c.total} 条多步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "可评估多步 ${c.total} · 判定失守 ${c.failed} · 定位出破防步 ${c.located}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "破防步数 P50 ${oneDecimal(c.p50)} · P90 ${oneDecimal(c.p90)}" +
                    " · ≤${ErosionAnalysis.BROKE_EARLY_TURNS} 步失守 ${c.brokeEarly}" +
                    (if (c.unresolved > 0) " · 定位不出破防步 ${c.unresolved} 条" else "") +
                    (if (c.excludedUnmeasured > 0) " · 剔除不可评估 ${c.excludedUnmeasured} 条" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 分位数显示：null = 该族一条都没破防 → `--`（不能显示 0.0）。 */
private fun oneDecimal(v: Float?): String = v?.let { "%.1f".format(it) } ?: "--"

/**
 * 加固建议面板（方案 §3.5）：取证闭环的最后一问「怎么修」。
 *
 * 取数全部来自 [HardeningAdvisor]（报告页 / Markdown / HTML 三处同源），这里只负责排版。
 * 一条建议 = 一个「攻击手法 × 先失效护栏层」组合，右上角的**中招条数**同时是排序依据 ——
 * 排最前的就是该先修的那条。
 *
 * ⚠️ 建议文本里**不能出现 Markdown 标记**（`**` 之类）：Compose 的 Text 不解析 Markdown，
 * 写进去会原样显示成星号。有单测钉住（HardeningAdvisorTest 的「建议文本不含 Markdown 标记」）。
 */
@Composable
private fun HardeningPanel(hardening: HardeningAdvisor.HardeningReport) {
    Column {
        hardening.items.forEach { item -> HardeningCard(item) }
        // 定位不出失效层的条数必须单独说，不能静默吞掉：那是「没测到」，不是「没有」。
        // 文案取自 HardeningReport.unattributedNote —— 与两个导出件同源，这里不自己拼字符串
        //（拼三遍就会走岔；而且「其中」和「另有」差一个字，报告就自相矛盾了）。
        if (hardening.unattributed > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                hardening.unattributedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单条加固建议：手法 + 中招数 + 失效层 + 可执行动作 +（可选）可直接粘的提示片段。 */
@Composable
private fun HardeningCard(item: HardeningAdvisor.HardeningItem) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(item.strategy.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${item.cases} 条中招",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 层徽标：只有真定位出来了才画。定位不出时写「不可归因」——
            // 绝不默认塞一层，那正是「拿没测到当测出来」的同一种错。
            Text(
                item.layer?.let { "先失效：${it.label}" } ?: "先失效层不可归因",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.hasLayer) SemanticTheme.colors.high else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(item.action, style = MaterialTheme.typography.bodyMedium)
            item.snippet?.let { s ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "可直接粘进系统提示：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(s, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 安全指标面板：一条指标一张小卡（名称 / 取值 / 口径）。
 *
 * 口径文案必须跟着显示 —— 这几个指标里有一半的坑都在"怎么算"上
 * （一致性会奖励一律照做的模型、过度拒绝只是下界、失效层只归可观测的两层），
 * 光给数字等于让读者按直觉误读。
 */
@Composable
private fun MetricsPanel(metrics: SafetyMetrics) {
    Column {
        metrics.rows().forEach { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(m.value, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        m.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 策略 × 意图矩阵。
 *
 * 手机上不做真网格：8 个意图的列头（「恶意代码/入侵」这种 7 字标签）在窄屏必然被截成省略号，
 * 而**截断的标签在 `uiautomator dump` 里仍是完整的**，只有截图能发现（报告页踩过）。
 * 改成一族一块、族内一行一个意图，条形长度 = 该意图下的 ASR（右侧数字即百分比）。
 *
 * ⚠️ 两条 2026-10-13 真机跑完才定下来的规矩：
 * 1. **条形按绝对刻度画（[MiniBar] 的 `scaleMax = 100`），不做族内归一化。**
 *    原先按族内最大值归一化，于是「25%（该族最差的一格，颜色是绿色）」被画成满格 ——
 *    长度说「本族最大」、颜色说「很好」，两套语义叠在同一根条上，读不出结论。
 * 2. **没样本的意图也要占一行并显示 `--`。** [SafetyMetrics.cellsOf] 现在补齐全部意图，
 *    漏掉的话「没测到」在这套行式布局里会退化成「整行消失」，
 *    而图例却写着「空着的格子 = 没测到」—— 手机上根本没有对应物。
 */
@Composable
private fun StrategyIntentMatrix(metrics: SafetyMetrics) {
    Column {
        metrics.matrixStrategies.forEach { st ->
            Text(st.label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            MiniBar(
                items = metrics.cellsOf(st).map { c ->
                    val asr = c.asr
                    val pct = asr?.let { (it * 100).roundToInt() }
                    BarItem(
                        label = c.intent.label,
                        // 没样本时 value 兜底 0（条长为空），但**文字必须给 `--`** ——
                        // 显示 0% 会被读成「试过了、这招不管用」，而事实是「没测过」。
                        value = pct ?: 0,
                        color = if (asr == null) ChartPalette.SKIPPED else ChartPalette.asrColor(asr),
                        text = pct?.let { "$it%" } ?: "--"
                    )
                },
                scaleMax = 100
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "条长 = ASR 绝对值（0~100%），与颜色同一把尺；「--」= 该组合没有可评估样本（没测到），不是 0%。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 基线对比：总分/维度 delta 一览。 */
@Composable
private fun DiffSummaryCard(diff: ReportDiff.Result) {
    val baseTime = timeFmtOnly(diff.base.timestamp)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("对比基线（$baseTime）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(
                    if (diff.base.riskLevel == RiskLevel.UNKNOWN) "--" else "%.1f".format(diff.baseTotal),
                    style = MaterialTheme.typography.titleLarge,
                    color = SemanticTheme.colors.neutral
                )
                Text(" → ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
                Text(
                    if (diff.cur.riskLevel == RiskLevel.UNKNOWN) "--" else "%.1f".format(diff.curTotal),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (diff.cur.riskLevel == RiskLevel.UNKNOWN) SemanticTheme.colors.neutral
                    else SemanticTheme.colors.good
                )
                Spacer(Modifier.weight(1f))
                // scoreDelta 为 null = 有--侧无有效样本，不能比
                val d = diff.scoreDelta
                Text(
                    when {
                        d == null -> "无法比较"
                        d > 0f -> "↑ %.1f".format(d)
                        d < 0f -> "↓ %.1f".format(-d)
                        else -> "持平"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        d == null -> SemanticTheme.colors.neutral
                        d > 0f -> SemanticTheme.colors.good
                        d < 0f -> SemanticTheme.colors.bad
                        else -> SemanticTheme.colors.neutral
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            diff.dimChanges.forEach { dc ->
                val bd = dc.delta
                Text(
                    "${dc.dimension.label}：" +
                        (bd?.let { "%+.1f".format(it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        bd == null -> SemanticTheme.colors.neutral
                        bd > 0f -> SemanticTheme.colors.good
                        bd < 0f -> SemanticTheme.colors.bad
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** 单条变化项：维度·标题 + from→to。 */
@Composable
private fun ChangeItem(c: ReportDiff.CaseChange, fromText: String, toText: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${c.result.testCase.dimension.label} · ${c.result.testCase.title}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "基线：$fromText → 本轮：$toText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun timeFmtOnly(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/** 时间轴区块最多画几条用例 —— 超出只给计数，避免报告被图表淹掉。 */
private const val TRAIL_CASES_SHOWN = 8

/**
 * 内容最大宽度。超过就不再加宽 —— 平板上一行正文若拉到 1000dp 以上，读的时候眼睛要来回扫。
 * 手机（≈360~420dp）永远够不到这个值，所以这条约束对手机零影响。
 */
private val CONTENT_MAX_WIDTH = 840.dp

/** 判定层级分布取数：只认 `JudgeResult.level`，不做任何新判定。 */
private fun levelCount(report: Report, level: JudgeLevel): Int =
    report.results.count { it.judgeResult.level == level }

private fun levelLabel(level: JudgeLevel): String = when (level) {
    JudgeLevel.L1_PATTERN -> "L1 正则"
    JudgeLevel.L2_SEMANTIC -> "L2 语义"
    JudgeLevel.L3_STRUCTURAL -> "L3 结构"
    JudgeLevel.LLM -> "LLM 裁判"
}

