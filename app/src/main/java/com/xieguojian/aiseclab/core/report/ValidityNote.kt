package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.judge.OutputValidity
import com.xieguojian.aiseclab.core.model.Report

/**
 * 报告里**「输出有效性」那一行**的取数与文案 —— 全项目唯一一份（B7 的报告侧落点）。
 *
 * ## 为什么报告要记这一行
 *
 * B7 把「无效输出」立成了第三态：退化循环 / 被截断 / 只输了推理段的回答
 * **既不算守住、也不算失守**，直接从所有百分比的分母里剔除
 * （见 [com.xieguojian.aiseclab.core.judge.OutputValidity]）。
 *
 * 这条口径有一个**必须自证**的副作用：**分母变小了**。
 * 「失守率 12%」在两个不同轮次之间可以直接比，只因为分母是同一批用例；
 * 但如果一轮里有 5 条被判无效、另一轮一条都没有，
 * 那两个百分比**不是在同一把尺上量的** —— 而报告上完全看不出来。
 * 所以和 CoT 处理（[cotNote]）一样，报告必须自己说清本轮剔了几条、为什么剔。
 *
 * ## 取数为什么是现算、不是报告里的字段
 *
 * 与 [cotNote] / [reproLine] 同一做法：它完全可以由 `results[].response` 推出来
 * （判定链拿到的就是那段原文），所以**不设字段、不上报** ——
 * 设了字段就多一份"谁负责填、旧报告怎么办"的契约
 * （`tokenUsage` 是另一回事，它的"谁填"是明确的）。
 *
 * ## 文案唯一源
 *
 * 报告页 / Markdown 导出 / HTML 导出**三处共用这一句**。
 * 「同一个词在两份文案里对错相反」这种坑本项目已经踩过
 * （见 `HardeningReport.unattributedNote` 的注释），所以这句只准在这里拼。
 */
internal fun Report.invalidOutputCount(): Int =
    results.count { OutputValidity.of(it.response) == OutputValidity.Result.INVALID }

/**
 * 「本轮输出有效性」那一行的文案。
 *
 * 三态必须分得开，**不许把 0 条与"没测到"混为一谈**（铁律 5.2）：
 *
 * | 情形 | 说法 |
 * |---|---|
 * | 一条回答都没有 | 「无法判断」—— 不给结论，也不给 0 |
 * | 0 条无效 | 全部可用（分母即全部样本），**这是好结论，要正面说出来** |
 * | N > 0 条无效 | 剔了几条、为什么剔、以及**剔除不等于守住** |
 *
 * ⚠️ 最后那句「不是「模型守住了」」是这一行的核心。少了它，读者会把
 * "本轮失守率降了"读成"模型变强了"，而真实原因可能只是**模型坏掉了** ——
 * 对一个取证工具来说，这比少报一条失守更严重（见 `OutputValidity` 的实测表）。
 */
internal fun Report.validityNote(): String {
    if (results.isEmpty()) return "本轮没有回答记录，无法判断输出可用性。"
    val n = invalidOutputCount()
    if (n == 0) {
        return "本轮 ${results.size} 条回答全部可用（无退化循环 / 无截断 / 非纯推理段），" +
            "全部样本都进了判定分母。"
    }
    return "本轮有 $n/${results.size} 条回答被判「无效」（退化循环 / 被截断 / 只输出了推理段），" +
        "已从全部百分比的分母中剔除 —— 「无效」既不算守住也不算失守。" +
        "这几条的结论是「没有拿到可用的回答」，不是「模型守住了」。"
}
