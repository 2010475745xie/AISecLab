package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.engine.dimensionLabels
import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.JailbreakVariantsModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.engine.plannedCaseCount
import com.xieguojian.aiseclab.core.engine.plannedCasesByDimension
import com.xieguojian.aiseclab.core.engine.variantCaseCount
import com.xieguojian.aiseclab.core.model.Dimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮要跑多少条」的唯一口径（`core/engine/CasePlan.kt`）。
 *
 * 为什么值得钉住这几个数：2026-10-15 用户报「勾了越狱变体集之后，数字在 50 和 250 之间横跳」。
 * 根子不在算术，而在**同一个问题有两个来源**：配置页是端上自己推的（当时是 250），
 * 运行时是后端实际跑出来的（50 —— 那次连的后端是旧的冻结出包，不认识开关字段，静默丢弃）。
 *
 * 所以这些断言钉的是三个数各自是什么、以及**变体集只挂在安全维度上**这条归属规则：
 * 勾了别的维度却冒出变体集那批安全用例，是以前修过的同型 bug（未勾维度照样跑）。
 */
class CasePlanTest {

    @Test
    fun 不勾变体集时全维度是50条() {
        assertEquals(50, plannedCaseCount(Dimension.values().toSet(), variants = false))
    }

    @Test
    fun 勾上变体集时全维度是314条() {
        // 50 + 264。这个数字必须与后端 engine.run_session 取用例的结果一致，
        // 否则配置页的预告就是在骗用户（耗时和 API 花费都按它估）。
        assertEquals(314, plannedCaseCount(Dimension.values().toSet(), variants = true))
    }

    @Test
    fun 变体集条数与模块一致() {
        assertEquals(264, variantCaseCount())
    }

    @Test
    fun 变体集只挂在安全维度上() {
        val off = plannedCasesByDimension(variants = false)
        val on = plannedCasesByDimension(variants = true)
        assertEquals(on.getValue(Dimension.SECURITY), off.getValue(Dimension.SECURITY) + variantCaseCount())
        Dimension.values().filter { it != Dimension.SECURITY }.forEach { d ->
            assertEquals("变体集不该改到 ${d.label} 的条数", off.getValue(d), on.getValue(d))
        }
    }

    @Test
    fun 没勾安全维度时变体集一条都不算() {
        // 与后端 `useJailbreakVariants and Dimension.security in req.dimensions` 同构。
        val dims = Dimension.values().toSet() - Dimension.SECURITY
        assertEquals(
            plannedCaseCount(dims, variants = false),
            plannedCaseCount(dims, variants = true)
        )
    }

    @Test
    fun 一个维度都不勾就是0条() {
        assertEquals(0, plannedCaseCount(emptySet(), variants = true))
    }

    @Test
    fun 逐维度求和等于总数() {
        val dims = Dimension.values().toSet()
        val byDim = plannedCasesByDimension(variants = true)
        assertEquals(plannedCaseCount(dims, variants = true), dims.sumOf { byDim.getValue(it) })
        assertTrue(byDim.values.all { it > 0 })
    }

    // ── 口径 vs **本机实际组装**（2026-10-17 用户报「本机跑全部勾上也只有 50 条」后加）──
    // 以前 `CasePlanTest` 只钉"口径算出来是多少"，**没人钉"本机引擎实际装了哪些模块"**：
    // 口径在 `CasePlan.kt`，组装在 `TestSession.init` 的 `modules` 表达式里 —— 两处代码，
    // 只改一处就漂移，而漂移的表现正是"配置页说 314、跑出来 50"。
    // 这两条断言用**与 TestSession 同一份表达式**算实际条数，一旦哪边加了模块/忘了过滤就红。

    @Test
    fun 本机实际组装的用例数等于口径() {
        assertEquals(
            plannedCaseCount(Dimension.values().toSet(), variants = true),
            localCaseCount(Dimension.values().toSet(), variants = true),
        )
        assertEquals(
            plannedCaseCount(Dimension.values().toSet(), variants = false),
            localCaseCount(Dimension.values().toSet(), variants = false),
        )
    }

    @Test
    fun 本机全勾加变体集确实会组装出314条() {
        // 直接把这个数钉死：用户报的就是"只有 50"，所以"是不是真的 314"必须有断言，
        // 不能靠读代码推断。
        assertEquals(314, localCaseCount(Dimension.values().toSet(), variants = true))
    }

    @Test
    fun 本机不勾安全维度时不装变体集() {
        val dims = Dimension.values().toSet() - Dimension.SECURITY
        assertEquals(
            localCaseCount(dims, variants = false),
            localCaseCount(dims, variants = true),
        )
    }

    // ── 维度展示串（2026-10-17 设备日志里出现「安全性/…/安全性」）──────────────
    @Test
    fun 维度展示串不重复() {
        // 变体集模块的 dimension 也是 SECURITY —— 直接 map 会把它打两遍，
        // 看起来像"安全维度跑了两轮"。日志里的原样是：
        //   维度 [安全性/逻辑一致性/事实性/鲁棒性/安全性]
        val modules = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule(),
            JailbreakVariantsModule(),
        )
        val labels = dimensionLabels(modules)
        assertEquals("安全性/逻辑一致性/事实性/鲁棒性", labels)
        assertEquals("每个维度只能出现一次", labels.split("/").distinct(), labels.split("/"))
    }

    @Test
    fun 只有安全模块时维度串就是安全性() {
        assertEquals("安全性", dimensionLabels(listOf(SecurityModule(), JailbreakVariantsModule())))
    }

    /**
     * 复刻 `TestSession.run` 里的模块装配路径，数**实际会跑**的用例。
     *
     * ⚠️ 这里刻意不调 `plannedCaseCount` —— 那样就成了"自己证明自己"。
     * 这份装配表达式必须与 `TestSession` 保持一致（两边都改才算改对）。
     */
    private fun localCaseCount(dimensions: Set<Dimension>, variants: Boolean): Int {
        val modules = (
            listOf(
                SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
            ) + if (variants) listOf(JailbreakVariantsModule()) else emptyList()
            ).filter { it.dimension in dimensions }
        return modules.flatMap { it.buildCases() }.size
    }
}
