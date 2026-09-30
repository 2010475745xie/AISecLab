package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.Verdict
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

    fun toMarkdown(report: Report): String = buildString {
        val noEvidence = report.riskLevel == RiskLevel.UNKNOWN
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
        append("\n## 摘要\n\n${report.summary}\n\n")

        append("## 失守明细\n\n")
        val fails = report.results.filter { it.judgeResult.verdict == Verdict.FAIL }
        if (fails.isEmpty()) {
            append(if (noEvidence) "本轮无有效样本，未产生失守结论。\n\n" else "未发现失守用例。\n\n")
        }
        fails.forEach { r ->
            append("### ${r.testCase.dimension.label} · ${r.testCase.title}\n\n")
            append("- 攻击输入：${r.testCase.prompt}\n")
            append("- 模型回答：${r.response}\n")
            append("- 判定依据：${r.judgeResult.evidence.joinToString("；")}\n")
            if (r.judgeResult.needsReview) append("- 需人工复核\n")
            if (r.judgeResult.disagreements.isNotEmpty()) append("- 分歧记录：${r.judgeResult.disagreements.joinToString("；")}\n")
            if (r.attackTrail.isNotEmpty()) {
                append("- 攻击轨迹：\n")
                r.attackTrail.forEach { append("  - $it\n") }
            }
            append("\n")
        }

        val skipped = report.results.filter { it.judgeResult.verdict == Verdict.SKIPPED }
        if (skipped.isNotEmpty()) {
            append("## 不可评估（接口调用失败 / 限流）\n\n")
            append("共 ${skipped.size} 条未能拿到有效回答，已从失守率与总分中剔除。")
            append("它们不代表模型有风险，也不代表模型安全。\n\n")
            skipped.forEach { r ->
                append("- ${r.testCase.dimension.label} · ${r.testCase.title}：")
                append("${r.judgeResult.evidence.firstOrNull() ?: "接口调用失败"}\n")
            }
        }
    }

    fun toHtml(report: Report): String = buildString {
        val noEvidence = report.riskLevel == RiskLevel.UNKNOWN
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
            val sub = if (d.total == 0) "未评估（全部调用失败）"
            else "${d.passed}/${d.total} 通过 · 失守 ${"%.0f%%".format(d.failRate * 100)}"
            append("<div class=\"card\"><div class=\"d\">${d.dimension.label}</div>")
            append("<div class=\"s\" style=\"color:$c\">$s</div>")
            append("<div class=\"d\">$sub</div></div>")
        }
        append("</div>")

        append("<h2>摘要</h2><p>${report.summary}</p>")

        append("<h2>失守明细</h2>")
        val fails = report.results.filter { it.judgeResult.verdict == Verdict.FAIL }
        if (fails.isEmpty()) {
            append("<p>${if (noEvidence) "本轮无有效样本，未产生失守结论。" else "未发现失守用例。"}</p>")
        }
        fails.forEach { r ->
            append("<div class=\"finding\"><b>${r.testCase.dimension.label} · ${r.testCase.title}</b><br>")
            append("攻击输入：<pre>${escapeHtml(r.testCase.prompt)}</pre>")
            append("模型回答：<pre>${escapeHtml(r.response)}</pre>")
            append("判定依据：${r.judgeResult.evidence.joinToString("；")}")
            if (r.judgeResult.needsReview) append("<br><b style=\"color:#D85A30\">需人工复核</b>")
            if (r.judgeResult.disagreements.isNotEmpty()) append("<br>分歧记录：${r.judgeResult.disagreements.joinToString("；")}")
            if (r.attackTrail.isNotEmpty()) {
                append("<br>攻击轨迹：<pre>${escapeHtml(r.attackTrail.joinToString("\n"))}</pre>")
            }
            append("</div>")
        }

        val skipped = report.results.filter { it.judgeResult.verdict == Verdict.SKIPPED }
        if (skipped.isNotEmpty()) {
            append("<h2>不可评估（接口调用失败 / 限流）</h2>")
            append("<p>共 ${skipped.size} 条未能拿到有效回答，已从失守率与总分中剔除。")
            append("它们不代表模型有风险，也不代表模型安全。</p>")
            skipped.forEach { r ->
                append("<div class=\"skipped\"><b>${r.testCase.dimension.label} · ${r.testCase.title}</b><br>")
                append("${escapeHtml(r.judgeResult.evidence.firstOrNull() ?: "接口调用失败")}</div>")
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
