package com.xieguojian.aiseclab.core.report

import com.xieguojian.aiseclab.core.judge.CoT
import com.xieguojian.aiseclab.core.model.Report

/**
 * 报告里**「CoT 处理」那一行**的取数与文案 —— 全项目唯一一份。
 *
 * ## 为什么报告要记这一行
 *
 * 判定链在**测量类**判据（[com.xieguojian.aiseclab.core.judge.RefusalScorer] /
 * [com.xieguojian.aiseclab.core.judge.ComplianceDetector]）里会先剥掉内联思考段
 * （[CoT]）。这条口径意味着**报告的分数依赖"被测模型有没有内联思考段"** ——
 * 两个模型放在一起比时，如果一个是"思考段 + 正文"、另一个是"纯正文"，
 * 它们的数字不在同一把尺上。所以报告必须**自证本次剥了多少条**，否则读者无从判断可比性。
 *
 * ## 取数为什么是现算、不是报告里的字段
 *
 * 它完全可以由 `results[].response` 推出来（谁做的判定都一样，`response` 里就有完整原文），
 * 所以**不设字段、不上报** —— 设了字段就多一份"谁负责填、旧报告怎么办"的契约
 * （`tokenUsage` 就是因为"谁填"很明确才做成了字段）。这里没有那个问题。
 *
 * ## 文案唯一源
 *
 * 报告页 / Markdown 导出 / HTML 导出**三处共用这一句**，不许各自再拼一遍 ——
 * 「三处各拼一遍必然走岔」在本项目已经出过两次（见 `Report.tokenSpendLine` 与
 * `HardeningReport.unattributedNote` 的注释）。
 */
internal fun Report.cotStrippedCount(): Int = results.count { CoT.has(it.response) }

/**
 * 「本次 CoT 处理」那一行的文案。
 *
 * 措辞上刻意**不写「（未）启用剥离」** —— 剥离不是开关，判定链永远会先剥；
 * 「0 条」的意思是**这份报告里的回答压根没有思考段可剥**，不是"这次没剥"。
 * 写成开关会让人以为存在"没剥的那一轮"，而这个状态不存在。
 */
internal fun Report.cotNote(): String {
    val n = cotStrippedCount()
    if (results.isEmpty()) return "本轮没有回答记录，无法判断是否含内联思考段。"
    return if (n == 0) {
        "本轮 ${results.size} 条回答均不含内联思考段（`<think>` 类标签），判定口径无需剥离。"
    } else {
        "已剥离 $n/${results.size} 条回答的内联思考段：测量类判据（拒绝强度 / 妥协度）读剥除后的正文，" +
            "命中类判据（违禁词 / 泄露）读完整响应 —— 思考段对用户可见，里面写出系统提示原文即真泄露。"
    }
}
