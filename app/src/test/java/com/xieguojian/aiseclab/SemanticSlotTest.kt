package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.report.ChartPalette
import com.xieguojian.aiseclab.core.report.SemanticSlot
import com.xieguojian.aiseclab.ui.theme.SemanticPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色系统的结构性约束回归。
 *
 * ⚠️ 这里**只碰 `SemanticPalette`（纯 ARGB Long）**，绝不引用 `SemanticColors` /
 * `SemanticTheme` —— 单元测试的 classpath 上没有 compose-ui 的 JVM 实现，
 * 一旦触发 `SemanticColorsKt.<clinit>`（里面会构造 `Color(...)`）就会
 * `NoClassDefFoundError: androidx/compose/ui/graphics/ColorKt`。本文件第一版就是这么挂的。
 *
 * 钉的是三件事（都**不是**"好不好看"，那只有截图能判断）：
 *   1. 「风险等级 → 语义槽位」是单射 —— 五个等级不能两个共用一色，否则报告里两种严重程度
 *      长得一样，读者分不出；
 *   2. 浅底档必须**逐值等于** `ChartPalette` 的图例色 —— 否则报告页与导出 HTML 会出现
 *      "同一等级、两种红"，而这种不一致只有肉眼能发现；
 *   3. 深色档的五个色**逐个都比浅色档亮** —— 把「深色模式要提亮而不是调暗」这条设计意图
 *      变成可验证的断言（按相对亮度算）。此前语义色只有浅底一档，深绿放深底上对比度仅 2~3:1。
 */
class SemanticSlotTest {

    @Test
    fun `每个风险等级都有唯一槽位`() {
        val slots = RiskLevel.entries.map { ChartPalette.slotOf(it) }
        assertEquals(
            "等级到槽位必须是单射（两个等级共用一色 = 报告里分不出严重程度）",
            slots.size, slots.toSet().size
        )
        assertEquals(SemanticSlot.GOOD, ChartPalette.slotOf(RiskLevel.LOW))
        assertEquals(SemanticSlot.CAUTION, ChartPalette.slotOf(RiskLevel.MEDIUM))
        assertEquals(SemanticSlot.HIGH, ChartPalette.slotOf(RiskLevel.HIGH))
        assertEquals(SemanticSlot.BAD, ChartPalette.slotOf(RiskLevel.CRITICAL))
        assertEquals(SemanticSlot.NEUTRAL, ChartPalette.slotOf(RiskLevel.UNKNOWN))
    }

    @Test
    fun `浅底风险色与图例用色逐值相同`() {
        assertEquals(ChartPalette.PASS, ChartPalette.riskColor(RiskLevel.LOW))
        assertEquals(ChartPalette.INCONCLUSIVE, ChartPalette.riskColor(RiskLevel.MEDIUM))
        assertEquals(ChartPalette.HIGH, ChartPalette.riskColor(RiskLevel.HIGH))
        assertEquals(ChartPalette.FAIL, ChartPalette.riskColor(RiskLevel.CRITICAL))
        assertEquals(ChartPalette.SKIPPED, ChartPalette.riskColor(RiskLevel.UNKNOWN))
    }

    @Test
    fun `浅底档的语义色就是图例色`() {
        assertEquals(ChartPalette.PASS, SemanticPalette.light(SemanticSlot.GOOD))
        assertEquals(ChartPalette.INCONCLUSIVE, SemanticPalette.light(SemanticSlot.CAUTION))
        assertEquals(ChartPalette.HIGH, SemanticPalette.light(SemanticSlot.HIGH))
        assertEquals(ChartPalette.FAIL, SemanticPalette.light(SemanticSlot.BAD))
        assertEquals(ChartPalette.SKIPPED, SemanticPalette.light(SemanticSlot.NEUTRAL))
    }

    @Test
    fun `深色档的五个语义色都亮于浅色档`() {
        SemanticSlot.entries.forEach { slot ->
            val l = luminance(SemanticPalette.light(slot))
            val d = luminance(SemanticPalette.dark(slot))
            assertTrue(
                "$slot 的深色档应当更亮（深底上才看得清）：light=$l dark=$d",
                d > l
            )
        }
    }

    @Test
    fun `每一档内五个槽位互不相同`() {
        listOf("浅底" to SemanticPalette::light, "深色" to SemanticPalette::dark).forEach { (name, pick) ->
            val values = SemanticSlot.entries.map { pick(it) }
            assertEquals("$name 档里五个语义色不该重合", values.size, values.toSet().size)
        }
    }

    /** 相对亮度（sRGB 加权）。只用于"谁更亮"的比较，不必做 gamma 校正。 */
    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
