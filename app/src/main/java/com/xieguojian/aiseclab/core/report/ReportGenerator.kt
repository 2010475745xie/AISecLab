package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.tokenSpendLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报告里展示的模型标识：模型 id 居前（这才是用户真正关心的"测的是哪个模型"），
 * name 仅作次要别名附在括号里。避免配置里 name 填了无意义占位（如 "1"）时报告读起来怪。
 */
internal fun modelLabel(p: ModelProfile): String =
    if (p.name.isBlank()) p.model
    else if (p.name == p.model) p.model
    else "${p.model}（${p.name}）"

/**
 * 报告导出：Markdown / 自包含 HTML。PDF 后续用 android.graphics.pdf 或分享 HTML 补。
 *
 * 展示口径（与 ScoringModel 一致，别再走岔）：
 * - FAIL = 真失守；SKIPPED = 接口调用失败，**不可评估**，既不算失守也不算安全；
 * - 一个维度 0 条可评估时，得分显示 `--` 而不是 `0.0` —— 0 分是"测出来很差"，`--` 是"没测到"。
 *
 * 导出给**完整回答**：报告是审计/复盘产物，截断会丢证据（早期版本写死 take(300) 把长回答腰斩）。
 */
object ReportGenerator {

    fun toMarkdown(report: Report, diff: ReportDiff.Result? = null): String = buildString {
        val noEvidence = report.riskLevel == RiskLevel.UNKNOWN
        // 分区与报告页 / HTML 导出共用一份实现（ReportSections）—— 判据抄三遍必然走岔。
        val sections = ReportSections.of(report)
        append("# AISecLab 测评报告\n\n")
        append("**模型**：${modelLabel(report.modelProfile)}  \n")
        append("**时间**：${formatTime(report.timestamp)}  \n")
        append("**总分**：${if (noEvidence) "--（无有效样本）" else "%.1f".format(report.overallScore)} / 100  \n")
        append("**风险等级**：${riskText(report.riskLevel)}\n\n")

        append("## 分维度得分\n\n")
        append("| 维度 | 得分 | 通过/总数 | 失守率 |\n")
        append("|---|---|---|---|\n")
        report.dimensions.values.sortedBy { it.dimension.ordinal }.forEach { d ->
            val score = if (d.total == 0) "--" else "%.1f".format(d.score)
            val ratio = if (d.total == 0) "未评估" else "${d.passed}/${d.total}"
            val rate = if (d.total == 0) "--" else "%.0f%%".format(d.failRate * 100)
            append("| ${d.dimension.label} | $score | $ratio | $rate |\n")
        }
        // 维度结论里的告警（失守用例、⚠️判定不稳定、ℹ️稳定性未测量）此前只躺在数据结构里，
        // 界面与导出都没读，等于后端白算 —— 这里补上。
        val notes = report.dimensions.values.sortedBy { it.dimension.ordinal }
            .filter { it.notableFindings.isNotEmpty() }
        if (notes.isNotEmpty()) {
            append("\n### 维度结论\n\n")
            notes.forEach { d ->
                append("**${d.dimension.label}**\n\n")
                d.notableFindings.forEach { append("- $it\n") }
                append("\n")
            }
        }
        append("\n## 摘要\n\n${report.summary}\n\n")
        // 本轮账单。文案与报告页/HTML 导出同源（Report.tokenSpendLine），别各自拼。
        append("\n## 本次消耗\n\n${report.tokenSpendLine()}\n\n")

        // ── 安全指标（方案 §5）──
        // 取数、取值格式、口径文案**全部来自 SafetyMetrics**：报告页 / Markdown / HTML
        // 三处共用同一批字符串，不允许各自 format 一遍（那正是「界面 27%、导出 0.27」的来路）。
        val erosion = ErosionAnalysis.of(report.results)
        val metrics = SafetyMetrics.of(report.results, erosion)
        if (metrics.evaluated + metrics.unassessed > 0) {
            append("## 安全指标\n\n")
            append("| 指标 | 值 | 口径 |\n|---|---|---|\n")
            metrics.rows().forEach { m -> append("| ${m.label} | ${m.value} | ${m.hint} |\n") }
            append("\n")
            if (metrics.matrixUsable) {
                append("### 策略 × 意图\n\n")
                append("${ChartLegend.MATRIX}\n\n")
                append("| 策略族 | " + metrics.matrixIntents.joinToString(" | ") { it.label } + " |\n")
                append("|---" + "|---".repeat(metrics.matrixIntents.size) + "|\n")
                metrics.matrixStrategies.forEach { st ->
                    val byIntent = metrics.cellsOf(st).associateBy { it.intent }
                    append("| ${st.label} | ")
                    append(metrics.matrixIntents.joinToString(" | ") { i ->
                        byIntent[i]?.asr?.let { "%.0f%%".format(it * 100) } ?: "--"
                    })
                    append(" |\n")
                }
                append("\n")
            }
        }

        // ── 加固建议（方案 §3.5）：取证闭环的最后一问「怎么修」──
        // 与报告页 / HTML 导出同源（HardeningAdvisor），归因判据走 DefenseAttribution ——
        // 与上面「安全指标」的失效层是**同一份实现**，避免两处对同一条用例给出相反归因。
        // ⚠️ 它刻意**不是** LLM 现场生成的：建议必须可复现、可证伪，否则取证报告没有可信度。
        val hardening = HardeningAdvisor.advise(sections.fails)
        if (!hardening.isEmpty) {
            append("## 加固建议\n\n")
            append("按「攻击手法 × 先失效的护栏层」聚合，**按中招条数降序** —— 第一条就是该先修的。\n\n")
            hardening.items.forEach { item ->
                append("### ${item.strategy.label}（${item.cases} 条中招")
                append(item.layer?.let { " · 先失效：${it.label}" } ?: " · 定位不出失效层")
                append("）\n\n")
                append("${item.action}\n\n")
                item.snippet?.let { append("可直接粘进系统提示：\n\n> $it\n\n") }
            }
            if (hardening.unattributed > 0) {
                append("> 另有 ${hardening.unattributed} 条失守没能定位到失效层（没有逐轮记录），")
                append("上述建议只到策略级；跑多轮（配辅助模型 + 打开递归对抗）才能拿到分层归因。\n\n")
            }
        }

        // ── 基线对比章节（选了基线才有）──
        if (diff != null) {
            append("## 基线对比\n\n")
            append("基线时间：${formatTime(diff.base.timestamp)}  \n")
            val deltaTxt = when {
                diff.scoreDelta == null -> "无法比较（有一侧无有效样本）"
                diff.scoreDelta > 0f -> "↑ %.1f".format(diff.scoreDelta)
                diff.scoreDelta < 0f -> "↓ %.1f".format(-diff.scoreDelta)
                else -> "持平"
            }
            append("总分：%.1f → %.1f（%s）\n\n".format(diff.baseTotal, diff.curTotal, deltaTxt))
            append("| 维度 | 基线 | 本轮 | 变化 |\n|---|---|---|---|\n")
            diff.dimChanges.forEach { dc ->
                val bd = dc.delta
                val bdText = when {
                    bd == null -> "--"
                    bd > 0f -> "+%.1f".format(bd)
                    bd < 0f -> "%.1f".format(bd)
                    else -> "0.0"
                }
                append("| ${dc.dimension.label} | ${dc.baseScore?.let{"%.1f".format(it)} ?: "--"} | " +
                        "${dc.curScore?.let{"%.1f".format(it)} ?: "--"} | $bdText |\n")
            }
            append("\n")
            if (diff.regressions.isNotEmpty()) {
                append("### 新增失守 ${diff.regressions.size} 条\n\n")
                diff.regressions.forEach { c ->
                    append("- [${c.result.testCase.dimension.label}] ${c.result.testCase.title}")
                    append("（基线 ${ReportDiff.verdictText(c.from)} → 本轮 失守）\n")
                }
                append("\n")
            }
            if (diff.fixed.isNotEmpty()) {
                append("### 已修复 ${diff.fixed.size} 条\n\n")
                diff.fixed.forEach { c ->
                    append("- [${c.result.testCase.dimension.label}] ${c.result.testCase.title}")
                    append("（基线 失守 → 本轮 通过）\n")
                }
                append("\n")
            }
            if (diff.uncomparable.isNotEmpty()) {
                append("### 无法比较 ${diff.uncomparable.size} 条（含不可评估/待复核，不计入方向）\n\n")
                diff.uncomparable.forEach { c ->
                    append("- [${c.result.testCase.dimension.label}] ${c.result.testCase.title}")
                    append("（基线 ${ReportDiff.verdictText(c.from)} → 本轮 ${ReportDiff.verdictText(c.to)}）\n")
                }
                append("\n")
            }
        }

        // ── 攻击演进：多步用例的逐步拒绝情况 ──
        // 独立成章，**不放进**「失守明细」：失守 0 条的模型同样有多步攻击记录，
        // 挂在失守明细里就会随失守条数一起消失（2026-10-12 真机验证时抓到的导出 bug：
        // 同一条 fixture 报告 App 页看得到时间轴、导出的 HTML 里却没有）。
        // Markdown 画不了 SVG，用纯文本版：每步的轮次 + 该步回答里有没有拒绝话术。
        // ⚠️ 标签写「有拒绝 / 无拒绝」而**不写「破防」**：它只是攻击循环的启发式，
        // 后端每用例只判最后一轮 —— 写成「破防」会与同页的 verdict / 失守明细互相打架。
        val trailCases = sections.trailCases
        if (trailCases.isNotEmpty()) {
            append("## 攻击演进\n\n")
            append("${ChartLegend.TIMELINE}\n\n")
            trailCases.forEach { r ->
                append("### ${r.testCase.dimension.label} · ${r.testCase.title}")
                append("（${r.attackRounds.size} 步 · ${r.judgeResult.verdict}）\n\n")
                append(r.attackRounds.joinToString(" → ") {
                    "第${it.round}步" + if (it.refused) "有拒绝" else "**无拒绝**"
                } + "\n\n")
            }
        }

        // ── 多轮侵蚀：回答「第几步被磨破」──
        // 独立成章的理由同「攻击演进」：挂在失守明细里会在失守 0 条时整块消失，
        // 而「在步数上限内没被攻破」恰恰是最该看见的结论。取数只走 ErosionAnalysis（报告页同源）。
        // ⚠️ 2026-10-13 起**无条件成章**：以前 `multiStepTotal > 0` 才写，
        // 一次真机运行（本机跑、未开递归对抗）里整块消失 —— 而「没采集多步证据」
        // 本身就是要写进审计报告的结论，不能靠「不写」来表达。文案与报告页同源。
        run {
            append("## 多轮侵蚀\n\n")
            append("${ChartLegend.EROSION}\n\n")
            append("${erosion.headline}\n\n")
            if (erosion.exclusionNote.isNotEmpty()) append("${erosion.exclusionNote}\n\n")
            if (erosion.disagreementNote.isNotEmpty()) append("${erosion.disagreementNote}\n\n")
            if (erosion.unresolvedNote.isNotEmpty()) append("${erosion.unresolvedNote}\n\n")
            if (erosion.evaluated == 0) append("${ChartLegend.EROSION_EMPTY}\n\n")
            if (erosion.evaluated > 0) {
                append("| 策略族 | 可评估多步 | 判定失守 | 定位出破防步 | 破防步数 P50 | P90 | ≤${ErosionAnalysis.BROKE_EARLY_TURNS} 步失守 |\n")
                append("|---|---|---|---|---|---|---|\n")
                erosion.curves.forEach { c ->
                    append("| ${c.strategy.label} | ${c.total} | ${c.failed} | ${c.located} | ")
                    append("${fmtOne(c.p50)} | ${fmtOne(c.p90)} | ${c.brokeEarly} |\n")
                }
                append("\n")
                // Markdown 画不了 SVG，用纯文本把「爬升快不快」表达出来（与报告页同一组数）。
                append("累计失守率：\n\n")
                append(erosion.overallCumulative.joinToString(" ") { p ->
                    "第${p.step}步 ${"%.0f%%".format(p.rate * 100)}"
                } + "\n\n")
            }
        }

        append("## 失守明细\n\n")
        val fails = sections.fails
        if (fails.isEmpty()) {
            append(if (noEvidence) "本轮无有效样本，未产生失守结论。\n\n" else "未发现失守用例。\n\n")
        }
        fails.forEach { r ->
            append("### ${r.testCase.dimension.label} · ${r.testCase.title}\n\n")
            append("- 攻击输入：${r.testCase.prompt}\n")
            append("- 模型回答：${r.response}\n")
            append("- 判定依据：${r.judgeResult.evidence.joinToString("；")}\n")
            if (r.judgeResult.needsReview) append("- 需人工复核\n")
            if (r.stabilityMeasured && r.stability < 0.8f) {
                append("- ⚠️ 判定不稳定：多次采样一致性仅 %.0f%%\n".format(r.stability * 100))
            }
            if (r.judgeResult.disagreements.isNotEmpty()) append("- 分歧记录：${r.judgeResult.disagreements.joinToString("；")}\n")
            if (r.attackTrail.isNotEmpty()) {
                append("- 攻击轨迹：\n")
                r.attackTrail.forEach { append("  - $it\n") }
            }
            append("\n")
        }

        // ── 待人工复核：判过但证据链里有未消解的分歧 ──
        // 只列"判成 PASS、非失守、非不可评估"的那批，避免同一条在文档里出现两遍：
        //  · 失守用例上面已经逐条列过；
        //  · SKIPPED / INCONCLUSIVE 一律 needsReview（INCONCLUSIVE 必真），
        //    下面「不可评估」章节已经列过，不排除就会重复出现。
        val reviewOnly = sections.reviewOnly
        if (reviewOnly.isNotEmpty()) {
            append("## 待人工复核 ${reviewOnly.size} 条\n\n")
            append("这些用例判定为通过，但证据链里存在未消解的分歧（规则层与 LLM 结论相反，")
            append("或三角色投票分裂）。**不影响总分**，但可靠性不如一致通过，值得扫一眼。\n\n")
            reviewOnly.forEach { r ->
                val stab = if (r.stabilityMeasured && r.stability < 0.8f) {
                    "，采样一致性 %.0f%%".format(r.stability * 100)
                } else ""
                append("- ${r.testCase.dimension.label} · ${r.testCase.title}")
                append("（${ReportDiff.verdictText(r.judgeResult.verdict)}$stab）\n")
            }
            append("\n")
        }

        // ── 不可评估：调用失败（SKIPPED）+ 判定拿不准（INCONCLUSIVE），两者都剔出分母 ──
        // 必须分开列：成因完全不同（一个是网络/限流，一个是裁判没形成多数），
        // 混在一起用户不知道该去查网络还是查模型。
        val skipped = sections.skipped
        val inconclusive = sections.inconclusive
        if (skipped.isNotEmpty() || inconclusive.isNotEmpty()) {
            append("## 不可评估 ${skipped.size + inconclusive.size} 条\n\n")
            append("这些用例未计入失守率与总分。它们不代表模型有风险，也不代表模型安全。\n\n")
            if (skipped.isNotEmpty()) {
                append("**调用失败 ${skipped.size} 条**（接口异常/限流/超时，与模型安全性无关）：\n\n")
                skipped.forEach { r ->
                    append("- ${r.testCase.dimension.label} · ${r.testCase.title}：")
                    append("${r.judgeResult.evidence.firstOrNull() ?: "接口调用失败"}\n")
                }
                append("\n")
            }
            if (inconclusive.isNotEmpty()) {
                append("**判定拿不准 ${inconclusive.size} 条**（三角色裁判未形成多数结论）：\n\n")
                inconclusive.forEach { r ->
                    append("- ${r.testCase.dimension.label} · ${r.testCase.title}：")
                    append("${r.judgeResult.disagreements.joinToString("；").ifBlank { "裁判未达成一致" }}\n")
                }
            }
        }
    }

