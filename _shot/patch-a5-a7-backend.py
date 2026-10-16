#!/usr/bin/env python3
"""A5/A7 落地后，改后端的硬断言与日志文案。

`backend/smoke_test.py` 的条数断言是**故意写死的**（防"变体集静默不跑"），
所以用例集一变就必须跟着改 —— 不改就直接失败，这是特性不是缺点。
`engine.py` 那句日志则是**反例**：它把"20 个有害目标 × 10 类策略"写死在格式串里，
加了目标与策略之后会继续念旧数字 —— 所以改成**由表长度现算**。

用法： <venv>/Scripts/python.exe _shot/patch-a5-a7-backend.py
"""
import io
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "backend" / "app"
BE = ROOT / "backend"

EDITS = [
    # ── jailbreak_cases.py：加一个由表长度现算的构成说明 ──────────────────
    (
        APP / "jailbreak_cases.py",
        "# 便于 import 端直接用\nJAILBREAK_VARIANT_CASES: List[TestCase] = build_cases()",
        "# 便于 import 端直接用\nJAILBREAK_VARIANT_CASES: List[TestCase] = build_cases()\n"
        "\n"
        "# 人读的构成说明（日志 / 报告用）。**由表长度现算，不手写** ——\n"
        "# 手写过\"20 个有害目标 × 10 类策略\"，目标与策略各加一批之后它仍会念旧数字，\n"
        "# 而那句日志是用户判断\"这一轮到底跑了什么\"的唯一线索。\n"
        "COMPOSITION: str = f\"{len(GOALS)} 个有害目标 × {len(STRATEGIES)} 类策略\"",
    ),
    # ── engine.py ───────────────────────────────────────────────────────
    (
        APP / "engine.py",
        "from .jailbreak_cases import JAILBREAK_VARIANT_CASES",
        "from .jailbreak_cases import COMPOSITION as JB_COMPOSITION, JAILBREAK_VARIANT_CASES",
    ),
    (
        APP / "engine.py",
        "        # 越狱变体集（200 条）**全部是 security 维度**，所以勾了维度才并入 ——\n"
        "        # 用户没勾安全维度却冒出 200 条安全用例，与上面那个\"未勾维度照样跑\"是同一个 bug。\n"
        "        # 变体集不参与 attackBudget（那是 LLM 动态生成的口径），它本身就是静态用例。\n"
        "        cases += JAILBREAK_VARIANT_CASES\n"
        "        logger.info(\"[engine] 并入越狱变体集 %d 条（20 个有害目标 × 10 类策略）\",\n"
        "                    len(JAILBREAK_VARIANT_CASES))",
        "        # 越狱变体集**全部是 security 维度**，所以勾了维度才并入 ——\n"
        "        # 用户没勾安全维度却冒出变体集那批安全用例，与上面那个\"未勾维度照样跑\"是同一个 bug。\n"
        "        # 变体集不参与 attackBudget（那是 LLM 动态生成的口径），它本身就是静态用例。\n"
        "        cases += JAILBREAK_VARIANT_CASES\n"
        "        # 构成说明取自 jailbreak_cases.COMPOSITION（现算），别在这里手写数字。\n"
        "        logger.info(\"[engine] 并入越狱变体集 %d 条（%s）\",\n"
        "                    len(JAILBREAK_VARIANT_CASES), JB_COMPOSITION)",
    ),
    # ── models.py 的注释口径 ────────────────────────────────────────────
    (
        APP / "models.py",
        "    # 是否并入「越狱变体集」（200 条：20 个有害目标 × 10 类策略，见 jailbreak_cases.py）。",
        "    # 是否并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略，见 jailbreak_cases.py）。",
    ),
    (
        APP / "models.py",
        "    # 目的不同、成本差一个量级（安全维度 18 → 218 条），必须由用户显式打开。",
        "    # 目的不同、成本差一个量级（安全维度 18 → 282 条），必须由用户显式打开。",
    ),
    # ── smoke_test.py：故意的硬断言 ─────────────────────────────────────
    (
        BE / "smoke_test.py",
        "# ── 4) 越狱变体集：开关打开后必须真的多跑 200 条 ──",
        "# ── 4) 越狱变体集：开关打开后必须真的多跑一整批（264 条）──",
    ),
    (
        BE / "smoke_test.py",
        "assert nv == 250, f\"开变体集后应为 250 条（50 基线 + 200 变体），实得 {nv}\"\n"
        "jbv = [r.testCase for r in report_v.results if r.testCase.id.startswith(\"jbv-\")]\n"
        "assert len(jbv) == 200, f\"变体条数应为 200，实得 {len(jbv)}\"",
        "assert nv == 314, f\"开变体集后应为 314 条（50 基线 + 264 变体），实得 {nv}\"\n"
        "jbv = [r.testCase for r in report_v.results if r.testCase.id.startswith(\"jbv-\")]\n"
        "assert len(jbv) == 264, f\"变体条数应为 264，实得 {len(jbv)}\"",
    ),
    (
        BE / "smoke_test.py",
        "assert sum(1 for c in jbv if c.encoded) == 20, \"编码族条数应为 20\"",
        "assert sum(1 for c in jbv if c.encoded) == 24, \"编码族条数应为 24\"",
    ),
    (
        BE / "smoke_test.py",
        "assert dims_v[\"security\"] == 218, f\"安全维度应为 218 条（18 基线 + 200 变体），实得 {dims_v['security']}\"",
        "assert dims_v[\"security\"] == 282, f\"安全维度应为 282 条（18 基线 + 264 变体），实得 {dims_v['security']}\"",
    ),
    # ── _verify_encodings.py ────────────────────────────────────────────
    (
        BE / "_verify_encodings.py",
        "5. 变体集里编码族 **20 条**，每条还原后都命中 `overwrite` 指纹。",
        "5. 变体集里编码族 **24 条**，每条还原后都命中 `overwrite` 指纹。",
    ),
    (
        BE / "_verify_encodings.py",
        "# ── 4) 变体集：编码族 20 条，还原后命中 overwrite 指纹（1.2 验收本身）────\n"
        "print(\"\\n== 4) 变体集：编码族 20 条还原后必须命中 overwrite 指纹 ==\")\n"
        "cases = C.build_cases()\n"
        "check(\"变体集总量\", len(cases), 200)\n"
        "enc_cases = [c for c in cases if c.encoded]\n"
        "check(\"编码族条数\", len(enc_cases), 20)",
        "# ── 4) 变体集：编码族 24 条，还原后命中 overwrite 指纹（1.2 验收本身）────\n"
        "print(\"\\n== 4) 变体集：编码族 24 条还原后必须命中 overwrite 指纹 ==\")\n"
        "cases = C.build_cases()\n"
        "check(\"变体集总量\", len(cases), 264)\n"
        "enc_cases = [c for c in cases if c.encoded]\n"
        "check(\"编码族条数\", len(enc_cases), 24)",
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
