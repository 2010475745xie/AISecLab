package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.judge.guard.GuardTaxonomy
import com.xieguojian.aiseclab.core.judge.guard.XGuardPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XGuard 提示模板的契约测试。
 *
 * 这些断言全部**不需要模型**就能跑：它们守的是"PC 与端上喂给模型的输入必须逐字节相同"
 * 这条底线。模板空白一漂移，PC 判 `sec`、端上判 `mc` 的鬼故事就开始了，
 * 而且这种不一致极难复现。所以宁可让单测在 CI 里拦住。
 */
class XGuardPromptTest {

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `契约用例渲染结果哈希不变`() {
        val rendered = XGuardPrompt.render(
            XGuardPrompt.CONTRACT_QUERY,
            XGuardPrompt.CONTRACT_RESPONSE
        )
        assertEquals(XGuardPrompt.CONTRACT_SHA256, sha256(rendered))
    }

    @Test
    fun `输入两侧空白被裁剪（与模板 trim 过滤器一致）`() {
        val a = XGuardPrompt.render("  问题  ", "\n\n回答\n")
        val b = XGuardPrompt.render("问题", "回答")
        assertEquals(b, a)
    }

    @Test
    fun `渲染结果带 QR 双段标记`() {
        val rendered = XGuardPrompt.render("q", "r")
        assertTrue(rendered.contains("[User Query] q"))
        assertTrue(rendered.contains("[LLM Response] r"))
    }

    @Test
    fun `提问与回复各出现一次，不会被拼进同一个分支`() {
        val rendered = XGuardPrompt.render("ALPHA", "BETA")
        assertEquals(1, Regex("ALPHA").findAll(rendered).count())
        assertEquals(1, Regex("BETA").findAll(rendered).count())
        // 提问必须在回复之前（QR 顺序反了模板会认成单条内容）
        assertTrue(rendered.indexOf("ALPHA") < rendered.indexOf("BETA"))
    }

    @Test
    fun `结尾停在 assistant 空 think 块后，首个生成 token 即结论`() {
        assertTrue(XGuardPrompt.SUFFIX.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        val rendered = XGuardPrompt.render("q", "r")
        assertTrue(rendered.endsWith(XGuardPrompt.SUFFIX))
    }

    @Test
    fun `代号表与 GuardTaxonomy 完全对齐`() {
        // 从模板前缀里抠出 "- xx: Label" 行，和代码里的分类法逐条对账。
        // 这条测试的意义：模板是"生成"的，分类法是"手写"的，两者一旦漂移，
        // 模型吐出来的代号就会在 resolve() 里返回 null，整条判定链静默失效。
        val lines = XGuardPrompt.PREFIX.lines().filter { CODE_LINE.matches(it) }
        assertEquals(GuardTaxonomy.TABLE.size, lines.size)

        val parsed = lines.associate { line ->
            val id = line.removePrefix("- ").substringBefore(": ").trim()
            val label = line.substringAfter(": ").trim()
            id to label
        }
        assertEquals(GuardTaxonomy.TABLE.keys, parsed.keys)
        GuardTaxonomy.TABLE.forEach { (id, risk) ->
            assertEquals("代号 $id 的类别名与模板不一致", risk.category, parsed[id])
        }
    }

    @Test
    fun `安全类在表中且是唯一的免风险代号`() {
        assertTrue(GuardTaxonomy.TABLE.containsKey(GuardTaxonomy.SAFE_ID))
        assertEquals(29, GuardTaxonomy.TABLE.size)
        assertEquals(GuardTaxonomy.SAFE_ID, GuardTaxonomy.resolve("sec"))
        assertFalse(GuardTaxonomy.isSafeId("mc"))
    }

    private companion object {
        /**
         * 只认 `- xx: Label` 形态的分类行。
         * 别用 `startsWith("- ")` —— 指令段落里也有 `- Identify …` 这样的条目，
         * 松匹配会让这条对账测试在模板改版后悄悄放过漂移。
         */
        val CODE_LINE = Regex("^- [a-z]{2,4}: .+$")
    }
}