    fun toHtml(report: Report, diff: ReportDiff.Result? = null): String = buildString {
        val noEvidence = report.riskLevel == RiskLevel.UNKNOWN
        val sections = ReportSections.of(report)
        // 风险色统一走 ChartPalette。这里原本是**第三份**等级→颜色 when，
        // 且 CRITICAL 写成 `#E24B4B`、而 ChartPalette.FAIL 是 `#E24B4A` —— 已经差了一个字符，
        // 正是「同一判据存三份」必然走岔的证据。导出件是白底，所以取浅底档。
        val riskColor = ChartPalette.toHex(ChartPalette.riskColor(report.riskLevel))
        append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>AISecLab 测评报告</title><style>")
        append("body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;max-width:860px;margin:0 auto;padding:24px;color:#1a1a1a;line-height:1.6}")
        append("h1{font-size:24px;border-bottom:2px solid #eee;padding-bottom:12px}")
        append("h2{font-size:18px;margin-top:28px}")
        append(".score{font-size:48px;font-weight:600;color:").append(riskColor).append("}")
        append(".cards{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}")
        append(".card{flex:1;min-width:150px;border:1px solid #e5e5e5;border-radius:12px;padding:14px}")
        append(".card .d{font-size:13px;color:#666}.card .s{font-size:26px;font-weight:600}")
        append(".meta{color:#555;font-size:14px}")
        append(".finding{border-left:3px solid ").append(riskColor).append(";background:#fafafa;padding:10px 14px;margin:10px 0;border-radius:4px}")
        append(".skipped{border-left:3px solid #ccc;background:#fafafa;padding:10px 14px;margin:10px 0;border-radius:4px;color:#555}")
        // 加固建议用绿色左边框：它是「该做什么」的行动项，与红色的失守告警在视觉上分开。
        append(".hardening{border-left:3px solid #1D9E75;background:#f5fbf8;padding:10px 14px;margin:10px 0;border-radius:4px}")
        append("pre{background:#f5f5f5;padding:10px;border-radius:6px;overflow-x:auto;white-space:pre-wrap}")
        append(".tag{display:inline-block;padding:2px 8px;border-radius:4px;background:").append(riskColor).append(";color:#fff;font-size:12px;margin-left:8px}")
        append("</style></head><body>")

        append("<h1>AISecLab 测评报告 <span class=\"tag\">${riskText(report.riskLevel)}</span></h1>")
        append("<div class=\"meta\">模型：${modelLabel(report.modelProfile)} · 时间：${formatTime(report.timestamp)}</div>")
        val scoreText = if (noEvidence) "--" else "%.1f".format(report.overallScore)
        append("<div class=\"score\">$scoreText<span style=\"font-size:16px;color:#999\"> / 100</span></div>")

        append("<div class=\"cards\">")
        report.dimensions.values.sortedBy { it.dimension.ordinal }.forEach { d ->
            val c = if (d.total == 0) "#8A8A8A" else if (d.score >= 80) "#1D9E75" else if (d.score >= 60) "#BA7517" else "#E24B4B"
            val s = if (d.total == 0) "--" else "%.1f".format(d.score)
            val sub = if (d.total == 0) "未评估（全部不可评估）"
            else "${d.passed}/${d.total} 通过 · 失守 ${"%.0f%%".format(d.failRate * 100)}"
            append("<div class=\"card\"><div class=\"d\">${d.dimension.label}</div>")
            append("<div class=\"s\" style=\"color:$c\">$s</div>")
            append("<div class=\"d\">$sub</div></div>")
        }
        append("</div>")

        // 维度结论告警（失守用例 / ⚠️判定不稳定 / ℹ️稳定性未测量）—— 与 markdown 导出同源。
        val notes = report.dimensions.values.sortedBy { it.dimension.ordinal }
            .filter { it.notableFindings.isNotEmpty() }
        if (notes.isNotEmpty()) {
            append("<h2>维度结论</h2>")
            notes.forEach { d ->
                append("<b>${d.dimension.label}</b><ul>")
                d.notableFindings.forEach { append("<li>${escapeHtml(it)}</li>") }
                append("</ul>")
            }
        }

        append("<h2>摘要</h2><p>${report.summary}</p>")

        // 本轮账单：文案与报告页 / Markdown 导出同源（Report.tokenSpendLine）
        append("<h2>本次消耗</h2><p>${escapeHtml(report.tokenSpendLine())}</p>")

        // ── 安全指标（方案 §5）：取数与文案同 Markdown 侧，全部来自 SafetyMetrics ──
        val erosion = ErosionAnalysis.of(report.results)
        val metrics = SafetyMetrics.of(report.results, erosion)
        if (metrics.evaluated + metrics.unassessed > 0) {
            append("<h2>安全指标</h2>")
            append("<table style=\"width:100%;border-collapse:collapse;margin:8px 0\">")
            append("<tr style=\"background:#f5f5f5\">")
            listOf("指标", "值", "口径").forEach {
                append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">")
                    .append(escapeHtml(it)).append("</th>")
            }
            append("</tr>")
            metrics.rows().forEach { m ->
                append("<tr>")
                listOf(m.label, m.value, m.hint).forEachIndexed { idx, txt ->
                    val bold = if (idx == 1) "font-weight:600;" else ""
                    append("<td style=\"padding:6px;border:1px solid #e5e5e5;$bold\">")
                        .append(escapeHtml(txt)).append("</td>")
                }
                append("</tr>")
            }
            append("</table>")
            if (metrics.matrixUsable) {
                append("<h3>策略 × 意图</h3>")
                append("<p class=\"meta\">").append(escapeHtml(ChartLegend.MATRIX)).append("</p>")
                append("<div style=\"overflow-x:auto\"><table style=\"border-collapse:collapse;margin:8px 0\">")
                append("<tr style=\"background:#f5f5f5\"><th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">策略族</th>")
                metrics.matrixIntents.forEach {
                    append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">")
                        .append(escapeHtml(it.label)).append("</th>")
                }
                append("</tr>")
                metrics.matrixStrategies.forEach { st ->
                    val byIntent = metrics.cellsOf(st).associateBy { it.intent }
                    append("<tr><td style=\"padding:6px;border:1px solid #e5e5e5\">")
                        .append(escapeHtml(st.label)).append("</td>")
                    metrics.matrixIntents.forEach { i ->
                        val cell = byIntent[i]
                        val txt = cell?.asr?.let { "%.0f%%".format(it * 100) } ?: "--"
                        // 底色把 ASR 高低也画出来（导出件没有交互，纯数字表不好扫）。
                        // 用 asrColor 而不是 colorFor：0% 是好事，得是绿的。
                        val asr = cell?.asr
                        val bg = asr?.let { ChartPalette.toHex(ChartPalette.asrColor(it)) }
                        val fg = if (asr != null && ChartPalette.asrNeedsLightText(asr)) "color:#fff;" else ""
                        val style = if (bg != null) "background:$bg;$fg" else ""
                        append("<td style=\"padding:6px;border:1px solid #e5e5e5;$style\">")
                            .append(escapeHtml(txt)).append("</td>")
                    }
                    append("</tr>")
                }
                append("</table></div>")
            }
        }

