package com.xieguojian.aiseclab

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
}
