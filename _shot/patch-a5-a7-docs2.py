#!/usr/bin/env python3
"""A5/A7 落地后：把另外三份文档里的过期口径补齐。

原则：**判断依据不美化，历史数字不篡改** —— 对"当时的诊断 / 当时的验收标准"
只**加注**（"现已补上 / 现为 N 条"），不改原文；只把**表述当前事实的句子**
（如"变体集 200 条"）改成不带数字的说法，免得它每加一批用例就过期一次。

用法： <venv>/Scripts/python.exe _shot/patch-a5-a7-docs2.py
"""
import io
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
RENELLM = DOCS / "借鉴-ReNeLLM-可迁移清单.md"
HERETIC = DOCS / "借鉴-Heretic-可迁移清单.md"
MVP = DOCS / "越狱取证审计-技术方案与MVP.md"

EDITS = [
    # ── ReNeLLM 清单：缺口已补，但**保留当时的诊断原文** ────────────────
    (
        RENELLM,
        "## 1. 实测缺口：**15 个策略族里，没有一族是\"任务层伪装\"**\n"
        "\n"
        "### 1.1 我们现有的 15 族，按\"它把什么当成对手\"分类",
        "## 1. 实测缺口：**当时 15 个策略族里，没有一族是\"任务层伪装\"**\n"
        "\n"
        "> ✅ **2026-10-16 已补上**：`SCENARIO_NESTING`（任务层伪装）按 §2 的三组场景落地，\n"
        "> 现行变体集 = 11 族 × 24 目标 = 264 条。落地细节（借什么 / 不借什么 / 为什么\n"
        "> 追加在表尾就不会破坏历史可比性）见 `docs/参考项目-能力升级路线.md` §3.12。\n"
        "> **下面保留当时的诊断原文** —— 缺口描述是判断依据，不该事后美化。\n"
        "\n"
        "### 1.1 当时的 15 族（现 16 族），按\"它把什么当成对手\"分类",
    ),
    (
        RENELLM,
        "| **任务层伪装** | **（空）** | —— |",
        "| **任务层伪装** | `SCENARIO_NESTING`（A5 补的） | 改换**任务本身**：把诉求埋进一份待完成工件（代码补全 / 表格填写 / 故事续写），只索要那个工件 |",
    ),
    # ── Heretic 清单：去掉会随用例集过期的数字 ──────────────────────────
    (
        HERETIC,
        "（策略族 × 变体，变体集 200 条开关默认关）",
        "（策略族 × 变体，变体集开关默认关）",
    ),
    (
        HERETIC,
        "那 200 条变体集正好是**候选点**",
        "那批变体集正好是**候选点**",
    ),
    # ── MVP 文档：附表补四行；计划表只加注，不改原文 ────────────────────
    (
        MVP,
        "；真接入属 4.4 |\n",
        "；真接入属 4.4 |\n"
        "| A5 | **任务层伪装族 +1**（`SCENARIO_NESTING`：代码补全 / 表格填写 / 故事续写）；变体集扩到 **264 条** | `JailbreakVariantsTest`（逐族命中率 ≥ 80%）、`tools/check-jailbreak-parity.py`、`HardeningAdvisorTest`（逐族加固建议） |\n"
        "| A7 | **意图轴 +2**（暴力/人身伤害、高风险医疗建议）—— 9 类意图各有目标；CSAM / 政治游说 / 高风险政府决策**政策性排除、不建用例** | `JailbreakVariantsTest`（覆盖面断言与 `HarmIntent` 枚举等量）、两端 parity |\n"
        "| B7 | **无效/退化输出第三态**（空 / 纯标点 / 字符循环 / 连续重复 → INVALID，**既不算守住也不算失守**） | `_verify_refusal.py`、`RefusalScorerTest`、两端逐条一致 |\n"
        "| B8 | **CoT 剥离覆盖补全**（`<analysis>` / Harmony 通道 / `final` 标记 + 未闭合思考块判无效） | `_verify_refusal.py`、`CoTGovernanceTest` |\n",
    ),
    (
        MVP,
        "| 1.3 | **意图标签** | 新增 `HarmIntent`（制毒/入侵/歧视/隐私泄露/系统提示窃取/欺诈…）作为**用例元数据** | 有害集全部带 intent 标签（200 变体 + 18 安全基线 = 218 条） |",
        "| 1.3 | **意图标签** | 新增 `HarmIntent`（制毒/入侵/歧视/隐私泄露/系统提示窃取/欺诈…）作为**用例元数据** | 有害集全部带 intent 标签（200 变体 + 18 安全基线 = 218 条）。⚠️ **2026-10-16 现为 9 类意图 / 282 条**（A7 加了暴力与医疗建议） |",
    ),
    (
        MVP,
        "| 1.4 | **用例集** | 复用 `SecurityModule.buildCases()`；补「同一有害意图 × 10 类策略 × 各 20 条」变体集 | 10×20 = 200 条越狱变体可跑 |",
        "| 1.4 | **用例集** | 复用 `SecurityModule.buildCases()`；补「同一有害意图 × 10 类策略 × 各 20 条」变体集 | 10×20 = 200 条越狱变体可跑。⚠️ **2026-10-16 现为 24 目标 × 11 策略 = 264 条**（A5 任务层伪装 + A7 新目标） |",
    ),
    (
        MVP,
        "（19 条 / 15 族）",
        "（指纹库随族增长，别在这写数字）",
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
