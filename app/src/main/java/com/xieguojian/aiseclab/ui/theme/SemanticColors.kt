package com.xieguojian.aiseclab.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.report.ChartPalette
import com.xieguojian.aiseclab.core.report.SemanticSlot

/**
 * 语义色的**原始 ARGB 值**（纯数据层，不依赖 Compose）。
 *
 * ── 为什么必须单独抽这一层 ──
 *
 * 单元测试的 classpath 上**没有** compose-ui 的 JVM 实现。只要颜色值在类初始化阶段就
 * 被构造成 `Color(...)`，任何碰到本文件的单测都会在 `<clinit>` 抛
 * `NoClassDefFoundError: androidx/compose/ui/graphics/ColorKt`（本文件第一版就是这么挂的，
 * 两条测试直接红）。
 *
 * 抽出来之后：[SemanticColors] 只负责"把 ARGB 转成 Compose 的 Color"，
 * 而「深色档是否真的比浅色档亮」「浅底档是否与图例色一致」这类**结构性约束**
 * 可以在纯 JVM 单测里验证（见 `SemanticSlotTest`）—— 否则它们就只能靠肉眼在真机上看。
 */
internal object SemanticPalette {

    /** 浅底档：与 [ChartPalette] 的图例色**逐值相同**。导出件永远是白底，两边必须一致。 */
    fun light(slot: SemanticSlot): Long = when (slot) {
        SemanticSlot.GOOD -> ChartPalette.PASS
        SemanticSlot.CAUTION -> ChartPalette.INCONCLUSIVE
        SemanticSlot.HIGH -> ChartPalette.HIGH
        SemanticSlot.BAD -> ChartPalette.FAIL
        SemanticSlot.NEUTRAL -> ChartPalette.SKIPPED
    }

    /**
     * 深色档：亮度整体抬一档，保证在 `#1C1B1F` 这类深底上仍可读。
     *
     * 不是把浅色档调暗，而是**调亮** —— 浅色档那几个色（深绿 `#1D9E75`、深橙 `#D85A30`）
     * 直接放深底上对比度只有 2~3:1。`BAD` 取 `#FF7B72` 而不是把 `#E24B4A` 提一点，
     * 是因为后者在深底上仍偏暗；这个值来自 `RunScreen` 终端里已在用的错误色
     * （GitHub 深色主题调过的红），实际观感已验证过，不再另造一个。
     *
     * 「每个档都比浅色档亮」有单测钉住（按相对亮度算，不靠肉眼）。
     */
    fun dark(slot: SemanticSlot): Long = when (slot) {
        SemanticSlot.GOOD -> 0xFF5DCAA5
        SemanticSlot.CAUTION -> 0xFFE5B95C
        SemanticSlot.HIGH -> 0xFFF0997B
        SemanticSlot.BAD -> 0xFFFF7B72
        SemanticSlot.NEUTRAL -> 0xFFA6A6A6
    }
}

/**
 * 语义色 —— 「好 / 拿不准 / 偏高 / 糟 / 没数据」这五种含义在当前主题下长什么样。
 *
 * ── 为什么需要它（两个真实缺陷）──
 *
 * 1. **同一套语义色此前散在四处**：`ChartPalette` 一份，`HomeScreen`、`ReportScreen`、
 *    `ReportGenerator` 各硬编码一份（三份 `RiskLevel → 颜色` 的 `when` 一字不差）。
 *    证据：`ReportGenerator` 写的是 `#E24B4B`，`ChartPalette` 是 `#E24B4A` ——
 *    **已经差了一个字符**，只是没人发现。现在「等级 → 槽位」的判据收敛在
 *    [ChartPalette.slotOf]，各处只负责"这个槽位在我这套配色里是哪个值"。
 *
 * 2. **深色模式只有浅底一档**。深绿 `#1D9E75`、深橙 `#D85A30` 放在深底上对比度只有
 *    2~3:1，远不够读。深色档见 [SemanticPalette.dark]。
 *
 * ── 与 `ChartPalette` 的分工（别合并）──
 *
 * `ChartPalette` 的值是给**白底**用的：HTML 导出件永远是白底。深色模式下同一支红/绿
 * 必须换成亮档，所以这里必须是"随主题变化"的一份。两边的**颜色值**本就不同，
 * 但**哪个等级对应哪个槽位**的判据只有一份。
 */
@Immutable
data class SemanticColors(
    /** 通过 / 低风险 / 指标变好。 */
    val good: Color,
    /** 中风险 / 拿不准 / 需要留意。 */
    val caution: Color,
    /** 高风险。 */
    val high: Color,
    /** 严重风险 / 判定失守。 */
    val bad: Color,
    /** 无数据 / 不可评估 / 中性对照。 */
    val neutral: Color
) {
    fun of(slot: SemanticSlot): Color = when (slot) {
        SemanticSlot.GOOD -> good
        SemanticSlot.CAUTION -> caution
        SemanticSlot.HIGH -> high
        SemanticSlot.BAD -> bad
        SemanticSlot.NEUTRAL -> neutral
    }

    /**
     * 风险等级 → 颜色。**等级到槽位的映射不在这里**，在 [ChartPalette.slotOf] ——
     * 这里只做"槽位 → 当前主题下的实际值"这一步查表。
     */
    fun risk(level: RiskLevel): Color = of(ChartPalette.slotOf(level))
}

private val Light = SemanticColors(
    good = Color(SemanticPalette.light(SemanticSlot.GOOD)),
    caution = Color(SemanticPalette.light(SemanticSlot.CAUTION)),
    high = Color(SemanticPalette.light(SemanticSlot.HIGH)),
    bad = Color(SemanticPalette.light(SemanticSlot.BAD)),
    neutral = Color(SemanticPalette.light(SemanticSlot.NEUTRAL))
)

private val Dark = SemanticColors(
    good = Color(SemanticPalette.dark(SemanticSlot.GOOD)),
    caution = Color(SemanticPalette.dark(SemanticSlot.CAUTION)),
    high = Color(SemanticPalette.dark(SemanticSlot.HIGH)),
    bad = Color(SemanticPalette.dark(SemanticSlot.BAD)),
    neutral = Color(SemanticPalette.dark(SemanticSlot.NEUTRAL))
)

/** 按明暗取档。供 `AISecLabTheme` 使用；业务代码请走 [SemanticTheme.colors]。 */
internal fun semanticColorsFor(darkTheme: Boolean): SemanticColors = if (darkTheme) Dark else Light

val LocalSemanticColors = staticCompositionLocalOf { Light }

/** 用法：`SemanticTheme.colors.bad`（与 `MaterialTheme.colorScheme` 同一姿势）。 */
object SemanticTheme {
    val colors: SemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSemanticColors.current
}
