package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.elapsedLine
import com.xieguojian.aiseclab.core.model.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本次耗时」这一行（`core/model/Report.kt` 的 [formatElapsed] / [Report.elapsedLine]）。
 *
 * 钉住两件事：
 *
 * 1. **格式只有一份**。测试页（`RunScreen` 的实时计时）与报告页（`ReportScreen`）调的是
 *    同一个 `formatElapsed` —— 各写一个必然出现"跑的时候 `42 秒`、报告里 `00:42`"，
 *    用户不知道该信哪个。所以格式本身要在这里钉死。
 * 2. **没有记录时不许显示 `0 秒`**。`elapsedMs == null`（旧版本报告）和 `0L`（真的记为 0）
 *    是两回事 —— 与 `TokenUsageTest` 钉的是同一条口径（「没采集 ≠ 很差」）。
 */
class ElapsedTimeTest {

    private fun reportWith(ms: Long?) = Report(
        modelProfile = ModelProfile(id = "t", name = "", baseUrl = "http://x", apiKey = "", model = "m"),
        timestamp = 0L,
        overallScore = 100f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = emptyList(),
        summary = "",
        elapsedMs = ms,
    )

    @Test
    fun 不满一分钟时只显示秒() {
        assertEquals("0 秒", formatElapsed(0L))
        assertEquals("1 秒", formatElapsed(1_000L))
        // 59.4 秒**截断**成 59 秒，不是四舍五入 —— 计时器只在跑完时才越过这一格，
        // 进位会让"末秒"看起来比实际多一秒。
        assertEquals("59 秒", formatElapsed(59_400L))
    }

    @Test
    fun 过一分钟起显示分与秒() {
        assertEquals("1 分 00 秒", formatElapsed(60_000L))
        assertEquals("12 分 03 秒", formatElapsed(723_000L))
        assertEquals("59 分 59 秒", formatElapsed(3_599_000L))
    }

    @Test
    fun 过一小时才显示小时() {
        assertEquals("1 小时 00 分 00 秒", formatElapsed(3_600_000L))
        assertEquals("1 小时 02 分 03 秒", formatElapsed(3_723_000L))
    }

    @Test
    fun 负数按零处理() {
        // 只可能来自时钟异常（正常口径用的是单调时钟）。显示一个负数"用时"
        // 比显示 `0 秒` 更容易让人以为程序坏了。
        assertEquals("0 秒", formatElapsed(-5_000L))
    }

    @Test
    fun 没有用时记录时说未记录而不是零秒() {
        val line = reportWith(null).elapsedLine()
        assertTrue(line, line.contains("未记录"))
        assertFalse("「没记时间」不能写成 `0 秒`", line.startsWith("0") || line.contains("用时 0 秒"))
    }

    @Test
    fun 真的记为零秒时照实说零秒() {
        // 与上一条成对：0L 是**记录下来的**值，如实显示；null 才是"没记录"。
        val line = reportWith(0L).elapsedLine()
        assertTrue(line, line.contains("用时 0 秒"))
        assertFalse(line, line.contains("未记录"))
    }

    @Test
    fun 有用时记录时给出可读时长() {
        val line = reportWith(723_000L).elapsedLine()
        assertTrue(line, line.contains("12 分 03 秒"))
        assertFalse(line, line.contains("未记录"))
    }
}
