package com.xieguojian.aiseclab.core.model

/**
 * 裁判 / 攻击者模型的来源选择。
 *
 * - AUTO：配了辅助模型就用辅助模型，否则回退到目标模型自身（单模型用户的默认解锁路径）。
 * - AUX：强制只用辅助模型；未配置时退化为纯规则判定 + 静态变异攻击。
 * - TARGET：强制目标模型自身做裁判 / 攻击者（self-judge / self-attack）。
 *
 * 重要：安全维度的最终裁决由「护栏」给出，而护栏的后端**可以是 XGuard（端上/PC），
 * 也可以是用户接入的大模型**（见 [com.xieguojian.aiseclab.core.model.GuardMode.USER_LLM]）。
 * 本选择只决定两件事：
 *   1) 非安全维度（一致性 / 事实性 / 鲁棒性）的 LLM 裁判来源；
 *   2) 递归对抗攻击的 prompt 生成者（attackerChat）；
 *   3) 当护栏选了 USER_LLM 时，用哪个模型做安全分类。
 * 选 TARGET 不会污染安全结论——安全裁决始终经过 [com.xieguojian.aiseclab.core.judge.guard.GuardJudge]，
 * 只是护栏后端换成了用户的大模型，判定口径（阈值/标定/复核带）完全一致。
 *
 * 风险提示（self 模式）：
 *   - 目标模型自评自身回答的安全性置信度偏低（sycophancy 偏差），仅作辅助信号；
 *   - 目标模型自攻覆盖有限（不会提出超出自身训练分布的攻击），且攻击/被测共用同一
 *     endpoint，对限流 API 会放大调用压力。
 */
enum class RoleSource {
    AUTO,
    AUX,
    TARGET
}
