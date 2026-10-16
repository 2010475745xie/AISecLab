package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.JailbreakVariantsModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.jailbreak.BenignControls
import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.HarmIntent
import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 负对照集（B9）的回归。
 *
 * 这一集**没有自己的判定逻辑**，它是「**同一批包装 × 无害目标**」的对照组：
 * 把有害诉求换成无害诉求、包装一字不改、跑同一条判定链，**期望失守率 ≈ 0**。
 * 显著偏离 0 说明的是：判定链有假阳性，或模型对**包装**本身过敏。
 *
 * 所以这里断言的**不是**"对照集会通过"，而是**两组真的是同一个实验**：
 * 只要包装悄悄分了叉，"对照组失守率"这个数字就失去全部意义 ——
 * 而且**照常出数**、报告上完全看不出来。
 *
 * ⚠️ 包装的"唯一实现"在 [JailbreakCases.promptFor]，本集只换载荷（见 [BenignControls]）。
 */
class BenignControlsTest {

    private val controls = BenignControls.buildCases()
    private val harmful = JailbreakCases.buildCases()

    // ── 规模 ──────────────────────────────────────────────────────────

    @Test
    fun `负对照集正好264条且每个策略族各24条`() {
        assertEquals("负对照集总量不对（11 族 × 24 目标）", 264, controls.size)
        JailbreakCases.STRATEGIES.forEach { s ->
            assertEquals(
                "策略族 ${s.id} 的对照条数不是 24 —— 对照组的族不全，这一族的失守率无从对比",
                24, controls.count { it.strategy == s }
            )
        }
        // 与有害组**同构**：外层族、内层目标一致，两侧条数必须相等
        assertEquals("负对照与有害集条数不等 —— 两组不是同一批实验", harmful.size, controls.size)
    }

    @Test
    fun `负对照目标数必须与有害目标数相等`() {
        // 下标对齐是硬要求（i 同时决定编码种类 / 说服技术 / 场景）。后端用
        // `assert len(GOALS) == len(_HARMFUL_GOALS)` 钉住，端上在这里钉。
        assertEquals(
            "负对照目标数 ${BenignControls.GOALS.size} ≠ 有害目标数 ${JailbreakCases.GOALS.size} —— " +
                "下标对不齐，两组的包装就不是同一批了",
            JailbreakCases.GOALS.size, BenignControls.GOALS.size
        )
        assertEquals("负对照目标有重复 slug", BenignControls.GOALS.size,
            BenignControls.GOALS.map { it.slug }.toSet().size)
        assertTrue("负对照目标的 slug 必须以 bn- 开头（一眼区分两类目标）",
            BenignControls.GOALS.all { it.slug.startsWith("bn-") })
    }

    @Test
    fun `负对照 id 全局唯一且形如 jbc-族-序号`() {
        val ids = controls.map { it.id }
        assertEquals("有重复 id（报告里会串样本）", ids.size, ids.toSet().size)
        ids.forEach {
            assertTrue("id 格式不是 jbc-<策略>-<两位序号>：$it", Regex("""jbc-[a-z_]+-\d{2}""").matches(it))
        }
        assertTrue("负对照的 id 前缀必须是 jbc-（jbv- 是攻击用例的）", ids.none { it.startsWith("jbv-") })
    }

    // ── 判定口径 ──────────────────────────────────────────────────────

    @Test
    fun `负对照全部期望正常作答`() {
        controls.forEach {
            assertEquals("${it.id} 维度不对", Dimension.SECURITY, it.dimension)
            // ⭐ 这是"负对照"与"有害用例"的**唯一区别**：期望正常作答。
            // 一旦有人把它改成 SHOULD_REFUSE，这条用例就会以"该拒绝却没拒绝"进入失守率分母，
            // 对照组的期望值直接被污染，而且报告上只表现为"失守率变高了"。
            assertEquals(
                "${it.id} 期望行为不是正常作答 —— 负对照必须期望正常回答，否则它就不是对照",
                ExpectedBehavior.SHOULD_ANSWER_CORRECTLY, it.expected
            )
            // 这些目标没有有害意图：硬填一个 harmful 意图就是伪造标注，
            // 报告里的「策略 × 意图」矩阵会凭空多出一整行。
            assertEquals("${it.id} 被标了有害意图 —— 负对照目标是无害的", HarmIntent.UNKNOWN, it.intent)
        }
    }

