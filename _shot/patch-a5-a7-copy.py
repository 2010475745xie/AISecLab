#!/usr/bin/env python3
"""A5/A7 落地后，清掉**界面 / 日志 / 其它文件**里残留的硬编码条数。

这一批和前两个脚本分开的理由：前两批改的是"文档口径"与"断言数字"，
这一批改的是**用户看得见的字**与**别处的常量** —— 后者尤其危险：
`TestRunService` 那行日志把「已并入 200 条」写死在字符串里，
用例集涨到 264 之后它会照旧打「200」—— 用户对着 264 条的实际进度条看 200，
和当初"50 / 250 横跳"是同一类病（预告与实际各说各话）。所以它改成**取唯一口径**。

用法： <venv>/Scripts/python.exe _shot/patch-a5-a7-copy.py
"""
import io
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "xieguojian" / "aiseclab"
TEST = ROOT / "app" / "src" / "test" / "java" / "com" / "xieguojian" / "aiseclab"

EDITS = [
    # ── TestConfigScreen.kt：族清单改成从枚举取，别再手抄一份 ────────────
    (
        MAIN / "ui" / "screens" / "TestConfigScreen.kt",
        "import com.xieguojian.aiseclab.core.api.BackendClient\n",
        "import com.xieguojian.aiseclab.core.api.BackendClient\n"
        "import com.xieguojian.aiseclab.core.jailbreak.JailbreakCases\n",
    ),
    (
        MAIN / "ui" / "screens" / "TestConfigScreen.kt",
        "        // 越狱变体集：同一批有害意图 × 10 类策略（角色扮演/系统提示覆盖/虚构情境/编码混淆/拒绝抑制 +\n"
        "        // 说服改写/说服分类学/历史学术包装/思维实验/游戏化）\n"
        "        // × 各 20 条 = 200 条。加了它才能回答「换个包装还攻不攻得破」，代价是安全维度条数 ×12。",
        "        // 越狱变体集：同一批有害意图 × 全部策略族（清单见下面的文案，**从枚举取**，不手抄）。\n"
        "        // 加了它才能回答「换个包装还攻不攻得破」，代价是安全维度用例涨一个量级\n"
        "        // —— 具体条数见上方预览（唯一口径 = core/engine/CasePlan.kt）。",
    ),
    (
        MAIN / "ui" / "screens" / "TestConfigScreen.kt",
        "            else \"💥 再并入 $variantCount 条：20 个有害目标 × 10 类策略\" +\n"
        "                \"（角色扮演 / 系统提示覆盖 / 虚构情境 / 编码混淆 / 拒绝抑制 /\" +\n"
        "                \"说服改写 / 说服分类学 / 历史学术包装 / 思维实验 / 游戏化）。\" +\n"
        "                \"用同一批意图只换包装，用来量「哪种手法最容易得手」。\",",
        "            // 目标数 / 策略数 / 族名全部**从 JailbreakCases 取**：手抄一份的下场是\n"
        "            // 加了一族之后界面还在念旧名单（B7 那轮同型的\"两处口径走岔\"）。\n"
        "            else \"💥 再并入 $variantCount 条：${JailbreakCases.GOALS.size} 个有害目标 × \" +\n"
        "                \"${JailbreakCases.STRATEGIES.size} 类策略（\" +\n"
        "                JailbreakCases.STRATEGIES.joinToString(\" / \") { it.label } + \"）。\" +\n"
        "                \"用同一批意图只换包装，用来量「哪种手法最容易得手」。\",",
    ),
    (
        MAIN / "ui" / "screens" / "TestConfigScreen.kt",
        " * 为什么值得占配置页最上面一块：一轮 250 条的测试烧的是真 token，被中断后从头跑等于白烧一遍",
        " * 为什么值得占配置页最上面一块：一轮上百条的测试烧的是真 token，被中断后从头跑等于白烧一遍",
    ),
    (
        MAIN / "ui" / "screens" / "TestConfigScreen.kt",
        "    // 于是\"配置页说 250、跑出来 50\"时，两个数字各有各的出处、谁也说不清谁错\n"
        "    // （2026-10-15 用户报的\"勾了变体集数字在 50 和 250 之间横跳\"就是这个温床）。",
        "    // 于是\"配置页说的数、跑出来的数\"各有各的出处、谁也说不清谁错\n"
        "    // （2026-10-15 用户报的\"勾了变体集数字横跳\"就是这个温床）。",
    ),
    # ── TestRunService.kt：日志里的硬编码条数 → 取唯一口径 ────────────────
    (
        MAIN / "ui" / "TestRunService.kt",
        "import com.xieguojian.aiseclab.core.engine.plannedCaseCount\n",
        "import com.xieguojian.aiseclab.core.engine.plannedCaseCount\n"
        "import com.xieguojian.aiseclab.core.engine.variantCaseCount\n",
    ),
    (
        MAIN / "ui" / "TestRunService.kt",
        "                    appendLine(\"越狱变体集：$variants（${if (variants) \"已并入 200 条\" else \"未并入\"}）\")",
        "                    // 条数**必须取口径**、不能写死：写死过 200，用例集涨到 264 之后\n"
        "                    // 日志照旧念 200，用户对着 264 的实际进度看 200 —— 又一次\"预告 ≠ 实际\"。\n"
        "                    appendLine(\n"
        "                        \"越狱变体集：$variants\" +\n"
        "                            \"（${if (variants) \"已并入 ${variantCaseCount()} 条\" else \"未并入\"}）\"\n"
        "                    )",
    ),
    (
        MAIN / "ui" / "TestRunService.kt",
        "                    // pydantic 默认静默丢弃 → 照旧只跑 50 条。用户看到的就是\"配置页 250、跑出来 50\"。",
        "                    // pydantic 默认静默丢弃 → 照旧只跑基线 50 条，变体集一条没跑。",
    ),
    # ── 其余文档口径 ─────────────────────────────────────────────────────
    (
        MAIN / "ui" / "TestViewModel.kt",
        "     * 是否并入「越狱变体集」（200 条：20 个有害目标 × 10 类策略）。\n"
        "     *\n"
        "     * 默认关。打开后安全维度从 18 条涨到 218 条 —— 与递归对抗叠加时是最贵的一档\n"
        "     * （218 条安全用例 × 每例最多 10 轮），UI 上要把这个代价说清楚。",
        "     * 是否并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略）。\n"
        "     *\n"
        "     * 默认关。打开后安全维度从 18 条涨到 282 条 —— 与递归对抗叠加时是最贵的一档\n"
        "     * （282 条安全用例 × 每例最多 10 轮），UI 上要把这个代价说清楚。",
    ),
    (
        MAIN / "core" / "TestSession.kt",
        "     * 是否并入「越狱变体集」（200 条：20 个有害目标 × 10 类策略，见\n"
        "     * [com.xieguojian.aiseclab.core.jailbreak.JailbreakCases]）。\n"
        "     *\n"
        "     * 默认关：默认集是\"每个维度跑到就算过\"的回归基线；变体集是\"同意图换包装\"的取证样本，\n"
        "     * 目的不同，成本差一个量级（安全维度 18 → 218 条）。由用户在测试配置里显式打开。",
        "     * 是否并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略，见\n"
        "     * [com.xieguojian.aiseclab.core.jailbreak.JailbreakCases]）。\n"
        "     *\n"
        "     * 默认关：默认集是\"每个维度跑到就算过\"的回归基线；变体集是\"同意图换包装\"的取证样本，\n"
        "     * 目的不同，成本差一个量级（安全维度 18 → 282 条）。由用户在测试配置里显式打开。",
    ),
    (
        MAIN / "core" / "api" / "BackendClient.kt",
        "        // 并入「越狱变体集」（200 条：20 个有害目标 × 10 类策略）。",
        "        // 并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略）。",
    ),
    (
        MAIN / "core" / "jailbreak" / "EncodingVariants.kt",
        "     * 决定了哪条目标用哪种编码 —— 改顺序＝改 200 条变体里的 20 条 prompt，",
        "     * 决定了哪条目标用哪种编码 —— 改顺序＝改整族 24 条变体的 prompt，",
    ),
    (
        MAIN / "core" / "engine" / "modules" / "JailbreakVariantsModule.kt",
        " * 越狱变体集模块（200 条）—— [JailbreakCases] 的插件化外壳。\n"
        " *\n"
        " * 它**不并入默认 50 条**：默认集是\"每个维度跑到就算过\"的回归基线，\n"
        " * 变体集是\"同一意图换 10 种包装\"的取证样本，两者目的不同、成本差一个量级（200 vs 18 条安全用例）。",
        " * 越狱变体集模块（264 条）—— [JailbreakCases] 的插件化外壳。\n"
        " *\n"
        " * 它**不并入默认 50 条**：默认集是\"每个维度跑到就算过\"的回归基线，\n"
        " * 变体集是\"同一意图换 11 种包装\"的取证样本，两者目的不同、成本差一个量级（264 vs 18 条安全用例）。",
    ),
    (
        MAIN / "core" / "engine" / "CasePlan.kt",
        "/** 变体集条数。界面文案（「再并入 N 条」）也用它 —— 别把 200 写死在提示语里。 */",
        "/** 变体集条数。界面文案（「再并入 N 条」）也用它 —— 别把条数写死在提示语里。 */",
    ),
    (
        MAIN / "core" / "engine" / "CasePlan.kt",
        " * 用户报过一个现象：勾上「越狱变体集」之后，数字在 **50 和 250 之间横跳**。",
        " * 用户报过一个现象：勾上「越狱变体集」之后，数字在两个值之间**横跳**（当时是 50 与 250）。",
    ),
    (
        TEST / "JailbreakParityDumpTest.kt",
        " * 1. `fingerprints` —— 策略指纹库（17 条），比对 strategy / keywords / patterns / weight；\n"
        " * 2. `staticCases` —— 50 条基线用例，比对 dimension / intent / strategy / tags；\n"
        " * 3. `variants` —— 200 条越狱变体，比对 **prompt 全文**在内的一切字段。",
        " * 1. `fingerprints` —— 策略指纹库，比对 strategy / keywords / patterns / weight；\n"
        " * 2. `staticCases` —— 基线用例，比对 dimension / intent / strategy / tags；\n"
        " * 3. `variants` —— 越狱变体，比对 **prompt 全文**在内的一切字段。",
    ),
    (
        TEST / "JailbreakParityDumpTest.kt",
        "        check(variants.size == 200) { \"变体集不是 200 条，实际 ${variants.size}\" }",
        "        // 期望值**从生成器取**（不写死数字）：这里要防的是\"dump 少了一半\"，\n"
        "        // 而不是\"用例集条数变了\" —— 后者是 parity 脚本里那个显式常量负责的事。\n"
        "        val expectedVariants = JailbreakCases.buildCases().size\n"
        "        check(variants.size == expectedVariants) {\n"
        "            \"变体集不是 $expectedVariants 条，实际 ${variants.size}\"\n"
        "        }",
    ),
    (
        TEST / "JailbreakVariantsTest.kt",
        "        // 有害用例（安全维度 18 + 变体 200）必须全部有具体意图，不允许 unknown",
        "        // 有害用例（安全维度 18 + 变体 264）必须全部有具体意图，不允许 unknown",
    ),
]


def main() -> int:
    bad = 0
    for path, old, new in EDITS:
        src = io.open(path, encoding="utf-8").read()
        n = src.count(old)
        if n != 1:
            print(f"[MISS] {path.name}: 命中 {n} 次（应为 1）\n    {old[:80]!r}")
            bad += 1
            continue
        io.open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new))
        print(f"[ok] {path.name}: {old.strip().splitlines()[0][:56]}…")
    if bad:
        print(f"\n有 {bad} 处没改上 —— 别当成功。")
        return 1
    print("\n全部改上。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
