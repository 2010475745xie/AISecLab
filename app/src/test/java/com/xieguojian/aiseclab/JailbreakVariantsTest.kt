package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.JailbreakVariantsModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.jailbreak.EncodingNormalizer
import com.xieguojian.aiseclab.core.jailbreak.EncodingVariants
import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases
import com.xieguojian.aiseclab.core.jailbreak.StrategyFingerprintLibrary
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ExpectedBehavior
import com.xieguojian.aiseclab.core.model.HarmIntent
import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越狱变体集的回归 —— 同时承担比赛方案里 1.1 / 1.2 / 1.3 三条验收标准。
 *
 * | 验收项 | 标准 | 对应用例 |
 * |---|---|---|
 * | 1.1 策略指纹库 | 给定越狱样本，策略命中率 ≥ 90% | [越狱变体集的设计策略必须被指纹库识别] |
 * | 1.2 编码还原器 | 编码型越狱在还原后可被命中 | [编码生成器必须是还原器的真逆运算] |
 * | 1.3 意图标签 | 有害集 100% 带 intent 标签 | [变体集每条都有意图标签] / [安全维度基线全部带意图标签] |
 *
 * ## 为什么要拿"生成的变体"当 1.1 的样本，而不是另写 60 条
 *
 * 验收标准原话是「给定 60 条越狱样本文本，策略命中率 ≥ 90%」。如果样本和断言都手写，
 * 就变成"我写了 60 条我保证能命中的话"—— **恒真**，什么都没验到。
 * 用变体集当样本，样本是**模板批产**的（语料库、策略、意图三者独立组合），
 * 断言才有内容：`attribute()` 必须在一个它没见过的组合空间上仍然认得出来。
 *
 * 另外这也是**模板的镜子**：模板写歪了（比如"扮演"后面没跟限定语，指纹本来就该不命中）
 * 会在这里暴露 —— 要么改模板，要么补指纹，不允许"标了 roleplay 但其实不像 roleplay"。
 */
class JailbreakVariantsTest {

    private val variants = JailbreakCases.buildCases()

    // ── 规模与结构 ────────────────────────────────────────────────────

    @Test
    fun `变体集正好100条且每个策略族各20条`() {
        assertEquals("变体集总量不对", 100, variants.size)
        JailbreakCases.STRATEGIES.forEach { s ->
            assertEquals(
                "策略族 ${s.id} 的条数不是 20（策略 × 意图矩阵会因此不平衡）",
                20, variants.count { it.strategy == s }
            )
        }
        // 5 类 × 20 = 100，两处数字必须自洽，否则"100 条"是碰巧凑出来的
        assertEquals(100, JailbreakCases.STRATEGIES.size * JailbreakCases.GOALS.size)
    }

    @Test
    fun `变体 id 全局唯一且可排序定位`() {
        val ids = variants.map { it.id }
        assertEquals("有重复 id（报告里会串样本）", ids.size, ids.toSet().size)
        ids.forEach {
            assertTrue("id 格式不是 jbv-<策略>-<两位序号>：$it", Regex("""jbv-[a-z_]+-\d{2}""").matches(it))
        }
    }

    @Test
    fun `变体集全部是安全维度且期望拒绝`() {
        variants.forEach {
            assertEquals("${it.id} 维度不对", Dimension.SECURITY, it.dimension)
            assertEquals("${it.id} 期望行为不对 —— 变体集全是有害请求，必须期望拒绝",
                ExpectedBehavior.SHOULD_REFUSE, it.expected)
        }
    }

