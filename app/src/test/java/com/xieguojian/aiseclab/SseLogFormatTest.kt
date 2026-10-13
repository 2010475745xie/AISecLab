package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.api.BackendClient
import com.xieguojian.aiseclab.core.api.progressLogLines
import com.xieguojian.aiseclab.core.api.sseDetailLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后端 SSE 摘要 → 终端日志行的拆行逻辑（从 BackendClient 抽出的纯函数）。
 *
 * 这段逻辑写错的代价是**静默**的：界面上只是多几个空行、或者少几个片段，
 * 肉眼很难发现是它的问题（用户 2026-10-13 反馈的「终端看不到模型回复」，
 * 根因就在后端压根没发 message；这个函数负责的是"发了之后怎么显示"这一半）。
 * 所以边界都钉住：null、纯空白、多行含空行、单行、行尾 CRLF。
 */
class SseLogFormatTest {

    @Test
    fun 没有摘要时返回空列表() {
        assertEquals(emptyList<String>(), sseDetailLines(null))
    }

    @Test
    fun 全是空白时不产生日志行() {
        assertEquals(emptyList<String>(), sseDetailLines("   \n\n  \t "))
    }

    @Test
    fun 多行摘要逐行输出且丢掉空行() {
        val msg = "✓ 《标题》 → 失守（置信 90% · LLM）\n\n  输入：xxx\n  回答：yyy\n"
        assertEquals(
            listOf("✓ 《标题》 → 失守（置信 90% · LLM）", "  输入：xxx", "  回答：yyy"),
            sseDetailLines(msg),
        )
    }

    @Test
    fun 单行摘要就是单条日志() {
        assertEquals(listOf("只有一行"), sseDetailLines("只有一行"))
    }

    @Test
    fun 行尾是CRLF也能正确拆() {
        assertEquals(listOf("a", "b", "c"), sseDetailLines("a\r\nb\r\nc"))
    }

    // ── 接线：progress 事件 → 终端日志行（钉住"模型回复真的进了日志"）──

    @Test
    fun 进度事件把后端摘要接到计数行之后() {
        val ev = BackendClient.SseEvent(
            event = "progress", completed = 3, total = 18, dimension = "security",
            message = "✓ 《系统角色重置》 → 通过（置信 80% · LLM）\n  输入：xxx\n  回答：yyy",
        )
        assertEquals(
            listOf(
                "● [后端] [security] 进度 3/18",
                "✓ 《系统角色重置》 → 通过（置信 80% · LLM）",
                "  输入：xxx",
                "  回答：yyy",
            ),
            progressLogLines(ev),
        )
    }

    @Test
    fun 后端没带摘要时只剩计数行() {
        val ev = BackendClient.SseEvent(event = "progress", completed = 1, total = 18, dimension = "security")
        assertEquals(listOf("● [后端] [security] 进度 1/18"), progressLogLines(ev))
    }

    @Test
    fun 没有维度时不拼空的方括号() {
        val ev = BackendClient.SseEvent(event = "progress", completed = 2, total = 18)
        assertEquals("● [后端] 进度 2/18", progressLogLines(ev).first())
    }
}
