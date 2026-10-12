package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.Serializable

/**
 * 单条测试用例。
 */
@Serializable
data class TestCase(
    val id: String,
    val dimension: Dimension,
    val title: String,
    val prompt: String,
    val expected: ExpectedBehavior,
    val detectionHints: List<DetectionHint> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * 这条用例**设计上**想达到的有害意图（见 [HarmIntent]）。
     *
     * ⚠️ 这是**预设标注**，不是运行时判定结果 —— 运行时识别出来的意图走
     * [AttackAttribution.intent]，两者可能不一致（攻击者把 prompt 改写偏了）。
     *
     * 默认 [HarmIntent.UNKNOWN]：旧用例集没有这个标注，加可选字段才能兼容反序列化
     * （两端都开着 `ignoreUnknownKeys`，字段对不上是**静默丢弃**，所以字段名必须逐字一致）。
     */
    val intent: HarmIntent = HarmIntent.UNKNOWN,
    /**
     * 这条用例**设计上**用的越狱策略族（见 [JailbreakStrategy]）。
     *
     * 非安全维度的用例（一致性/事实性/鲁棒性）本来就不是越狱，如实标 [JailbreakStrategy.NONE]
     * ——不要为了"看起来有标签"而硬塞一个策略，那会让 Strategy-ASR 的分母混进不属于它的样本。
     */
    val strategy: JailbreakStrategy = JailbreakStrategy.NONE
)

/**
 * 期望行为，决定评判器往哪个方向判。
 */
@Serializable
enum class ExpectedBehavior {
    SHOULD_REFUSE,            // 应当拒绝（安全类）
    SHOULD_ANSWER_CORRECTLY,  // 应当正确回答（一致性/事实类）
    SHOULD_STAY_CONSISTENT,   // 应当保持稳定（鲁棒类）
    SHOULD_SAY_UNKNOWN        // 应当承认不知道（抗幻觉类）
}

/**
 * 评判提示：给规则引擎/裁判提供「怎么判才算通过」的线索。
 */
@Serializable
data class DetectionHint(
    val hintType: HintType,
    val keywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList()
)

@Serializable
enum class HintType {
    FORBIDDEN_CONTENT,     // 危险内容
    PII_LEAK,             // 敏感信息泄露
    SYSTEM_PROMPT_LEAK,   // 系统提示泄露
    CONTRADICTION,        // 自相矛盾
    FAKE_CITATION,        // 虚假引用
    HALLUCINATED_FACT,    // 编造事实
    WRONG_ANSWER          // 错误答案
}
