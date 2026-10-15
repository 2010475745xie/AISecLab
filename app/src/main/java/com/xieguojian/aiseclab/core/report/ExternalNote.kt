package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.Report

/**
 * 报告里**「外部对照」那一行**的取数与文案 —— 全项目唯一一份。
 *
 * ## 为什么报告要单独有一段
 *
 * 本地判定链的自测准确率是**集内**数字：自编集不等于外部效度（方案 2.1 的已知局限）。
 * 要让读者知道"这份结论有没有第二方印证过"，就得把对照情况**明说**：
 * 没接入就说没接入，别让读者以为"没有这一节 = 不需要"。
 *
 * ## 三种状态必须分得开（这正是本文件存在的理由）
 *
 * 1. **没接入**（[Report.externalCheck] 为 null）→ 说清楚"没有第二方对照"，
 *    并提醒跨报告比较的前提；它**不是**"对照失败"，也不显示 0%。
 * 2. **接入了但一条都没拿到**（`compared == 0`）→ 说"这次对照没做成"，
 *    并给出不可用条数 —— 与"完全不同意"是两回事（同「没采集 ≠ 很差」）。
 * 3. **真对照过** → 给一致率、分歧条数与不可用条数，并重申纪律：外部只作对照，不回写判定。
 *
 * ## 文案唯一源
 *
 * 报告页 / Markdown 导出 / HTML 导出**三处共用这一句**（做法同 [reproLine] 与
 * [cotNote]）—— 同一句话抄三遍必然走岔，本项目已经出过两次。
 */
internal fun Report.externalNote(): String {
    val c = externalCheck
        ?: return "未接入外部裁判 —— 本报告的判定全部来自本地判定链（规则层 / LLM 裁判），" +
            "没有第二方对照。自测集的准确率只是集内数字，不等于外部效度；" +
            "跨报告比较时，请只比同一判定链下产出的结论。"

    if (c.compared == 0) {
        // 对照没做成 —— 必须说清这是"没测到"，不是"不同意"
        return "已接入外部裁判（${c.providerLabel}），但本轮没有任何一条拿到可用结果" +
            "（${c.unavailable} 条不可用）—— 这次对照没有做成，不代表外部与本地判定不一致。" +
            "常见原因是调用失败 / 超时 / 配额用尽，可查运行日志。"
    }

    val rate = c.rate?.let { "%.0f%%".format(it * 100) } ?: "--"
    val disagree = if (c.disagreementIds.isEmpty()) {
        "分歧 0 条。"
    } else {
        "分歧 ${c.disagreementIds.size} 条（${c.disagreementIds.take(5).joinToString(", ")}" +
            if (c.disagreementIds.size > 5) " 等）" else "）。"
    }
    val unavailable = if (c.unavailable > 0) "另有 ${c.unavailable} 条外部未给出结果（调用失败 / 未判）。" else ""

    return "外部对照（${c.providerLabel}）：在 ${c.compared} 条上与本地判定一致 ${c.agreed} 条（$rate）；" +
        disagree + unavailable +
        "外部裁判只作对照 —— 它不回写判定，本报告的结论仍只由本地判定链产生。"
}
