package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.ui.progressText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知栏进度文案（[progressText]）。
 *
 * 这段文案是"续跑到底复用了多少"的**唯一出口**（终端日志里那行进度只报总数，
 * 详细说明在终端里、用户看不到通知栏）—— 说错了用户会以为 token 白烧，
 * 所以钉住三条：不复用时不加后缀、复用时两个数字都对、上游口径异常时不出现负数。
 */
class ProgressTextTest {

    @Test
    fun `全新一轮不加复用后缀`() {
        // resumed=0 = 没复用。加个"复用 0 / 新跑 3"只会添噪音。
        assertEquals("进行中 3 / 314", progressText(3, 314, 0))
        // 负数同样是"没有复用"（后端理论上不会下发，但别让它渲染出"复用 -1"）
        assertEquals("进行中 3 / 314", progressText(3, 314, -1))
    }

    @Test
    fun `续跑时复用与新跑两个数字都对`() {
        // 176 条复用 + 2 条真跑 = 178
        assertEquals("进行中 178 / 314（复用 176 · 新跑 2）", progressText(178, 314, 176))
    }

    @Test
    fun `刚开始续跑时全是复用`() {
        // 复用条瞬间完成，第一帧就可能已经是"全部复用、新跑 0"—— 不能出现负数或空白
        assertEquals("进行中 176 / 314（复用 176 · 新跑 0）", progressText(176, 314, 176))
    }

    @Test
    fun `上游口径异常时兜底成 0 而不是负数`() {
        // resumed > completed 是不该发生的（后端按累计值下发），万一发生也要显示得下去
        assertEquals("进行中 1 / 314（复用 5 · 新跑 0）", progressText(1, 314, 5))
    }
}
