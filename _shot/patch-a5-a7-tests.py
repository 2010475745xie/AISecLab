#!/usr/bin/env python3
"""A5/A7 落地后，把两端**断言里的条数**一并改对。

为什么必须单独做这一步：`tools/run-unit-tests.py` **只跑已编译产物**，不触发 Gradle 编译。
改完测试源码不重新编译，跑出来的仍是旧断言 —— 会得到"改了却全绿"的假象（B7 那轮踩过）。

用法： <venv>/Scripts/python.exe _shot/patch-a5-a7-counts.py 之后跑本脚本。
"""
import io
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
T = ROOT / "app" / "src" / "test" / "java" / "com" / "xieguojian" / "aiseclab"
TOOLS = ROOT / "tools"

EDITS = [
    # ── JailbreakVariantsTest.kt ────────────────────────────────────────
    (
        T / "JailbreakVariantsTest.kt",
        "    fun `变体集正好200条且每个策略族各20条`() {\n"
        "        assertEquals(\"变体集总量不对\", 200, variants.size)",
        "    fun `变体集正好264条且每个策略族各24条`() {\n"
        "        assertEquals(\"变体集总量不对\", 264, variants.size)",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "                \"策略族 ${s.id} 的条数不是 20（策略 × 意图矩阵会因此不平衡）\",\n"
        "                20, variants.count { it.strategy == s }",
        "                \"策略族 ${s.id} 的条数不是 24（策略 × 意图矩阵会因此不平衡）\",\n"
        "                24, variants.count { it.strategy == s }",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "        // 10 类 × 20 = 200，两处数字必须自洽，否则\"200 条\"是碰巧凑出来的\n"
        "        assertEquals(200, JailbreakCases.STRATEGIES.size * JailbreakCases.GOALS.size)",
        "        // 11 类 × 24 = 264，两处数字必须自洽，否则\"264 条\"是碰巧凑出来的\n"
        "        assertEquals(264, JailbreakCases.STRATEGIES.size * JailbreakCases.GOALS.size)",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "        assertEquals(\"变体集模块条数不是 200\", 200, JailbreakVariantsModule().buildCases().size)",
        "        assertEquals(\"变体集模块条数不是 264\", 264, JailbreakVariantsModule().buildCases().size)",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "        // 覆盖面：变体集必须真的铺开在多类意图上，否则\"策略 × 意图矩阵\"只有一行有数\n"
        "        val intents = variants.map { it.intent }.toSet()\n"
        "        assertTrue(\"变体集覆盖的意图只有 $intents，太窄\", intents.size >= 6)",
        "        // 覆盖面：变体集必须铺满**全部已定义的有害意图**，而不是\"至少 6 类\"。\n"
        "        // 钉成与 HarmIntent 等量，是为了让「加了新意图却忘了加目标」当场变红 ——\n"
        "        // 那种情况下策略 × 意图矩阵会凭空少一行，而**少一行不会报错**，报告上完全看不出来。\n"
        "        val intents = variants.map { it.intent.id }.toSet()\n"
        "        val declared = HarmIntent.entries.map { it.id }.toSet() - HarmIntent.UNKNOWN.id\n"
        "        assertEquals(\n"
        "            \"变体集没覆盖这些意图：${declared - intents}\",\n"
        "            declared, intents\n"
        "        )",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "    fun `全量250条用例都带结构化意图与策略字段`() {",
        "    fun `全量314条用例都带结构化意图与策略字段`() {",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "        assertEquals(\"全量条数不对（50 基线 + 200 变体）\", 250, all.size)",
        "        assertEquals(\"全量条数不对（50 基线 + 264 变体）\", 314, all.size)",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "        assertEquals(\"有害用例条数不对（18 基线 + 200 变体）\", 218, harmful.size)",
        "        assertEquals(\"有害用例条数不对（18 基线 + 264 变体）\", 282, harmful.size)",
    ),
    (
        T / "JailbreakVariantsTest.kt",
        "    fun `编码族的20条还原后都能命中覆盖指纹`() {\n"
        "        // 这是 1.2 的验收本身：还原不是\"看起来解出来了\"，而是**还原后能被检测器命中**\n"
        "        val encoded = variants.filter { it.encoded }\n"
        "        assertEquals(\"编码族条数不是 20\", 20, encoded.size)",
        "    fun `编码族的24条还原后都能命中覆盖指纹`() {\n"
        "        // 这是 1.2 的验收本身：还原不是\"看起来解出来了\"，而是**还原后能被检测器命中**\n"
        "        val encoded = variants.filter { it.encoded }\n"
        "        assertEquals(\"编码族条数不是 24\", 24, encoded.size)",
    ),
    # ── CasePlanTest.kt ────────────────────────────────────────────────
    (
        T / "CasePlanTest.kt",
        " * 根子不在算术，而在**同一个问题有两个来源**：配置页是端上自己推的（250），",
        " * 根子不在算术，而在**同一个问题有两个来源**：配置页是端上自己推的（当时是 250），",
    ),
    (
        T / "CasePlanTest.kt",
        " * 勾了别的维度却冒出 200 条安全用例，是以前修过的同型 bug（未勾维度照样跑）。",
        " * 勾了别的维度却冒出变体集那批安全用例，是以前修过的同型 bug（未勾维度照样跑）。",
    ),
    (
        T / "CasePlanTest.kt",
        "    fun 勾上变体集时全维度是250条() {\n"
        "        // 50 + 200。这个数字必须与后端 engine.run_session 取用例的结果一致，\n"
        "        // 否则配置页的预告就是在骗用户（耗时和 API 花费都按它估）。\n"
        "        assertEquals(250, plannedCaseCount(Dimension.values().toSet(), variants = true))",
        "    fun 勾上变体集时全维度是314条() {\n"
        "        // 50 + 264。这个数字必须与后端 engine.run_session 取用例的结果一致，\n"
        "        // 否则配置页的预告就是在骗用户（耗时和 API 花费都按它估）。\n"
        "        assertEquals(314, plannedCaseCount(Dimension.values().toSet(), variants = true))",
    ),
    (
        T / "CasePlanTest.kt",
        "    fun 变体集条数与模块一致() {\n        assertEquals(200, variantCaseCount())",
        "    fun 变体集条数与模块一致() {\n        assertEquals(264, variantCaseCount())",
    ),
    # ── tools/check-jailbreak-parity.py ────────────────────────────────
    (
        TOOLS / "check-jailbreak-parity.py",
        "   - 200 条越狱变体：上面这些**再加 title / prompt 全文** ——",
        "   - 264 条越狱变体：上面这些**再加 title / prompt 全文** ——",
    ),
    (
        TOOLS / "check-jailbreak-parity.py",
        "    \"\"\"200 条越狱变体：**prompt 全文也要比**。",
        "    \"\"\"264 条越狱变体：**prompt 全文也要比**。",
    ),
    (
        TOOLS / "check-jailbreak-parity.py",
        "        if counts.get(\"variants\") != 200:\n"
        "            failures.append(f\"端上变体集不是 200 条（实际 {counts.get('variants')}）\")",
        "        if counts.get(\"variants\") != 264:\n"
        "            failures.append(f\"端上变体集不是 264 条（实际 {counts.get('variants')}）\")",
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
