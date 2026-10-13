package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.RunMode
import com.xieguojian.aiseclab.core.resolveRunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「本轮在哪跑」的唯一口径（`core/RunMode.kt`）。
 *
 * 背景：2026-10-13 真机实测卡住 —— 「本机运行测试」开关打开、后端地址也填着，
 * 一开测照样走后端（旧口径是「开关只在地址为空时才生效」），后端不通就只能干瞪眼。
 * 现在口径定为 **开关打开 = 本机优先、忽略地址**。
 *
 * 这四条覆盖的是「配置组合」而不是「单点取值」—— 判据分叉正是发生在组合上。
 */
class RunModeTest {

    @Test
    fun 开关打开时后端地址填了也走本机() {
        assertEquals(RunMode.LOCAL, resolveRunMode("http://192.168.1.4:8000", true))
    }

    @Test
    fun 开关打开且没填地址也走本机() {
        assertEquals(RunMode.LOCAL, resolveRunMode("", true))
    }

    @Test
    fun 开关关闭且填了地址走后端() {
        assertEquals(RunMode.BACKEND, resolveRunMode("http://192.168.1.4:8000", false))
    }

    @Test
    fun 开关关闭且没填地址不允许开测() {
        assertNull(resolveRunMode("", false))
    }

    @Test
    fun 地址只有空白等同没填() {
        assertNull(resolveRunMode("   ", false))
        assertEquals(RunMode.LOCAL, resolveRunMode("   ", true))
    }
}