        // ── 加固建议（方案 §3.5）：与报告页 / Markdown 同源（HardeningAdvisor）──
        val hardening = HardeningAdvisor.advise(sections.fails)
        if (!hardening.isEmpty) {
            append("<h2>加固建议</h2>")
            append("<p class=\"meta\">按「攻击手法 × 先失效的护栏层」聚合，按中招条数降序 —— 第一条就是该先修的。</p>")
            hardening.items.forEach { item ->
                append("<div class=\"hardening\"><b>${escapeHtml(item.strategy.label)}</b>")
                append("<span class=\"meta\" style=\"margin-left:8px\">${item.cases} 条中招 · ")
                append(escapeHtml(item.layer?.let { "先失效：${it.label}" } ?: "先失效层不可归因"))
                append("</span>")
                append("<div style=\"margin-top:6px\">${escapeHtml(item.action)}</div>")
                item.snippet?.let {
                    append("<div style=\"margin-top:6px\"><span class=\"meta\">可直接粘进系统提示：</span>")
                    append("<pre>${escapeHtml(it)}</pre></div>")
                }
                append("</div>")
            }
            if (hardening.unattributed > 0) {
                append("<p class=\"meta\">另有 ${hardening.unattributed} 条失守没能定位到失效层（没有逐轮记录），")
                append("上述建议只到策略级。</p>")
            }
        }

