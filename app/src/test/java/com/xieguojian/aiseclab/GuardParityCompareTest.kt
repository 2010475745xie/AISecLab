package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.judge.guard.LlamaCppGuard
import com.xieguojian.aiseclab.core.judge.guard.XGuardParser
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * **端上判定 == PC 判定** 的验收。
 *
 * 输入由外部准备（见 `tools/verify-ondevice.py`）：
 * - `cases.json`：样本清单（只用名字）
 * - `pc.json`：同样输入打到 **PC 侧 llama-server** 拿到的原始响应体数组（按序）
 * - `device.jsonl`：`xguard_cli` 在**真机**上跑同一批 prompt 输出的一行一个 JSON（按序）
 *
 * ## 判据（这是整个端上方案的守门条件）
 * 1. **标签必须完全相同** —— 标签是 argmax 风险代号，代表"模型认为这是什么风险"。
 *    这条路一旦不一致，说明两边的权重/词表/模板有一边不对，是整个方案失效的信号。
 * 2. **结论（风险 >= 0.5 与否）必须相同** —— 报告里给人看的 PASS/FAIL 就是它。
 * 3. **风险度差值有上界** —— PC 走 x86 AVX、手机走 ARM NEON，浮点与 kernel 都不同，
 *    数值必然有小差；但差到能翻转结论就是 bug。这里给了 0.15 的上界做烟雾线
 *    （实测远小于它），**不追求逐位一致** —— 那是不现实的目标，写了只会被忽略。
 * 4. **prompt token 数必须完全相同** —— 同一份词表 + 同一份渲染，端上与 PC 应该逐
 *    token 对齐。这条如果挂了，几乎一定是模板或 tokenizer 配置漂移，而不是数值噪音。
 *
 * 默认跳过（没传 `-Dxguard.parity.dir` 时 assume 失败），不影响常规单测。
 */
class GuardParityCompareTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(
        val name: String,
        val pcLabel: String,
        val devLabel: String,
        val pcRisk: Float?,
        val devRisk: Float?,
        val pcTokens: Int,
        val devTokens: Int
    ) {
        val labelOk get() = pcLabel == devLabel
        val verdictOk get() = (pcRisk ?: 0f >= 0.5f) == (devRisk ?: 0f >= 0.5f)
        val riskOk get() = abs((pcRisk ?: 0f) - (devRisk ?: 0f)) <= RISK_TOLERANCE
        val tokensOk get() = pcTokens == devTokens

        companion object {
            /**
             * 危险度差值上界。PC 走 x86 AVX、手机走 ARM NEON，浮点与 kernel 都不同，
             * 数值必然有小差。这条是"差到能翻转结论就是 bug"的烟雾线，实测远小于它。
             * 追求逐位一致是不现实的目标，写了只会被忽略。
             */
            const val RISK_TOLERANCE = 0.15f
        }
    }

    @Test
    fun `端上判定与 PC 判定一致`() {
        val dirProp = System.getProperty("xguard.parity.dir")
        assumeTrue("未指定 -Dxguard.parity.dir，跳过", !dirProp.isNullOrBlank())
        val dir = File(dirProp!!)

        val casesFile = File(dir, "cases.json")
        val pcFile = File(dir, "pc.json")
        val devFile = File(dir, "device.jsonl")

        assumeTrue("缺少 ${casesFile.absolutePath}（先跑 GuardParityDumpTest）", casesFile.isFile)
        assumeTrue("缺少 ${pcFile.absolutePath}（PC 侧响应未采集）", pcFile.isFile)
        assumeTrue("缺少 ${devFile.absolutePath}（端上 CLI 未运行或未回传）", devFile.isFile)

        val names = json
            .decodeFromString(ListSerializer(GuardParityCases.Case.serializer()), casesFile.readText(Charsets.UTF_8))
            .map { it.name }
        val pcBodies = json
            .decodeFromString(ListSerializer(String.serializer()), pcFile.readText(Charsets.UTF_8))
        val devLines = devFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals("PC 响应条数与样本数不符", names.size, pcBodies.size)
        assertEquals("端上输出条数与样本数不符", names.size, devLines.size)

        val rows = names.indices.map { i -> judgeOne(names[i], pcBodies[i], devLines[i]) }

        println()
        println("=" .repeat(96))
        println("端上 vs PC 判定比对（xguard_cli on device / llama-server on PC）")
        println("=" .repeat(96))
        println(
            "%-22s %-8s %-8s %-10s %-10s %-7s %-7s".format(
                "样本", "PC标签", "端标签", "PC危险度", "端危险度", "PC tok", "端 tok"
            )
        )
        println("-".repeat(96))
        rows.forEach { r ->
            println(
                "%-22s %-8s %-8s %-10s %-10s %-7d %-7d".format(
                    r.name, r.pcLabel, r.devLabel,
                    r.pcRisk?.let { "%.4f".format(it) } ?: "null",
                    r.devRisk?.let { "%.4f".format(it) } ?: "null",
                    r.pcTokens, r.devTokens
                )
            )
        }
        println("-".repeat(96))
        println(
            "标签一致 ${rows.count { it.labelOk }}/${rows.size}｜" +
                "结论一致 ${rows.count { it.verdictOk }}/${rows.size}｜" +
                "token 一致 ${rows.count { it.tokensOk }}/${rows.size}｜" +
                "危险度差最大 %.4f".format(rows.maxOfOrNull { abs((it.pcRisk ?: 0f) - (it.devRisk ?: 0f)) } ?: 0f)
        )
        println("=" .repeat(96))
        println()

        // ---- 断言 ----
        rows.forEach { r ->
            assertEquals("「${r.name}」标签不一致：PC=${r.pcLabel} 端上=${r.devLabel}", r.pcLabel, r.devLabel)
            assertTrue(
                "「${r.name}」结论不一致：PC危险度=${r.pcRisk} 端上危险度=${r.devRisk}",
                r.verdictOk
            )
            assertTrue(
                "「${r.name}」危险度差超出容差：PC=${r.pcRisk} 端上=${r.devRisk}",
                r.riskOk
            )
            assertEquals(
                "「${r.name}」prompt token 数不一致（模板/tokenizer 漂移的强信号）",
                r.pcTokens, r.devTokens
            )
        }

        // 覆盖度自查：样本必须既包含风险项也包含安全项。
        // 否则"全判安全"也能让上面四条断言全过 —— 那种验收是假的。
        val risky = rows.count { (it.pcRisk ?: 0f) >= 0.5f }
        assertTrue("样本里没有风险项，本验收无意义", risky > 0)
        assertTrue("样本里没有安全项，本验收无意义", risky < rows.size)
    }

    private fun judgeOne(name: String, pcBody: String, devLine: String): Row {
        // ---- PC 路径：走 App 里真实的解析实现 ----
        val completion = OpenAiCompatibleClient.parseCompletion(pcBody)
        assertNotNull("PC 响应无法解析：$name", completion)
        val pcVerdict = XGuardParser.parse(completion!!.content, completion.firstTopTokens)

        // prompt_tokens 从原始体里取（Completion 没暴露 usage；报告也不需要它）
        val pcTokens = runCatching {
            json.parseToJsonElement(pcBody).jsonObject["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.int
        }.getOrDefault(-1)

        // ---- 端上路径：native 输出经生产解码器 → 同一份 XGuardParser ----
        val devRaw = LlamaCppGuard.parseResult(devLine.toByteArray(Charsets.UTF_8))
        assertNotNull("端上输出不是合法 JSON（$name）：${devLine.take(180)}", devRaw)
        assertNull("端上推理报错（$name）：${devRaw!!.error}", devRaw.error)
        val devVerdict = XGuardParser.parse(devRaw.text, devRaw.top.map { it.token to it.p })

        // 端上必须能给出标签；给不出说明端上链路坏了，不能让"两边都为 null"混过去
        assertNotNull(
            "端上无法判定（$name）：text=${devRaw.text.take(80)} err=${devVerdict.error}",
            devVerdict.labelId
        )
        assertNotNull(
            "PC 无法判定（$name）：content=${completion.content.take(80)} err=${pcVerdict.error}",
            pcVerdict.labelId
        )

        return Row(
            name = name,
            pcLabel = pcVerdict.labelId!!,
            devLabel = devVerdict.labelId!!,
            pcRisk = pcVerdict.risk,
            devRisk = devVerdict.risk,
            pcTokens = pcTokens,
            devTokens = devRaw.promptTokens
        )
    }
}
