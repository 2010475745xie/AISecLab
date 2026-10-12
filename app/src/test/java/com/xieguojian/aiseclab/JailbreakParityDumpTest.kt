package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases
import com.xieguojian.aiseclab.core.jailbreak.StrategyFingerprintLibrary
import com.xieguojian.aiseclab.core.model.StrategyFingerprint
import com.xieguojian.aiseclab.core.model.TestCase
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 把端上的「意图 × 策略」契约 dump 成 JSON，供 `tools/check-jailbreak-parity.py`
 * 与后端**逐条**比对。dump 三块：
 *
 * 1. `fingerprints` —— 策略指纹库（12 条），比对 strategy / keywords / patterns / weight；
 * 2. `staticCases` —— 50 条基线用例，比对 dimension / intent / strategy / tags；
 * 3. `variants` —— 100 条越狱变体，比对 **prompt 全文**在内的一切字段。
 *
 * 为什么不用"解析 Kotlin 源码"的方式比对：
 * - 指纹里的正则**本身包含引号**（前缀注入那两条 `[「"']`），按字符串字面量抽必然断在这里，
 *   一旦断掉就会得出"两边不一致"的**假警报** —— 比不校验更糟；
 * - 变体集的 prompt 是**模板拼出来的**，源码里根本没有那段连续文本，
 *   只有真跑一遍生成器才拿得到。
 *
 * 所以让端上自己把结构化结果写出来，比对的是**真正的数据**，不是文本形状。
 *
 * 默认不跑（没有 `-Djailbreak.parity.out` 时 assume 失败 → 记为跳过），
 * 所以日常 `python tools/run-unit-tests.py` 不受影响。
 */
class JailbreakParityDumpTest {

    private val json = Json {
        prettyPrint = true
        // 必须显式开启：weight / encoded / tags 都有默认值，不编码默认值的话这边少个字段、
        // 比对脚本按默认值补上，看起来"一致"但实际上没验到。
        encodeDefaults = true
    }

    @Test
    fun `dump 意图与策略契约供两端比对`() {
        val out = System.getProperty("jailbreak.parity.out")
        assumeTrue("未指定 -Djailbreak.parity.out，跳过", !out.isNullOrBlank())
        val file = File(out!!)
        file.parentFile?.mkdirs()

        val fps = StrategyFingerprintLibrary.ALL
        val static = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
        ).flatMap { it.buildCases() }
        val variants = JailbreakCases.buildCases()

        val payload: JsonObject = buildJsonObject {
            put("fingerprints", json.encodeToJsonElement(
                ListSerializer(StrategyFingerprint.serializer()), fps))
            put("staticCases", json.encodeToJsonElement(
                ListSerializer(TestCase.serializer()), static))
            put("variants", json.encodeToJsonElement(
                ListSerializer(TestCase.serializer()), variants))
            // 把比对口径一起写进去：脚本不该"猜"端上有多少条，
            // 否则端上少生成 50 条、脚本按自己那份比，两边都缺、逐条比全过。
            put("expectedCounts", buildJsonObject {
                put("staticCases", JsonPrimitive(static.size))
                put("variants", JsonPrimitive(variants.size))
                put("fingerprints", JsonPrimitive(fps.size))
            })
        }
        file.writeText(json.encodeToString(JsonObject.serializer(), payload), Charsets.UTF_8)

        // 自查：dump 出来的必须是完整的 —— 空数组会让比对"两边都为空"而假通过。
        check(fps.isNotEmpty()) { "指纹库为空，dump 无意义" }
        fps.forEach { f ->
            check(f.keywords.isNotEmpty() || f.patterns.isNotEmpty()) {
                "指纹 ${f.label} 既没有 keywords 也没有 patterns"
            }
        }
        check(static.size == 50) { "静态用例不是 50 条，实际 ${static.size}" }
        check(variants.size == 100) { "变体集不是 100 条，实际 ${variants.size}" }
        variants.forEach { c ->
            check(c.prompt.isNotBlank()) { "${c.id} 的 prompt 为空" }
        }
        println("dump 指纹 ${fps.size} 条 / 基线 ${static.size} 条 / 变体 ${variants.size} 条 → ${file.absolutePath}")
    }
}