        // ── 图表：内联 SVG，不依赖 JS / CDN ──
        // 导出的报告要能在**没网、没脚本**的环境打开（审计存档、微信/邮件预览）。
        // 配色与图例都取自 core/report 里那一份，与报告页同源，不会走岔。
        val heatSvg = ChartSvg.heatmap(report.results)
        if (heatSvg.isNotEmpty()) {
            append("<h2>用例热力图</h2><div style=\"overflow-x:auto\">").append(heatSvg).append("</div>")
            append("<p class=\"meta\">").append(escapeHtml(ChartLegend.HEATMAP)).append("</p>")
        }
        val partitionSvg = ChartSvg.bars(
            listOf(
                Triple("失守", sections.fails.size, ChartPalette.FAIL),
                Triple("待人工复核", sections.reviewOnly.size, ChartPalette.INCONCLUSIVE),
                Triple("通过", sections.passPlain.size, ChartPalette.PASS),
                Triple("不可评估·调用失败", sections.skipped.size, ChartPalette.SKIPPED),
                Triple("不可评估·判定拿不准", sections.inconclusive.size, ChartPalette.INCONCLUSIVE)
            )
        )
        if (partitionSvg.isNotEmpty()) {
            append("<h2>分区分布</h2><div style=\"overflow-x:auto\">").append(partitionSvg).append("</div>")
            append("<p class=\"meta\">").append(escapeHtml(ChartLegend.BARS)).append("</p>")
        }
        val levelSvg = ChartSvg.bars(
            listOf(
                Triple("L1 正则", report.results.count { it.judgeResult.level == JudgeLevel.L1_PATTERN }, ChartPalette.PASS),
                Triple("L2 语义", report.results.count { it.judgeResult.level == JudgeLevel.L2_SEMANTIC }, ChartPalette.PASS),
                Triple("L3 结构", report.results.count { it.judgeResult.level == JudgeLevel.L3_STRUCTURAL }, ChartPalette.PASS),
                Triple("LLM 裁判", report.results.count { it.judgeResult.level == JudgeLevel.LLM }, ChartPalette.PASS)
            )
        )
        if (levelSvg.isNotEmpty()) {
            append("<h2>判定层级分布</h2><div style=\"overflow-x:auto\">").append(levelSvg).append("</div>")
        }

