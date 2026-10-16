package com.xieguojian.aiseclab

import com.xieguojian.aiseclab.core.jailbreak.BenignControls
import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases
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
 * 把端上的**负对照集**（B9）dump 成 JSON，供 `tools/check-negative-control.py`
 * 与后端 `backend/app/benign_controls.py` **逐条**比对。
 *
 * 为什么不并进 [JailbreakParityDumpTest]：
 * - 那份 payload 已经喂给 `tools/check-jailbreak-parity.py`（绿着的），
 *   往里加一个键就改了它的输入 —— **不要去动已经验过的东西**；
 * - 两者的**比对口径**也不同：变体集比的是"两端攻击是否同一批"，
 *   负对照还要额外比"**包装是否与有害组同源**"（见脚本里的下标对齐与替换回代）。
 *
 * 默认不跑（没有 `-Djailbreak.control.out` 时 assume 失败 → 记为跳过），
 * 所以日常 `python tools/run-unit-tests.py` 不受影响。
 *
 * > 端上两组之间的包装对齐已由 [BenignControlsTest] 断言；本 dump 之后还要接上
 * > 「Kotlin 变体 ≡ Python 变体」（`check-jailbreak-parity.py`）与
 * > 「Python 对照 ≡ Python 变体 + 换载荷」，三件事合起来才得出
 * > **「Kotlin 对照 ≡ Kotlin 变体 + 换载荷」** —— 这就是负对照的全部前提。
 */
class BenignControlParityDumpTest {

    private val json = Json {
        prettyPrint = true
        // 必须显式开启：encoded / tags 都有默认值，不编码默认值的话这边少个字段、
        // 比对脚本按默认值补上，看起来"一致"但实际上没验到。
        encodeDefaults = true
    }

    @Test
    fun `dump 负对照集供两端比对`() {
        val out = System.getProperty("jailbreak.control.out")
        assumeTrue("未指定 -Djailbreak.control.out，跳过", !out.isNullOrBlank())
        val file = File(out!!)
        file.parentFile?.mkdirs()

        val controls = BenignControls.buildCases()

        val payload: JsonObject = buildJsonObject {
            put("controls", json.encodeToJsonElement(
                ListSerializer(TestCase.serializer()), controls))
            // 把口径一起写进去：脚本不该"猜"端上有多少条，
            // 否则端上少生成一批、脚本按自己那份比，两边都缺、逐条比全过。
            put("expectedCounts", buildJsonObject {
                put("controls", JsonPrimitive(controls.size))
                // 有害组的目标数也要写进去：下标对齐是负对照的硬前提，
                // 而"对齐"这件事只有在**两边数量相等**时才有意义。
                put("harmfulGoals", JsonPrimitive(JailbreakCases.GOALS.size))
            })
        }
        file.writeText(json.encodeToString(JsonObject.serializer(), payload), Charsets.UTF_8)

        // 自查：dump 出来的必须是完整的 —— 空数组会让比对"两边都为空"而假通过。
        val expected = JailbreakCases.STRATEGIES.size * BenignControls.GOALS.size
        check(controls.size == expected) { "负对照不是 $expected 条，实际 ${controls.size}" }
        check(controls.size == JailbreakCases.buildCases().size) {
            "负对照 ${controls.size} 条 ≠ 有害集 ${JailbreakCases.buildCases().size} 条 —— 两组不是同一批实验"
        }
        controls.forEach { c ->
            check(c.prompt.isNotBlank()) { "${c.id} 的 prompt 为空" }
            check(c.id.startsWith("jbc-")) { "${c.id} 不是负对照的 id 前缀" }
        }
        println("dump 负对照 ${controls.size} 条 → ${file.absolutePath}")
    }
}
