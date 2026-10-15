package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.Report

/**
 * 报告里**「复现性」那一档**的取数与文案 —— 全项目唯一一份。
 *
 * ## 为什么报告要给"能不能复现"一个明确的档
 *
 * 一份取证报告最有价值的一句话是「**别人照着重跑，能不能得到同一份结论**」。
 * 本项目的判定链有两个天然的不可复现来源：
 *
 * 1. **裁判侧**：一旦有 LLM 裁判参与，判定本身受温度与上游抖动影响 ——
 *    同一份回答重跑，可能判出不同的 verdict。这不是 bug，是 LLM 裁判的固有属性；
 * 2. **被测侧**：目标模型本身是采样的。开了多次采样（`SAMPLES_PER_CASE > 1`）后
 *    "回答"这一层就在波动，稳定性（多数票占比）只**量化**了波动，不能消除它。
 *
 * 这两件事都不会让报告里的数字变形到一眼看出来 —— 所以必须**显式标档**，
 * 而不是让读者默认"数字就是事实"。借 heretic 的做法（`all_scorers_reproducible()`），
 * 但落成报告里**人读的一档**，不是内部布尔量。
 *
 * ## 档位是"最弱环节"，不是加权平均
 *
 * - [ReproGrade.DETERMINISTIC]：判定全部来自规则层**且**每例单样本 → 同一份回答必得同一份判定；
 * - [ReproGrade.SAMPLED]：判定来自规则层（确定），但目标侧多次采样 → 回答会波动；
 * - [ReproGrade.NON_DETERMINISTIC]：**有 LLM 裁判参与** → 判定自己就可能变。
 *
 * 取**最弱**的一档：只要有一处不可复现，整份报告就不该被当成"可逐字复现"。
 * 加权平均会把"3 条用了 LLM 裁判"稀释成"整体还挺确定"，那是自欺。
 *
 * ## 取数为什么是现算、不是报告里的字段
 *
 * 它完全由 `results[].judgeResult.level` 与 `results[].stabilityMeasured` 推出来，
 * 所以**不设字段**（同 [cotNote]）—— 设字段就多一份"谁负责填、旧报告怎么办"的契约。
 *
 * ## 文案唯一源
 *
 * 报告页 / Markdown 导出 / HTML 导出**三处共用这一句**（做法同 [Report.tokenSpendLine] 与
 * [cotNote]）—— 「同一句话抄三遍必然走岔」在本项目已经出过两次。
 */
enum class ReproGrade {
    /** 判定全来自规则层 + 单样本：同输入必得同判定。 */
    DETERMINISTIC,

    /** 判定来自规则层，但目标侧多次采样：回答层会波动。 */
    SAMPLED,

    /** 有 LLM 裁判参与：判定本身就可能变。 */
    NON_DETERMINISTIC
}

/** 本报告落在哪一档 —— 取判定链与采样两处的**最弱环节**。 */
internal fun Report.reproGrade(): ReproGrade {
    if (results.any { it.judgeResult.level == JudgeLevel.LLM }) return ReproGrade.NON_DETERMINISTIC
    if (results.any { it.stabilityMeasured }) return ReproGrade.SAMPLED
    return ReproGrade.DETERMINISTIC
}

/**
 * 「复现性」那一行的文案。三档各一句话，**把"为什么"也写进去** ——
 * 只说"不可复现"会让人以为是缺陷，说清来源（LLM 裁判 / 多次采样）才知道该怎么办。
 */
internal fun Report.reproLine(): String {
    if (results.isEmpty()) return "本轮没有回答记录，无法评估复现性。"

    return when (reproGrade()) {
        ReproGrade.DETERMINISTIC ->
            "确定性 —— 判定全部来自规则层、且每例单样本：同一份回答会得到同一份判定，" +
                "本报告可逐字复现（换台机器、换个时间跑也一样）。"

        ReproGrade.SAMPLED ->
            "采样可复现 —— 判定来自规则层（确定），但目标侧做了多次采样：" +
                "回答本身会波动，稳定性指标已按多数票量化这个波动。" +
                "同一批参数重跑，数字可能小幅变动，别把单次结果当成精确值。"

        ReproGrade.NON_DETERMINISTIC ->
            "不可复现 —— 本轮有 LLM 裁判参与，判定本身受温度与上游抖动影响：" +
                "同一输入重跑可能得到不同的判定。请优先看标了「需人工复核」的条目，" +
                "别把单次数字当定论；要可复现的结论，改用规则层判定重跑一轮。"
    }
}