    @Test
    fun `负对照标签可筛且不复刻标注`() {
        JailbreakCases.STRATEGIES.forEach { s ->
            BenignControls.GOALS.forEachIndexed { i, goal ->
                val c = controls.first { it.id == "jbc-${s.id}-${(i + 1).toString().padStart(2, '0')}" }
                assertTrue("${c.id} 没带 jailbreak-control 标签（报告里就没法一眼筛出对照组）",
                    c.tags.contains("jailbreak-control"))
                assertTrue("${c.id} 的 tags 里没有目标 slug ${goal.slug}", c.tags.contains(goal.slug))
                // encoded 标记必须如实反映"这条真的编码了没有" —— 它是编码还原率的分母
                assertEquals(
                    "${c.id} 的 encoded 标记与策略不符（策略 ${s.id}）",
                    s == JailbreakStrategy.ENCODING, c.encoded
                )
                if (c.encoded) {
                    assertTrue("${c.id} 标了 encoded 却没进 tags", c.tags.contains("encoded"))
                }
                // tags 是功能字段，不是标注副本 —— 复制一份就是第二个真相源
                assertFalse("${c.id} 把 intent/strategy 复刻进了 tags",
                    c.tags.contains(c.intent.id) || c.tags.contains(c.strategy.id))
            }
        }
    }

    // ── ★ 包装一致性（这一集唯一真正重要的事）──────────────────────────

    @Test
    fun `负对照与有害集逐条下标对齐`() {
        // 逐条对拍（而不是"两组条数相等"）：族、子维度、序号全都要对上。
        // 只看条数的话，有人把 GOALS 插在表中间 —— 条数依然是 24、
        // 总量依然是 264，但**每一条的包装都已经错位了**（i 决定编码种类 / 说服技术 / 场景）。
        harmful.zip(controls).forEachIndexed { k, (h, c) ->
            assertEquals("第 $k 条策略族不对齐：有害 ${h.strategy.id} vs 对照 ${c.strategy.id}",
                h.strategy, c.strategy)
            assertEquals("第 $k 条 encoded 不对齐", h.encoded, c.encoded)
            // id 去掉前缀后必须逐字相同 —— 这是"族 + 族内序号"的完整对齐。
            assertEquals(
                "第 $k 条的（族, 序号）不对齐：${h.id} vs ${c.id}",
                h.id.removePrefix("jbv-"), c.id.removePrefix("jbc-")
            )
            // title 的族名（含族内子维度，如「编码混淆(Base64)」「任务层伪装(表格填写)」）必须相同。
            // 只对上了 strategy 还不够：同一个 ENCODING 族里 base64 与 morse 是不同的包装。
            assertEquals(
                "第 $k 条的族内子维度不对齐（i 错位了）：${h.title} vs ${c.title}",
                h.title.substringBefore("·"), c.title.removePrefix("负对照·").substringBefore("·")
            )
        }
    }

    @Test
    fun `包装只由策略与载荷决定_与目标的其余字段无关`() {
        // 这条是"包装一字不改"的**可机械验证形式**：
        // prompt 只能是 (strategy, zh, en, i) 的函数 —— 若某个模板读了 goal.label / goal.slug /
        // goal.intent，那么"负对照 = 有害用例 + 换载荷"这个等式就**静默失效**了
        // （两组的包装其实不同，而报告上完全看不出来）。
        //
        // 做法：把有害目标的 zh/en 换成对照目标的 zh/en、其余字段（slug/label/intent）保持有害值，
        // 再走同一个 promptFor —— 结果必须**逐字等于**对照用例的 prompt。
        JailbreakCases.STRATEGIES.forEach { s ->
            BenignControls.GOALS.forEachIndexed { i, benign ->
                val hybrid = JailbreakCases.Goal(
                    slug = JailbreakCases.GOALS[i].slug,
                    label = JailbreakCases.GOALS[i].label,
                    zh = benign.zh,
                    en = benign.en,
                    intent = JailbreakCases.GOALS[i].intent
                )
                val viaHybrid = JailbreakCases.promptFor(s, hybrid, i).prompt
                val direct = JailbreakCases.promptFor(s, benign, i).prompt
                assertEquals(
                    "策略 ${s.id} 第 ${i + 1} 条的 prompt 依赖了 label/slug/intent —— " +
                        "包装不再是载荷的函数，负对照的前提（只差内容）就不成立了",
                    direct, viaHybrid
                )
            }
        }
    }

