package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RiskLevel
import com.xieguojian.aiseclab.core.model.TokenUsage
import com.xieguojian.aiseclab.core.model.tokenSpendLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本次消耗」这一行（`core/model/Report.kt` 的 [Report.tokenSpendLine]）。
 *
 * 要钉住的口径：**没有记录时不许显示 0**。`tokenUsage == null`（旧版本报告、
 * 或本轮一次模型调用都没发起）和 `total == 0`（真的没烧）是两回事 ——
 * 报告页 / Markdown 导出 / HTML 导出三处共用这一个函数，所以口径只有这一份。
 */
class TokenUsageTest {

    private fun reportWith(usage: TokenUsage?) = Report(
        modelProfile = ModelProfile(id = "t", name = "", baseUrl = "http://x", apiKey = "", model = "m"),
        timestamp = 0L,
        overallScore = 100f,
        riskLevel = RiskLevel.LOW,
        dimensions = emptyMap(),
        results = emptyList(),
        summary = "",
        tokenUsage = usage,
    )

    @Test
    fun 没有用量记录时说未记录而不是零() {
        val line = reportWith(null).tokenSpendLine()
        assertTrue(line, line.contains("未记录"))
        assertFalse("「没记录」不能写成 `0 token`", line.startsWith("0"))
    }

    @Test
    fun 有用量时列出总数与分角色明细() {
        val line = reportWith(
            TokenUsage(
                total = 12345, prompt = 9000, completion = 3345, meteredCalls = 40,
                byRole = mapOf("被测" to 8000, "裁判" to 4345),
            )
        ).tokenSpendLine()
        assertTrue(line, line.contains("1.2万 token"))   // 过万折算
        assertTrue(line, line.contains("被测 8,000"))     // 不过万按千分位
        assertTrue(line, line.contains("裁判 4,345"))
    }

    @Test
    fun 有调用没返回用量时要显式标出来() {
        val line = reportWith(
            TokenUsage(
                total = 500, meteredCalls = 3, unmeteredCalls = 7,
                byRole = mapOf("被测" to 500),
            )
        ).tokenSpendLine()
        assertTrue(line, line.contains("500 token"))
        assertTrue("有没计到的调用就必须说出来，不能假装完整", line.contains("7 次未返回用量"))
    }
}
