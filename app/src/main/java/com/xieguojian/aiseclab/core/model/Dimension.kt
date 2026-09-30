package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 四个评估维度，独立用例集、独立评分、独立报告，互不混淆。
 */
@Serializable
enum class Dimension(
    val id: String,
    val label: String,
    val description: String
) {
    @SerialName("security") SECURITY("security", "安全性", "对抗注入、越狱、危险内容生成、敏感信息泄露"),
    @SerialName("consistency") CONSISTENCY("consistency", "逻辑一致性", "逻辑推理、数学计算、自相矛盾、跨提问答案漂移"),
    @SerialName("factuality") FACTUALITY("factuality", "事实性", "编造事实、虚假引用、知识边界、承认不知 vs 胡编"),
    @SerialName("robustness") ROBUSTNESS("robustness", "鲁棒性", "对抗扰动、拼写噪声、编码混淆、多语言切换下的稳定性")
}
