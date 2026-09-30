package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.judge.guard.GuardTaxonomy
import com.xieguojian.aiseclab.core.judge.guard.XGuardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * 用**真实报文金样本**验证「服务端 JSON → App 数据类 → 判定」整条链。
 *
 * 存在的理由：这条链断了不会报错。`logprobs` 解析失败只是返回空列表，
 * 于是危险度永远为 null，护栏**静默**退化成文字标签模式 ——
 * 表现是"能跑、有结论、但置信度全被打折"，靠肉眼很难发现。
 * 金样本让它在单测里就暴露，且不需要模型、不需要网络。
 */
class XGuardWireFormatTest {

    private fun parse(fixture: String): OpenAiCompatibleClient.Completion =
        OpenAiCompatibleClient.parseCompletion(fixture)

    @Test
    fun `金样本含未被数据类声明的字段（靠 ignoreUnknownKeys 容错）`() {
        // 这条不是在测 JSON，是在**锁住前提**：llama.cpp 会返回 usage/refusal 等
        // 我们没声明的字段。哪天有人去掉 ignoreUnknownKeys，这里会先炸。
        assertTrue(XGuardWireFixture.HARMFUL.contains("\"usage\""))
        assertTrue(XGuardWireFixture.HARMFUL.contains("\"refusal\""))
        assertNotNull(parse(XGuardWireFixture.HARMFUL))
    }

    @Test
    fun `有害样本：首 token 概率被正确还原成危险度`() {
        val c = parse(XGuardWireFixture.HARMFUL)
        assertTrue(c.content.startsWith("ha"))

        val v = XGuardParser.parse(c.content, c.firstTopTokens)
        assertTrue("应当标定过", v.calibrated)
        assertEquals("ha", v.labelId)
        // 危险度 = 1 - P(sec)；P(sec)=exp(-2.9047)≈0.0548
        assertEquals(0.9452, v.risk!!.toDouble(), 0.005)
        assertEquals(listOf("Cybersecurity - Hacker Attack（ha）"), v.categories)
        assertTrue("应判定为风险", v.risk!! >= 0.5f)
    }

    @Test
    fun `安全样本：危险度接近 0`() {
        val c = parse(XGuardWireFixture.SAFE)
        val v = XGuardParser.parse(c.content, c.firstTopTokens)
        assertEquals("sec", v.labelId)
        assertEquals(listOf("Safe"), v.categories)
        assertTrue("危险度应接近 0，实际 ${v.risk}", v.risk!! < 0.001f)
    }

    @Test
    fun `明确拒绝但复述敏感词：仍判安全`() {
        // 这条是护栏存在的全部意义。样本里模型输出的确是 "sec"，
        // 而 top-k 的亚军恰恰是 "dw"（危险武器）—— 只看词不看联合语义就会误判。
        assertTrue(XGuardWireFixture.REFUSAL.contains("\"dw\""))

        val c = parse(XGuardWireFixture.REFUSAL)
        val v = XGuardParser.parse(c.content, c.firstTopTokens)

        assertEquals("sec", v.labelId)
        assertTrue("拒绝场景不应判为风险", v.risk!! < 0.001f)
        assertTrue(v.categories == listOf("Safe"))
    }

    @Test
    fun `归因文本在生成被截断时仍能取出`() {
        // 金样本是 max_tokens=64 抓的，`</explanation>` 已被截断掉。
        // 若没有兜底路径，这里会拿到 null，报告里最关键的"为什么这么判"就整段丢了。
        listOf(XGuardWireFixture.HARMFUL, XGuardWireFixture.SAFE, XGuardWireFixture.REFUSAL)
            .forEach { fixture ->
                val c = parse(fixture)
                assertTrue("样本本身应当没有闭合标签", !c.content.contains("</explanation>"))
                val rationale = XGuardParser.extractRationale(c.content)
                assertNotNull("截断场景必须仍能取出归因", rationale)
                assertTrue("归因不应残留标签：$rationale", !rationale!!.contains("<explanation>"))
                assertTrue("归因不应包含首行代号：$rationale", !rationale.startsWith("sec"))
            }
    }

    @Test
    fun `服务端不给 logprobs 时退化为文字标签而非误判`() {
        // 模拟不支持 logprobs 的端点：top-k 为空 → 走 fromText，calibrated=false，
        // 风险取满值而不是猜一个中间值（宁可保守）。
        val v = XGuardParser.parse("mc\n<explanation>提供可执行的攻击代码</explanation>", emptyList())
        assertTrue(!v.calibrated)
        assertEquals("mc", v.labelId)
        assertEquals(1f, v.risk!!, 0.0001f)
        assertEquals(listOf("Cybersecurity - Malicious Code（mc）"), v.categories)
    }

    @Test
    fun `概率换算与手算一致`() {
        // 防止有人把 logprob 当成概率直接用（两者差一个 exp）。
        val c = parse(XGuardWireFixture.HARMFUL)
        val secProb = c.firstTopTokens.filter { GuardTaxonomy.isSafeId(it.first) }.sumOf { it.second }
        assertEquals(exp(-2.904731273651123), secProb, 0.0005)
    }
}
