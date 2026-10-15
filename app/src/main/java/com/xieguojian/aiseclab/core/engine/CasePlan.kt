package com.xieguojian.aiseclab.core.engine

import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.JailbreakVariantsModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.model.Dimension

/**
 * 「本轮要跑多少条用例」的**唯一口径**。
 *
 * ## 为什么必须收敛成一份（2026-10-15）
 *
 * 用户报过一个现象：勾上「越狱变体集」之后，数字在 **50 和 250 之间横跳**。
 * 根因不是算错了，而是**同一个问题有两个来源、两个口径**：
 *
 * - 配置页那个数字是**端上本地推算**的（模块表求和）——它永远反映最新代码，说 250；
 * - 运行时的 `completed / total` 是**后端实际跑出来的** —— 那次连的是一个
 *   **10-12 16:24 冻结的出包**，而「变体集开关」（`useJailbreakVariants`）这个字段
 *   是当天 23:05 才进的代码。旧后端不认识它，pydantic 默认把未知字段**静默丢弃**
 *   （不报错），于是照旧只跑 50 条。
 *
 * 于是一边喊 250、一边跑 50。**这两个数字都不假，是"预估"和"实际"被混成一句话看**。
 *
 * 收敛点有两处，缺一不可：
 * 1. 本文件 —— 端上所有"预告条数"的地方（配置页 / 运行页校验）都从这里取，不再各算一份；
 * 2. 运行时 —— [com.xieguojian.aiseclab.ui.TestRunService] 拿实际条数与本函数比对，
 *    不一致就**说出原因**（旧后端/旧引擎），而不是让用户自己对着两个数字猜。
 *
 * ⚠️ 与后端 `backend/app/engine.py::run_session` 的取用例逻辑必须同构：
 * 静态用例按勾选维度过滤；变体集**只挂在安全维度**上，且只有在勾了安全维度时才并入
 * （勾了别的维度却冒出 200 条安全用例，是另一个已经修过的同型 bug）。
 */
private val BASE_MODULES = listOf(
    SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
)

/** 变体集条数。界面文案（「再并入 N 条」）也用它 —— 别把 200 写死在提示语里。 */
fun variantCaseCount(): Int = JailbreakVariantsModule().buildCases().size

/** 每个维度各有多少条（**不考虑用户勾选**，供界面逐维度展示）。 */
fun plannedCasesByDimension(variants: Boolean): Map<Dimension, Int> {
    val base = BASE_MODULES.associate { it.dimension to it.buildCases().size }
    // 变体集的 dimension 就是 SECURITY，所以它登记在安全维度那一格：
    // 界面显示「安全 218」而不是多出一行"变体集 200"，与实际跑出来的归属一致。
    return if (!variants) base
    else base + (Dimension.SECURITY to (base.getValue(Dimension.SECURITY) + variantCaseCount()))
}

/** 勾选维度之后本轮实际会跑的条数。 */
fun plannedCaseCount(dimensions: Set<Dimension>, variants: Boolean): Int {
    val byDim = plannedCasesByDimension(variants)
    return dimensions.sumOf { byDim[it] ?: 0 }
}
