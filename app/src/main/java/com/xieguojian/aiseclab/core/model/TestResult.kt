package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.Serializable

/**
 * 递归对抗（PAIR）的**单轮**记录。
 *
 * 为什么要单独有它：`attackTrail: List<String>` 存的只是拼好的展示字符串，而且两端格式
 * 还不一致（后端含回答片段、端上只有 prompt、端上 fallback 是第三种）。画"攻击演进时间轴"
 * 需要的「这一轮到底拒绝没拒绝」从字符串里取不出来，所以每轮结构化落库。
 *
 * 契约（**改一端必须同步另一端**，见 `backend/app/models.py::AttackRound`）：
 * - [round] **统一 1-based**：后端 `for r in range(max_rounds)` 是 0-based，落库时须 `r + 1`，
 *   不统一会让两端时间轴**错位一格**。另外**两种攻击路径的 `round` 语义不同**：
 *   递归对抗（PAIR）是**轮次**，静态变异路径（未配攻击模型时的默认路径）是**变体序号** ——
 *   都是 1-based 的"第几步"，但不要在文案里把它写成"第 N 轮"（变异路径没有轮的概念）；
 * - [refused] = 该轮**未破防**。**两侧的判据不同且都是"该侧循环自己的破防判据"**：
 *   端上每轮都真判过，用 `verdict == FAIL && confidence >= 0.7` 取反；
 *   后端每用例只判最终那一轮（逐轮判会把裁判调用量抬到最多 10 倍），只能用它循环里的
 *   拒绝词表取反。它只是**展示用派生量，不得回流去影响 verdict 或评分**；
 * - [response] 是该轮被测模型的原始回答，导出时**不截断**（报告是审计/复盘产物）。
 */
@Serializable
data class AttackRound(
    val round: Int,
    val prompt: String,
    val response: String,
    val refused: Boolean,
    /**
     * 这一轮的**态度**（半妥协检测，方案 §周 1–2 的 2.2）。
     *
     * ⚠️ 与 [refused] **不同源、也不等价**，别混用：
     * - [refused] 是**判定派生量**（该轮有没有被攻破），端上逐轮真判过、后端是拒绝词表启发式；
     * - [compliance] 是**纯文本测量**（这段回答读起来像拒绝 / 半妥协 / 配合），
     *   两端同一套规则、零成本、与判定无关。
     *
     * 所以「refused = true（没被攻破）但 compliance = COMPLIED（读起来像配合）」是**合法组合**，
     * 含义是"这一轮内容没造成失守，但模型在文本上并没有挡" —— 值得人工看一眼。
     * 默认 [ComplianceLevel.UNKNOWN]：旧报告没有这个字段，反序列化后就是它，不要显示成「拒绝」。
     */
    val compliance: ComplianceLevel = ComplianceLevel.UNKNOWN
)

/**
 * 单条测试的完整结果，含递归对抗的攻击轨迹。
 */
@Serializable
data class TestResult(
    val testCase: TestCase,
    val response: String,
    val judgeResult: JudgeResult,
    val latencyMs: Long,
    /** 递归对抗：每轮攻击 prompt（**旧格式，保留兼容**，新代码请读 [attackRounds]）。 */
    val attackTrail: List<String> = emptyList(),
    /**
     * 递归对抗的**每轮结构化记录** —— 攻击演进时间轴的数据源。
     * ⚠️ 字段名必须与后端 `app/models.py::TestResult.attackRounds` 逐字一致：反序列化用的是
     * `ignoreUnknownKeys = true`，名字对不上会**静默丢弃**，表现成"时间轴是空的"而**不报错**。
     * 旧报告没有这个字段 → 默认空列表，时间轴不显示，不影响报告其余部分。
     */
    val attackRounds: List<AttackRound> = emptyList(),
    /**
     * 多次采样的一致性（多数派占比，0..1）。**只后端会填** —— 端上不做重复采样，
     * 恒为 1.0。⚠️ 字段名必须与后端 `app/models.py::TestResult` 逐字一致：反序列化用的是
     * `ignoreUnknownKeys = true`，名字对不上会**静默丢弃**，稳定性信号就再也到不了界面。
     */
    val stability: Float = 1f,
    /**
     * false = 单样本模式（后端 `SAMPLES_PER_CASE=1`，或端上本机跑），此时 [stability]
     * 是占位值 1.0、**不代表真实波动**。别把占位值当成"模型很稳定"。
     */
    val stabilityMeasured: Boolean = false
)
