package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.jailbreak.StrategyFingerprintLibrary
import com.xieguojian.aiseclab.core.model.StrategyFingerprint
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 把端上策略指纹库 dump 成 JSON，供 `tools/check-jailbreak-parity.py` 与后端逐条比对。
 *
 * 为什么不用"解析 Kotlin 源码"的方式比对：指纹里的正则**本身包含引号**
 * （例如前缀注入那两条 `[「\"']`），正则表达式抽字符串字面量必然在这里断掉，
 * 一旦断掉就会得出"两边不一致"的**假警报** —— 比不校验更糟。
 * 让端上自己把结构化结果写出来，比对的是**真正的数据**，不是文本形状。
 *
 * 默认不跑（没有 `-Djailbreak.parity.out` 时 assume 失败 → 记为跳过），
 * 所以日常 `python tools/run-unit-tests.py` 不受影响。
 */
class JailbreakParityDumpTest {

    private val json = Json {
        prettyPrint = true
        // 必须显式开启：weight 有默认值，不编码默认值的话这里少个字段、
        // 比对脚本按默认值补上，看起来"一致"但实际上没验到。
        encodeDefaults = true
    }

    @Test
    fun `dump 策略指纹库供两端比对`() {
        val out = System.getProperty("jailbreak.parity.out")
        assumeTrue("未指定 -Djailbreak.parity.out，跳过", !out.isNullOrBlank())
        val dir = File(out!!)
        dir.parentFile?.mkdirs()

        val fps = StrategyFingerprintLibrary.ALL
        dir.writeText(
            json.encodeToString(ListSerializer(StrategyFingerprint.serializer()), fps),
            Charsets.UTF_8
        )

        // 自查：dump 出来的必须是完整的 —— 空数组会让比对"两边都为空"而假通过。
        check(fps.isNotEmpty()) { "指纹库为空，dump 无意义" }
        fps.forEach { f ->
            check(f.keywords.isNotEmpty() || f.patterns.isNotEmpty()) {
                "指纹 ${f.label} 既没有 keywords 也没有 patterns"
            }
        }
        println("dump ${fps.size} 条指纹 → ${dir.absolutePath}")
    }
}