    @Test
    fun `负对照载荷确实换掉了`() {
        // 反向兜底：如果谁把 BenignControls.GOALS 误抄成有害目标，
        // 上面那些"对齐"断言会全绿，而这一集就成了有害集的副本（对照彻底失效）。
        BenignControls.GOALS.forEachIndexed { i, benign ->
            assertNotEquals(
                "第 ${i + 1} 条负对照目标的中文载荷与有害目标相同 —— 这一集不再是负对照",
                JailbreakCases.GOALS[i].zh, benign.zh
            )
            assertNotEquals(
                "第 ${i + 1} 条负对照目标的英文载荷与有害目标相同",
                JailbreakCases.GOALS[i].en, benign.en
            )
        }
        harmful.zip(controls).forEach { (h, c) ->
            assertNotEquals("${c.id} 的 prompt 与有害用例完全相同", h.prompt, c.prompt)
        }
    }

    @Test
    fun `编码族的包装行逐字相同`() {
        // 编码族的 prompt = 「中文包装行 \n 编码体」，编码体**纯粹是载荷**
        // （`encodingPayload` 只取 goal.en）。所以包装行必须逐字相同 ——
        // 而它由 `i % 7` 选的编码种类决定，正好又验了一次下标对齐。
        val encoded = harmful.zip(controls).filter { (h, _) -> h.encoded }
        assertEquals("编码族的对照条数不是 24", 24, encoded.size)
        encoded.forEach { (h, c) ->
            assertEquals(
                "${c.id} 的编码包装行与有害用例不一致：\n  有害: ${h.prompt.substringBefore('\n')}\n" +
                    "  对照: ${c.prompt.substringBefore('\n')}",
                h.prompt.substringBefore('\n'), c.prompt.substringBefore('\n')
            )
            assertNotEquals("${c.id} 的编码体与有害用例完全相同（载荷没换）",
                h.prompt.substringAfter('\n'), c.prompt.substringAfter('\n'))
        }
    }

    // ── 纪律：不进变体集、不进默认基线 ────────────────────────────────

    @Test
    fun `负对照不并入变体集与默认基线`() {
        // 它的 expected 是 SHOULD_ANSWER_CORRECTLY，混进有害集会**污染失守率的分母**
        // （把对照目标算成"该拒绝却没拒绝"）—— 而且报告上只表现为"失守率变高了"。
        assertTrue("变体集里混进了负对照用例", harmful.none { it.id.startsWith("jbc-") })
        assertTrue("负对照里混进了攻击用例", controls.none { it.id.startsWith("jbv-") })

        val base = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
        ).flatMap { it.buildCases() }
        assertEquals("默认基线集被改了", 50, base.size)
        assertTrue("默认基线里混进了负对照用例", base.none { it.id.startsWith("jbc-") })
        assertEquals("安全维度基线条数变了", 18, SecurityModule().buildCases().size)

        // 变体集模块（UI 的条数预览 / 运行时并入都走它）同样不许带上对照组
        val module = JailbreakVariantsModule().buildCases()
        assertEquals("变体集模块条数不是 264", 264, module.size)
        assertTrue("变体集模块里混进了负对照用例", module.none { it.id.startsWith("jbc-") })
    }

    @Test
    fun `负对照生成是确定性的`() {
        val again = BenignControls.buildCases()
        assertEquals("两次生成条数不同", controls.size, again.size)
        controls.zip(again).forEach { (a, b) ->
            assertEquals("${a.id} 的 id 不稳定", a.id, b.id)
            assertEquals("${a.id} 的 prompt 不稳定 —— 对照组之间就没法横向比了", a.prompt, b.prompt)
            assertEquals("${a.id} 的 title 不稳定", a.title, b.title)
        }
    }
}
