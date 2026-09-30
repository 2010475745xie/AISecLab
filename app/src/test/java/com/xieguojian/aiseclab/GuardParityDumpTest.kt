package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.judge.guard.XGuardPrompt
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 把 [GuardParityCases] 渲染成端上 CLI 直接能吃的输入文件。
 *
 * 落盘内容（目录由 `-Dxguard.parity.dir=...` 指定）：
 * - `prompts.bin`：多条 prompt，用 0x1E 分隔，UTF-8。直接 push 给 `xguard_cli`。
 * - `cases.json`：样本清单（名字 + 原文），供驱动脚本回放 PC 侧 HTTP、供比对测试读名字。
 *
 * ## 为什么渲染必须在 Kotlin 里做
 * 模板只允许有一份实现（[XGuardPrompt]）。如果这里的 prompt 由脚本按 JSON 片段拼一遍，
 * 那"端上输入的模板"和"PC 输入的模板"就存在两个来源，一旦漂移，
 * 端上验收会拿一个错输入跑出"结论不一致"，反而把真正的实现问题盖掉。
 *
 * 默认不跑（没有 `-Dxguard.parity.dir` 时 assume 失败 → 记为跳过），
 * 所以正常的 `python tools/run-unit-tests.py` 不受影响。
 */
class GuardParityDumpTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `渲染一致性验收输入`() {
        val dirProp = System.getProperty("xguard.parity.dir")
        assumeTrue("未指定 -Dxguard.parity.dir，跳过", !dirProp.isNullOrBlank())
        val dir = File(dirProp!!)
        dir.mkdirs()

        val cases = GuardParityCases.CASES
        println("渲染 ${cases.size} 条一致性样本 → ${dir.absolutePath}")

        // 0x1E = ASCII RS。用 RS 而不是换行：prompt 本身含大量换行。
        val sb = StringBuilder()
        cases.forEachIndexed { i, c ->
            if (i > 0) sb.append('\u001e')
            sb.append(XGuardPrompt.render(c.query, c.response))
        }
        File(dir, "prompts.bin").writeText(sb.toString(), Charsets.UTF_8)

        File(dir, "cases.json").writeText(
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GuardParityCases.Case.serializer()),
                cases
            ),
            Charsets.UTF_8
        )

        // 自查：渲染结果必须含模板骨架，且以 suffix 收尾 —— 否则端上拿到的是废输入，
        // 跑出来的"不一致"是假警报，比不验还糟。
        val rendered = File(dir, "prompts.bin").readText(Charsets.UTF_8).split('\u001e')
        check(rendered.size == cases.size) { "记录数不符：${rendered.size} != ${cases.size}" }
        rendered.forEachIndexed { i, r ->
            check(r.contains("<|im_start|>assistant")) { "第 $i 条缺少 assistant 起头" }
            check(r.endsWith("</think>\n\n")) {
                "第 $i 条结尾不是模板 suffix（末尾 20 字：${r.takeLast(20)}）"
            }
        }

        println("  prompts.bin = ${File(dir, "prompts.bin").length()} 字节")
        println("  cases.json  = ${File(dir, "cases.json").length()} 字节")
    }
}
