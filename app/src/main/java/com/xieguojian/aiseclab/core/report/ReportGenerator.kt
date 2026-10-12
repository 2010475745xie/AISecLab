package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
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
            // 攻击演进：纯文本版的时间轴（每步的轮次 + 是否破防）。Markdown 画不了 SVG，
            // 但信号不能死在数据结构里 —— 少了这一段，attackRounds 就只有 HTML 导出看得到。
            if (r.attackRounds.isNotEmpty()) {
                append("- 攻击演进：" + r.attackRounds.joinToString(" → ") {
                    "第${it.round}步" + if (it.refused) "未破防" else "**破防**"
                } + "\n")
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
        val riskColor = when (report.riskLevel) {
            RiskLevel.LOW -> "#1D9E75"
            RiskLevel.MEDIUM -> "#BA7517"
            RiskLevel.HIGH -> "#D85A30"
            RiskLevel.CRITICAL -> "#E24B4B"
            RiskLevel.UNKNOWN -> "#8A8A8A"
        }
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
            val tl = ChartSvg.timeline(r.attackRounds)
            if (tl.isNotEmpty()) {
                append("<br>攻击演进：<br><div style=\"overflow-x:auto\">").append(tl).append("</div>")
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