        // ── 基线对比章节（选了基线才有）──
        if (diff != null) {
            append("<h2>基线对比</h2>")
            append("<div class=\"meta\">基线时间：${formatTime(diff.base.timestamp)}</div>")
            val deltaTxt = when {
                diff.scoreDelta == null -> "无法比较（有一侧无有效样本）"
                diff.scoreDelta > 0f -> "↑ %.1f".format(diff.scoreDelta)
                diff.scoreDelta < 0f -> "↓ %.1f".format(-diff.scoreDelta)
                else -> "持平"
            }
            append("<p>总分：%.1f → %.1f（%s）</p>".format(diff.baseTotal, diff.curTotal, deltaTxt))
            append("<table style=\"width:100%%;border-collapse:collapse;margin:8px 0\">")
            append("<tr style=\"background:#f5f5f5\"><th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">维度</th>")
            append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">基线</th>")
            append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">本轮</th>")
            append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">变化</th></tr>")
            diff.dimChanges.forEach { dc ->
                val bd = dc.delta
                val bdText = when {
                    bd == null -> "--"
                    bd > 0f -> "+%.1f".format(bd)
                    bd < 0f -> "%.1f".format(bd)
                    else -> "0.0"
                }
                append("<tr><td style=\"padding:6px;border:1px solid #e5e5e5\">${dc.dimension.label}</td>")
                append("<td style=\"padding:6px;border:1px solid #e5e5e5\">${dc.baseScore?.let{"%.1f".format(it)} ?: "--"}</td>")
                append("<td style=\"padding:6px;border:1px solid #e5e5e5\">${dc.curScore?.let{"%.1f".format(it)} ?: "--"}</td>")
                append("<td style=\"padding:6px;border:1px solid #e5e5e5\">$bdText</td></tr>")
            }
            append("</table>")
            if (diff.regressions.isNotEmpty()) {
                append("<h3 style=\"color:#E24B4B\">新增失守 ${diff.regressions.size} 条</h3><ul>")
                diff.regressions.forEach { c ->
                    append("<li>[${c.result.testCase.dimension.label}] ${escapeHtml(c.result.testCase.title)}" +
                            "（基线 ${ReportDiff.verdictText(c.from)} → 本轮 失守）</li>")
                }
                append("</ul>")
            }
            if (diff.fixed.isNotEmpty()) {
                append("<h3 style=\"color:#1D9E75\">已修复 ${diff.fixed.size} 条</h3><ul>")
                diff.fixed.forEach { c ->
                    append("<li>[${c.result.testCase.dimension.label}] ${escapeHtml(c.result.testCase.title)}" +
                            "（基线 失守 → 本轮 通过）</li>")
                }
                append("</ul>")
            }
            if (diff.uncomparable.isNotEmpty()) {
                append("<h3 style=\"color:#8A8A8A\">无法比较 ${diff.uncomparable.size} 条（含不可评估/待复核）</h3><ul>")
                diff.uncomparable.forEach { c ->
                    append("<li>[${c.result.testCase.dimension.label}] ${escapeHtml(c.result.testCase.title)}" +
                            "（基线 ${ReportDiff.verdictText(c.from)} → 本轮 ${ReportDiff.verdictText(c.to)}）</li>")
                }
                append("</ul>")
            }
        }

        // ── 攻击演进：独立章节，理由同 Markdown 侧（挂进失守明细会在失守 0 条时整块消失）──
        val trailCases = sections.trailCases
        if (trailCases.isNotEmpty()) {
            append("<h2>攻击演进</h2>")
            append("<p class=\"meta\">").append(escapeHtml(ChartLegend.TIMELINE)).append("</p>")
            trailCases.forEach { r ->
                append("<div class=\"finding\"><b>${r.testCase.dimension.label} · ")
                    .append(escapeHtml(r.testCase.title)).append("</b>")
                append("<span class=\"meta\" style=\"margin-left:8px\">")
                    .append(r.attackRounds.size).append(" 步 · ").append(r.judgeResult.verdict).append("</span>")
                append("<div style=\"overflow-x:auto;margin-top:8px\">")
                    .append(ChartSvg.timeline(r.attackRounds)).append("</div></div>")
            }
        }

        // ── 多轮侵蚀：独立章节、**无条件成章**，理由同 Markdown 侧 ──
        run {
            append("<h2>多轮侵蚀</h2>")
            append("<p class=\"meta\">").append(escapeHtml(ChartLegend.EROSION)).append("</p>")
            append("<p>${escapeHtml(erosion.headline)}</p>")
            if (erosion.exclusionNote.isNotEmpty()) {
                append("<p class=\"meta\">${escapeHtml(erosion.exclusionNote)}</p>")
            }
            if (erosion.disagreementNote.isNotEmpty()) {
                append("<p class=\"meta\">${escapeHtml(erosion.disagreementNote)}</p>")
            }
            if (erosion.unresolvedNote.isNotEmpty()) {
                append("<p class=\"meta\">${escapeHtml(erosion.unresolvedNote)}</p>")
            }
            if (erosion.evaluated == 0) {
                append("<p class=\"meta\">${escapeHtml(ChartLegend.EROSION_EMPTY)}</p>")
            }
            if (erosion.evaluated > 0) {
                val erosionSvg = ChartSvg.erosionCumulative(erosion.overallCumulative)
                if (erosionSvg.isNotEmpty()) {
                    append("<div style=\"overflow-x:auto\">").append(erosionSvg).append("</div>")
                }
                append("<table style=\"width:100%;border-collapse:collapse;margin:8px 0\">")
                append("<tr style=\"background:#f5f5f5\">")
                listOf(
                    "策略族", "可评估多步", "判定失守", "定位出破防步", "破防步数 P50", "P90",
                    "≤${ErosionAnalysis.BROKE_EARLY_TURNS} 步失守"
                ).forEach {
                    append("<th style=\"text-align:left;padding:6px;border:1px solid #e5e5e5\">")
                        .append(escapeHtml(it)).append("</th>")
                }
                append("</tr>")
                erosion.curves.forEach { c ->
                    append("<tr>")
                    listOf(
                        c.strategy.label, "${c.total}", "${c.failed}", "${c.located}",
                        fmtOne(c.p50), fmtOne(c.p90), "${c.brokeEarly}"
                    ).forEach {
                        append("<td style=\"padding:6px;border:1px solid #e5e5e5\">")
                            .append(escapeHtml(it)).append("</td>")
                    }
                    append("</tr>")
                }
                append("</table>")
            }
        }

        append("<h2>失守明细</h2>")
        val fails = sections.fails
        if (fails.isEmpty()) {
            append("<p>${if (noEvidence) "本轮无有效样本，未产生失守结论。" else "未发现失守用例。"}</p>")
        }
        fails.forEach { r ->
            append("<div class=\"finding\"><b>${r.testCase.dimension.label} · ${r.testCase.title}</b><br>")
            append("攻击输入：<pre>${escapeHtml(r.testCase.prompt)}</pre>")
            append("模型回答：<pre>${escapeHtml(r.response)}</pre>")
            append("判定依据：${r.judgeResult.evidence.joinToString("；")}")
            if (r.judgeResult.needsReview) append("<br><b style=\"color:#D85A30\">需人工复核</b>")
            if (r.stabilityMeasured && r.stability < 0.8f) {
                append("<br><b style=\"color:#D85A30\">⚠️ 判定不稳定：多次采样一致性仅 %.0f%%</b>".format(r.stability * 100))
            }
            if (r.judgeResult.disagreements.isNotEmpty()) append("<br>分歧记录：${r.judgeResult.disagreements.joinToString("；")}")
            if (r.attackTrail.isNotEmpty()) {
                append("<br>攻击轨迹：<pre>${escapeHtml(r.attackTrail.joinToString("\n"))}</pre>")
            }
            append("</div>")
        }

        // ── 待人工复核：判过但证据链里有未消解的分歧（非失守、非不可评估的那部分）──
        val reviewOnly = sections.reviewOnly
        if (reviewOnly.isNotEmpty()) {
            append("<h2>待人工复核 ${reviewOnly.size} 条</h2>")
            append("<p>这些用例判定为通过，但证据链里存在未消解的分歧（规则层与 LLM 结论相反，")
            append("或三角色投票分裂）。<b>不影响总分</b>，但可靠性不如一致通过，值得扫一眼。</p><ul>")
            reviewOnly.forEach { r ->
                val stab = if (r.stabilityMeasured && r.stability < 0.8f) {
                    "，采样一致性 ${"%.0f%%".format(r.stability * 100)}"
                } else ""
                append("<li>[${r.testCase.dimension.label}] ${escapeHtml(r.testCase.title)}")
                append("（${ReportDiff.verdictText(r.judgeResult.verdict)}$stab）</li>")
            }
            append("</ul>")
        }

        // ── 不可评估：调用失败 + 判定拿不准，分开列（成因不同，修法也不同）──
        val skipped = sections.skipped
        val inconclusive = sections.inconclusive
        if (skipped.isNotEmpty() || inconclusive.isNotEmpty()) {
            append("<h2>不可评估 ${skipped.size + inconclusive.size} 条</h2>")
            append("<p>这些用例未计入失守率与总分。它们不代表模型有风险，也不代表模型安全。</p>")
            if (skipped.isNotEmpty()) {
                append("<h3>调用失败 ${skipped.size} 条</h3>")
                append("<p>接口异常/限流/超时，与模型安全性无关。</p>")
                skipped.forEach { r ->
                    append("<div class=\"skipped\"><b>${r.testCase.dimension.label} · ${r.testCase.title}</b><br>")
                    append("${escapeHtml(r.judgeResult.evidence.firstOrNull() ?: "接口调用失败")}</div>")
                }
            }
            if (inconclusive.isNotEmpty()) {
                append("<h3>判定拿不准 ${inconclusive.size} 条</h3>")
                append("<p>三角色裁判未形成多数结论。</p>")
                inconclusive.forEach { r ->
                    append("<div class=\"skipped\"><b>${r.testCase.dimension.label} · ${r.testCase.title}</b><br>")
                    append("${escapeHtml(r.judgeResult.disagreements.joinToString("；").ifBlank { "裁判未达成一致" })}</div>")
                }
            }
        }
        append("</body></html>")
    }

    /** 分位数显示：null = 没有可评估的破防样本 → `--`（**不能显示 0.0**，那会读成"第 0 步就破了"）。 */
    private fun fmtOne(v: Float?): String = v?.let { "%.1f".format(it) } ?: "--"

    private fun riskText(level: RiskLevel): String = when (level) {
        RiskLevel.LOW -> "低风险"
        RiskLevel.MEDIUM -> "中风险"
        RiskLevel.HIGH -> "高风险"
        RiskLevel.CRITICAL -> "严重风险"
        RiskLevel.UNKNOWN -> "无法评级"
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
