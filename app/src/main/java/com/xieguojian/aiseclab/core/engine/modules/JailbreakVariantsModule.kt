package com.xieguojian.aiseclab.core.engine.modules

import com.xieguojian.aiseclab.core.engine.AttackModule
import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.TestCase

/**
 * 越狱变体集模块（100 条）—— [JailbreakCases] 的插件化外壳。
 *
 * 它**不并入默认 50 条**：默认集是"每个维度跑到就算过"的回归基线，
 * 变体集是"同一意图换 5 种包装"的取证样本，两者目的不同、成本差 2 倍（100 vs 18 条安全用例）。
 * 由用户在测试配置里显式打开（见 `TestSession.includeVariants`），
 * 打开后并入安全维度 —— 因为它的 [dimension] 就是 SECURITY，维度勾选天然把它挡住。
 */
class JailbreakVariantsModule : AttackModule {
    override val id = "jailbreak_variants"
    override val dimension = Dimension.SECURITY
    override val displayName = "越狱变体集"

    override fun buildCases(): List<TestCase> = JailbreakCases.buildCases()
}
