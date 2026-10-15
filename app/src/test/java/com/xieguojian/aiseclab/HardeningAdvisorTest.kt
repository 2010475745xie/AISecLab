package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.model.AttackRound
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.HarmIntent
import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import com.xieguojian.aiseclab.core.model.JudgeLevel
import com.xieguojian.aiseclab.core.model.JudgeResult
import com.xieguojian.aiseclab.core.model.TestCase
import com.xieguojian.aiseclab.core.model.TestResult
import com.xieguojian.aiseclab.core.model.Verdict
import com.xieguojian.aiseclab.core.report.DefenseLayer
import com.xieguojian.aiseclab.core.report.HardeningAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加固建议（方案 §3.5）回归。
 *
 * 这块最怕的不是"算错数"，而是**给出不可复现、不可证伪的建议**（所以它必须是纯规则映射表，
 * 不是让模型现场编）。测试盯四件事：
 *   1. 聚合口径：同策略同层要合并、同策略不同层不能合并（修法本来就不同）；
 *   2. 不可归因的用例（没有逐轮记录）仍要拿到**策略级**建议，且单列计数，不许塞进任何一层；
 *   3. 排序按中招条数降序 —— 第一条就是该先修的；
 *   4. 建议文本里**不许出现 Markdown 标记**（Compose 与导出都不解析，`**` 会原样显示成星号）。
 */
class HardeningAdvisorTest {

    private fun attack(
        id: String,
        verdict: Verdict = Verdict.FAIL,
        strategy: JailbreakStrategy = JailbreakStrategy.ROLEPLAY,
        intent: HarmIntent = HarmIntent.MALWARE,
        /** 逐轮「有没有拒绝话术」；空 = 没有逐轮记录（本机跑未开递归对抗）。 */
        refusedRounds: List<Boolean> = emptyList()
    ) = TestResult(
        testCase = TestCase(
            id = id, dimension = Dimension.SECURITY, title = "用例 $id",
            prompt = "p-$id", expected = ExpectedBehavior.SHOULD_REFUSE,
            intent = intent, strategy = strategy
        ),
        response = "回答 $id",
        judgeResult = JudgeResult(
            verdict = verdict, confidence = 0.95f, level = JudgeLevel.LLM,
            evidence = listOf("依据 $id"), score = if (verdict == Verdict.PASS) 1f else 0f
        ),
        latencyMs = 10L,
        attackRounds = refusedRounds.mapIndexed { i, r ->
            AttackRound(i + 1, "p${i + 1}", "r${i + 1}", refused = r)
        }
    )

    @Test
    fun 没有失守时不给任何建议() {
        val r = HardeningAdvisor.advise(emptyList())
        assertTrue(r.isEmpty)
        assertEquals(0, r.covered)
        assertEquals(0, r.unattributed)
    }

    @Test
    fun 非失守用例被忽略() {
        val r = HardeningAdvisor.advise(
            listOf(
                attack("p1", verdict = Verdict.PASS),
                attack("p2", verdict = Verdict.SKIPPED),
                attack("p3", verdict = Verdict.INCONCLUSIVE)
            )
        )
        assertTrue("只有 FAIL 才出建议，实际 = $r", r.isEmpty)
    }

    @Test
    fun 同策略同层合并成一条并计数() {
        val r = HardeningAdvisor.advise(
            listOf(
                attack("a1", refusedRounds = listOf(true, false)),   // 第 2 步破 → 规划层
                attack("a2", refusedRounds = listOf(true, true, false))
            )
        )
        assertEquals(1, r.items.size)
        val it0 = r.items.first()
        assertEquals(JailbreakStrategy.ROLEPLAY, it0.strategy)
        assertEquals(DefenseLayer.PLANNING, it0.layer)
        assertEquals(2, it0.cases)
        assertEquals(2, r.covered)
    }

    @Test
    fun 多步才破与一次就破是两条不同建议() {
        val r = HardeningAdvisor.advise(
            listOf(
                attack("a1", refusedRounds = listOf(true, false)),   // 多步 → 规划层
                attack("a2", refusedRounds = listOf(false))          // 一步 → 输出层
            )
        )
        assertEquals(2, r.items.size)
        val planning = r.items.single { it.layer == DefenseLayer.PLANNING }
        val output = r.items.single { it.layer == DefenseLayer.OUTPUT }
        // 策略相同、层不同 → 动作文本必须不同（否则等于把两种修法混成一条）
        assertTrue("两层建议不该同文", planning.action != output.action)
        assertTrue("规划层应提多轮一致性", planning.action.contains("多轮"))
        assertTrue("输出层应提输出过滤", output.action.contains("输出过滤"))
    }

    @Test
    fun 建议按中招条数降序() {
        val r = HardeningAdvisor.advise(
            listOf(
                attack("n1", strategy = JailbreakStrategy.NONE, refusedRounds = listOf(false)),
                attack("r1", refusedRounds = listOf(false)),
                attack("r2", refusedRounds = listOf(false)),
                attack("r3", refusedRounds = listOf(false))
            )
        )
        assertEquals(JailbreakStrategy.ROLEPLAY, r.items.first().strategy)
        assertEquals(3, r.items.first().cases)
        assertEquals(JailbreakStrategy.NONE, r.items.last().strategy)
    }