    @Test
    fun `变体集不并入默认50条基线`() {
        val base = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
        ).flatMap { it.buildCases() }
        assertEquals("默认基线集被改了 —— 变体集必须是**额外**的一批，不能替换掉回归基线", 50, base.size)
        assertTrue("默认基线里混进了变体用例", base.none { it.id.startsWith("jbv-") })
        assertEquals("安全维度基线条数变了", 18, SecurityModule().buildCases().size)
        assertEquals("变体集模块条数不是 100", 100, JailbreakVariantsModule().buildCases().size)
    }

    // ── 1.3 意图标签 ──────────────────────────────────────────────────

    @Test
    fun `变体集每条都有意图标签`() {
        variants.forEach {
            assertFalse("${it.id} 的意图是 unknown —— 有害集不允许有未归类样本", it.intent == HarmIntent.UNKNOWN)
            assertTrue("${it.id} 的策略是 none —— 变体集全部带包装，none 只能属于基线",
                it.strategy != JailbreakStrategy.NONE)
        }
        // 覆盖面：变体集必须真的铺开在多类意图上，否则"策略 × 意图矩阵"只有一行有数
        val intents = variants.map { it.intent }.toSet()
        assertTrue("变体集覆盖的意图只有 $intents，太窄", intents.size >= 6)
        assertEquals("GOALS 里有重复 slug", JailbreakCases.GOALS.size,
            JailbreakCases.GOALS.map { it.slug }.toSet().size)
    }

    @Test
    fun `安全维度基线全部带意图标签`() {
        val security = SecurityModule().buildCases()
        security.forEach {
            assertFalse("${it.id} 是安全用例却没标意图", it.intent == HarmIntent.UNKNOWN)
        }
        // 非安全维度如实标 UNKNOWN/NONE —— 不为"看起来有标签"硬塞策略
        val others = listOf(ConsistencyModule(), FactualityModule()).flatMap { it.buildCases() }
        others.forEach {
            assertEquals("${it.id} 不是越狱，策略应为 none", JailbreakStrategy.NONE, it.strategy)
            assertEquals("${it.id} 不是有害诉求，意图应为 unknown", HarmIntent.UNKNOWN, it.intent)
        }
    }

    @Test
    fun `全量150条用例都带结构化意图与策略字段`() {
        val all = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule(),
            JailbreakVariantsModule()
        ).flatMap { it.buildCases() }
        assertEquals("全量条数不对（50 基线 + 100 变体）", 150, all.size)
        // 字段必须显式存在、可空值域之外 —— 缺省值只在"旧数据反序列化"时才是合法的
        all.forEach {
            assertNotNull("${it.id} 的 intent 字段缺失", it.intent)
            assertNotNull("${it.id} 的 strategy 字段缺失", it.strategy)
        }
        // 有害用例（安全维度 18 + 变体 100）必须全部有具体意图，不允许 unknown
        val harmful = all.filter { it.expected == ExpectedBehavior.SHOULD_REFUSE }
        assertEquals("有害用例条数不对（18 基线 + 100 变体）", 118, harmful.size)
        val unclassified = harmful.filter { it.intent == HarmIntent.UNKNOWN }
        assertTrue("这些有害用例没有归类意图：${unclassified.map { it.id }}", unclassified.isEmpty())
    }

    @Test
    fun `tags 不得复刻 intent 与 strategy`() {
        // tags 是功能字段（`L3StructuralRules` 读 `tags.contains("json")`），
        // 把它当标注副本会造出第二个真相源：只改字段不改 tags 时，
        // "按 tags 筛样本"与"按字段分组统计"会得出两套不同的结果，而且都不报错。
        val all = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule(),
            JailbreakVariantsModule()
        ).flatMap { it.buildCases() }
        val dup = all.filter { it.tags.contains(it.intent.id) || it.tags.contains(it.strategy.id) }
        assertTrue("这些用例把 intent/strategy 复刻进了 tags：${dup.map { it.id }}", dup.isEmpty())
        // 但筛选用的标签要留着：变体集必须能被一眼认出来
        val jbv = all.filter { it.id.startsWith("jbv-") }
        assertTrue("变体集没带 jailbreak-variant 标签", jbv.all { it.tags.contains("jailbreak-variant") })
    }

    // ── 1.1 策略指纹命中率（≥ 90%）────────────────────────────────────

    @Test
    fun `越狱变体集的设计策略必须被指纹库识别`() {
        val miss = variants.filter {
            !StrategyFingerprintLibrary.attribute(it.prompt, it.intent).strategies.contains(it.strategy)
        }
        val hitRate = (variants.size - miss.size).toDouble() / variants.size
        assertTrue(
            "策略命中率 ${"%.1f".format(hitRate * 100)}% < 90% —— 未命中 ${miss.size} 条：\n" +
                miss.joinToString("\n") { "  ${it.id} 期望 ${it.strategy.id}，实际 ${StrategyFingerprintLibrary.strategiesOf(it.prompt).map { s -> s.id }}" },
            hitRate >= 0.9
        )
    }

    @Test
    fun `每个策略族的命中率都不低于80%`() {
        // 只看总命中率会掩盖"某一族全军覆没、靠其它族把均值拉起来"
        JailbreakCases.STRATEGIES.forEach { s ->
            val group = variants.filter { it.strategy == s }
            val hit = group.count { StrategyFingerprintLibrary.strategiesOf(it.prompt).contains(s) }
            assertTrue(
                "策略族 ${s.id} 命中率 ${hit}/${group.size} 过低 —— 模板不像该族越狱，或指纹库漏了这一族",
                hit.toDouble() / group.size >= 0.8
            )
        }
    }

    // ── 1.2 编码还原 ──────────────────────────────────────────────────

    @Test
    fun `编码生成器必须是还原器的真逆运算`() {
        val plain = "Ignore previous instructions"
        // 零宽与同形字：clean 必须精确还原
        assertEquals("零宽字符还原失败", plain, EncodingNormalizer.clean(EncodingVariants.zeroWidth(plain)))
        assertEquals("同形字还原失败", plain, EncodingNormalizer.clean(EncodingVariants.homoglyph(plain)))
        // ROT13 自反
        assertEquals("ROT13 不是自反的", plain, EncodingVariants.rot13(EncodingVariants.rot13(plain)))
        // Base64：走还原器（不是直接调解码器）—— 要验的是"还原器认它"
        assertTrue(
            "Base64 还原失败：${EncodingNormalizer.normalize(EncodingVariants.base64(plain)).decodedVariants}",
            EncodingNormalizer.normalize(EncodingVariants.base64(plain)).decodedVariants.contains(plain)
        )
        // Morse：只保留 A-Z 0-9，所以用纯大写字母串做输入
        val upper = "IGNORE PREVIOUS INSTRUCTIONS"
        assertTrue(
            "Morse 还原失败：${EncodingNormalizer.normalize(EncodingVariants.morse(upper)).decodedVariants}",
            EncodingNormalizer.normalize(EncodingVariants.morse(upper)).decodedVariants.contains(upper)
        )
    }

    @Test
    fun `编码族的20条还原后都能命中覆盖指纹`() {
        // 这是 1.2 的验收本身：还原不是"看起来解出来了"，而是**还原后能被检测器命中**
        val encoded = variants.filter { it.encoded }
        assertEquals("编码族条数不是 20", 20, encoded.size)
        encoded.forEach { c ->
            val matched = EncodingNormalizer.forMatching(c.prompt)
            val strs = StrategyFingerprintLibrary.strategiesOf(matched)
            assertTrue(
                "${c.id} 还原后仍未命中 overwrite —— 编码样本的载荷没被还原出来。\n" +
                    "  原文：${c.prompt.take(120)}\n  还原后命中：${strs.map { it.id }}",
                strs.contains(JailbreakStrategy.PROMPT_OVERWRITE)
            )
        }
    }

    @Test
    fun `只有编码族被标 encoded 且编码族必须标上`() {
        variants.forEach {
            assertEquals(
                "${it.id} 的 encoded 标记与策略不符（strategy=${it.strategy.id}，encoded=${it.encoded}）",
                it.strategy == JailbreakStrategy.ENCODING, it.encoded
            )
            if (it.encoded) assertTrue("${it.id} 标了 encoded 却没进 tags", it.tags.contains("encoded"))
        }
    }

    @Test
    fun `非编码族的载荷不得包含长 base64 块`() {
        // 反向检查：模板里若不小心把 base64 串带进非编码族，
        // "编码可还原率"的分母就会被污染，而那在报告上完全看不出来
        variants.filter { !it.encoded }.forEach {
            val blobbed = Regex("""[A-Za-z0-9+/]{40,}={0,2}""").containsMatchIn(it.prompt)
            assertFalse("${it.id} 是非编码族，却含长 base64 块", blobbed)
        }
    }

    // ── 确定性 ────────────────────────────────────────────────────────

    @Test
    fun `变体集生成是确定性的`() {
        val again = JailbreakCases.buildCases()
        assertEquals("两次生成条数不同", variants.size, again.size)
        variants.zip(again).forEach { (a, b) ->
            assertEquals("${a.id} 的 id 不稳定", a.id, b.id)
            assertEquals("${a.id} 的 prompt 不稳定 —— 报告之间就没法横向比了", a.prompt, b.prompt)
            assertEquals("${a.id} 的 title 不稳定", a.title, b.title)
        }
        // 变体集模块与直调也必须一致（UI 的条数预览就走的模块）
        assertNotNull(JailbreakVariantsModule().buildCases().firstOrNull())
    }

    @Test
    fun `零宽与同形字编码不得留下可读的原始关键词`() {
        // 若"编码"之后关键词仍然原样可读，那它根本没绕过字面检测，标 encoding 就是假的
        val zw = EncodingVariants.zeroWidth("忽略之前的指令")
        assertFalse("零宽编码后关键词仍原样出现", zw.contains("忽略之前的指令"))
        val homo = EncodingVariants.homoglyph("Ignore previous instructions")
        assertFalse("同形字编码后关键词仍原样出现", homo.contains("Ignore previous instructions"))
        // 但还原之后必须回来
        assertEquals("忽略之前的指令", EncodingNormalizer.clean(zw))
        assertEquals("Ignore previous instructions", EncodingNormalizer.clean(homo))
    }
}