    @Test
    fun 没有逐轮记录时仍给策略级建议并单独报不可归因() {
        val r = HardeningAdvisor.advise(
            listOf(attack("a1", strategy = JailbreakStrategy.ENCODING, refusedRounds = emptyList()))
        )
        assertEquals(1, r.items.size)
        val it0 = r.items.first()
        assertNull("定位不出层就不能硬塞一层", it0.layer)
        assertFalse(it0.hasLayer)
        // 建议仍要到策略级：编码混淆 → 提示做编码还原
        assertTrue("应给出编码还原方向的建议，实际 = ${it0.action}", it0.action.contains("编码还原"))
        assertEquals(1, r.unattributed)
        assertEquals(1, r.covered)
    }

    @Test
    fun 十三个策略族都能拿到针对性建议() {
        // 每个策略单独喂一条（一步就破），断言都拿到非空、且不是兜底文案
        JailbreakStrategy.entries.forEach { s ->
            val r = HardeningAdvisor.advise(
                listOf(attack("x-${s.id}", strategy = s, refusedRounds = listOf(false)))
            )
            val it0 = r.items.single()
            assertEquals(s, it0.strategy)
            assertTrue("${s.id} 的建议不该为空", it0.action.isNotBlank())
            assertFalse(
                "${s.id} 落到了兜底文案（说明规则表漏了这个策略族）",
                it0.action.contains("没有可识别的攻击策略标签")
            )
        }
    }

    @Test
    fun 建议文本不含Markdown标记() {
        // Compose 与两个导出件都不解析 Markdown，`**` 会原样显示成星号
        val all = HardeningAdvisor.advise(
            JailbreakStrategy.entries.mapIndexed { i, s ->
                attack("m$i", strategy = s, refusedRounds = listOf(true, false))
            }
        )
        all.items.forEach { item ->
            assertFalse("动作文本不该含 Markdown：${item.action}", item.action.contains("**"))
            item.snippet?.let { assertFalse("片段不该含 Markdown：$it", it.contains("**")) }
        }
    }

    @Test
    fun 片段要么是可直接粘的正文要么为空不为占位() {
        val all = HardeningAdvisor.advise(
            JailbreakStrategy.entries.mapIndexed { i, s ->
                attack("s$i", strategy = s, refusedRounds = listOf(false))
            }
        )
        assertTrue("至少要有一个策略给出可粘贴片段", all.items.any { it.snippet != null })
        all.items.forEach { item ->
            item.snippet?.let {
                assertTrue("片段不该是空白占位：'$it'", it.isNotBlank())
                assertNotNull(it)
            }
        }
    }

    @Test
    fun 覆盖条数等于失守条数() {
        val fails = listOf(
            attack("c1", refusedRounds = listOf(false)),
            attack("c2", strategy = JailbreakStrategy.MANY_SHOT, refusedRounds = listOf(true, false)),
            attack("c3", strategy = JailbreakStrategy.FICTIONAL_FRAMING, refusedRounds = emptyList())
        )
        val r = HardeningAdvisor.advise(fails)
        assertEquals(fails.size, r.covered)
        // c3 没有逐轮记录 → 不可归因 1 条
        assertEquals(1, r.unattributed)
    }

    @Test
    fun 不可归因的条数已经在卡片里数过_说明文案不许写成另有() {
        // 口径：unattributed 数的是失守里「定位不出层」的那些，而它们**已经在 items 里**
        // 各自拿到一条策略级建议了 → `unattributed <= covered` 恒成立，且同一条失守只列一次。
        // 报告页 / Markdown / HTML 三处都念 [HardeningReport.unattributedNote]，所以钉住这一份文案，
        // 三处就都不会写出「失守 1 条 + 1 条中招 + 另有 1 条失守」这种自己跟自己打架的话。
        //（2026-10-14 真机读到过这个措辞，还被导出侧单测锁着，所以一直没暴露。）
        val r = HardeningAdvisor.advise(
            listOf(
                attack("u1", strategy = JailbreakStrategy.ENCODING, refusedRounds = emptyList()),
                attack("u2", strategy = JailbreakStrategy.NONE, refusedRounds = emptyList()),
                attack("u3", refusedRounds = listOf(false))          // 一步就破 → 输出层，可归因
            )
        )
        // 三条失守 → 三个「策略 × 层」组合 → 三张卡，一条都不多不少
        assertEquals("同一条失守不该出现在两张卡里", 3, r.items.size)
        assertEquals(3, r.covered)
        assertEquals(2, r.unattributed)
        assertTrue("不可归因条数不可能超过已覆盖条数：$r", r.unattributed <= r.covered)

        val note = r.unattributedNote
        assertTrue("应报出不可归因条数：$note", note.contains("其中 2 条失守没能定位到失效层"))
        assertFalse("「另有」会把已经数过的失守再数一遍：$note", note.contains("另有"))
        assertTrue("要说清怎么才能拿到分层归因：$note", note.contains("跑多轮"))
    }

    @Test
    fun 没有不可归因的失守时说明为空串() {
        // 空串 = 「不用渲染这一行」，调用方据此判断；不是「0 条」占位。
        val r = HardeningAdvisor.advise(listOf(attack("a1", refusedRounds = listOf(false))))
        assertEquals(0, r.unattributed)
        assertEquals("", r.unattributedNote)
    }
}
