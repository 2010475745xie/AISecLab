#!/usr/bin/env python3
"""负对照集（B9）的两端一致性 + 包装同源性校验（不触网、不起服务）。

## 它防的是什么

负对照的全部价值在于一句话：**「同一批包装 × 无害目标」**。
只要包装悄悄分了叉，`对照组失守率` 这个数字就**失去全部意义** ——
而且它**照常出数**、报告上完全看不出来（这正是最难查的一类问题）。

所以本脚本不满足于"两端条数一样"，它校验**七件事**：

1. **包装的唯一实现**（源码级）：`benign_controls.py` 必须**import** `prompt_for`，
   不许自己再写一份 dispatch。各写一份的后果是"对照组和实验组其实不是同一个实验"。
2. **下标对齐**：`len(BENIGN.GOALS) == len(JC.GOALS)` 且逐条 (family_label, encoded) 一致
   —— `i` 同时决定编码种类 / 说服技术 / 场景，错位就意味着两组的包装其实不一样。
3. **prompt 是载荷的纯函数**：把有害目标的 zh/en 换成对照的、其余字段（slug/label/intent）
   保持有害值再走同一个 `prompt_for`，结果必须**逐字等于**对照的 prompt。
   若某个模板读了 `goal.label`，这个等式就静默失效。
4. **非编码族的替换回代**：把对照 prompt 里的无害载荷换回有害载荷，必须逐字得到有害 prompt
   —— 这是"包装一字不改"最直白的机械证明。
5. **编码族的包装行逐字相同**：编码族的 prompt = 「中文包装行 \\n 编码体」，
   编码体**纯粹是载荷**（`encoding_payload` 只取 `goal.en`），所以包装行必须一模一样。
6. **阴性纪律**：负对照**不进变体集、不进默认基线**
   —— 它的 expected 是 `SHOULD_ANSWER_CORRECTLY`，混进有害集会污染失守率的分母。
7. **两端逐条一致**：端上 dump（`BenignControlParityDumpTest`）↔ 后端 `benign_controls.py`，
   比 id / title / prompt 全文 / expected / dimension / intent / strategy / encoded / tags。

用法（在 C:\\AISecLab 下）：
    <venv>/Scripts/python.exe tools/check-negative-control.py
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT_BENIGN = (ROOT / "app" / "src" / "main" / "java" / "com" / "xieguojian"
             / "aiseclab" / "core" / "jailbreak" / "BenignControls.kt")
PY_BENIGN = ROOT / "backend" / "app" / "benign_controls.py"

sys.path.insert(0, str(ROOT / "backend"))

failures = []

CASE_KEYS = ("id", "dimension", "title", "prompt", "expected", "intent", "strategy", "encoded", "tags")


# ────────────────────────────────────────────────────────────────
# 1. 包装的唯一实现（源码级）
# ────────────────────────────────────────────────────────────────

def check_single_implementation():
    py = PY_BENIGN.read_text(encoding="utf-8")
    kt = KT_BENIGN.read_text(encoding="utf-8")

    if re.search(r"^\s*def\s+prompt_for\b", py, re.M):
        failures.append(
            "benign_controls.py 自己定义了 prompt_for —— 包装必须只有一份实现，"
            "各写一份的后果是'对照组和实验组其实不是同一个实验'，且报告上看不出来"
        )
        return
    if not re.search(r"from\s+\.jailbreak_cases\s+import[^\n]*\bprompt_for\b", py):
        failures.append("benign_controls.py 没有从 jailbreak_cases import prompt_for（包装没有复用）")
        return

    # 端上：必须调 JailbreakCases.promptFor，且不得把模板函数名搬进来（那是复刻的信号）
    if "JailbreakCases.promptFor(" not in kt:
        failures.append("BenignControls.kt 没有调用 JailbreakCases.promptFor（包装没有复用）")
        return
    smuggled = [name for name in ("encodingWrapper", "scenarioNesting", "encodingPayload",
                                  "roleplay(", "persuasion(")
                if name in kt]
    if smuggled:
        failures.append(
            f"BenignControls.kt 里出现了模板实现的名字 {smuggled} —— "
            "负对照不许有自己的模板，一律走 JailbreakCases.promptFor"
        )
        return
    print("  ✓ 包装的唯一实现：两端都只 import / 调用 prompt_for，自己不拼模板")


# ────────────────────────────────────────────────────────────────
# 2~6. 后端侧的包装同源性与纪律
# ────────────────────────────────────────────────────────────────

def check_backend_semantics():
    import app.jailbreak_cases as JC
    import app.benign_controls as BC
    from app.attack import STATIC_CASES
    from app.models import Dimension, ExpectedBehavior, HarmIntent

    # ── 2. 下标对齐 ──
    if len(BC.GOALS) != len(JC.GOALS):
        failures.append(
            f"负对照目标数 {len(BC.GOALS)} ≠ 有害目标数 {len(JC.GOALS)} —— 下标对不齐"
        )
        return
    misaligned = []
    for s in JC.STRATEGIES:
        for i, (h, b) in enumerate(zip(JC.GOALS, BC.GOALS)):
            hw, bw = JC.prompt_for(s, h, i), JC.prompt_for(s, b, i)
            if hw.family_label != bw.family_label or hw.encoded != bw.encoded:
                misaligned.append(
                    f"    {s.value} #{i + 1}: 有害[{hw.family_label}/enc={hw.encoded}] "
                    f"vs 对照[{bw.family_label}/enc={bw.encoded}]"
                )
            if h.intent == HarmIntent.unknown:
                failures.append(f"有害目标 {h.slug} 的意图是 unknown —— 那是负对照才该有的值")
            if b.intent != HarmIntent.unknown:
                failures.append(
                    f"负对照目标 {b.slug} 被标了有害意图 {b.intent.value} —— 这是伪造标注"
                )
            if h.zh == b.zh or h.en == b.en:
                failures.append(f"第 {i + 1} 条负对照目标的载荷与有害目标相同（slug {b.slug}）")
    if misaligned:
        failures.append("族内子维度错位（i 对不上）：\n" + "\n".join(misaligned[:12]))
    else:
        print(f"  ✓ 下标对齐：{len(JC.STRATEGIES)} 族 × {len(BC.GOALS)} 目标，逐条 "
              f"(family_label, encoded) 一致")

    # ── 3. prompt 是载荷的纯函数 ──
    impure = []
    for s in JC.STRATEGIES:
        for i, (h, b) in enumerate(zip(JC.GOALS, BC.GOALS)):
            hybrid = JC.Goal(slug=h.slug, label=h.label, zh=b.zh, en=b.en, intent=h.intent)
            via = JC.prompt_for(s, hybrid, i).prompt
            direct = JC.prompt_for(s, b, i).prompt
            if via != direct:
                impure.append(f"    {s.value} #{i + 1}: prompt 依赖了 label/slug/intent")
    if impure:
        failures.append(
            "prompt 不再是载荷的函数（模板读了目标的非载荷字段）—— "
            "'负对照 = 有害用例 + 换载荷' 这个前提静默失效：\n" + "\n".join(impure[:12])
        )
    else:
        print("  ✓ 包装无副作用：prompt 只由 (策略, 中文载荷, 英文载荷, i) 决定")

    # ── 4. 非编码族：替换回代 ──
    asym = []
    for s in JC.STRATEGIES:
        for i, (h, b) in enumerate(zip(JC.GOALS, BC.GOALS)):
            hw, bw = JC.prompt_for(s, h, i), JC.prompt_for(s, b, i)
            if hw.encoded:
                continue
            back = bw.prompt.replace(b.zh, h.zh).replace(b.en, h.en)
            if back != hw.prompt:
                asym.append(f"    {s.value} #{i + 1} ({b.slug})")
    if asym:
        failures.append(
            "非编码族把对照载荷换回有害载荷后 ≠ 有害 prompt —— 包装改过：\n" + "\n".join(asym[:12])
        )
    else:
        print(f"  ✓ 非编码族替换回代：{sum(1 for s in JC.STRATEGIES for i in range(len(JC.GOALS)) if not JC.prompt_for(s, JC.GOALS[i], i).encoded)} "
              f"条全部逐字还原成有害 prompt")

    # ── 5. 编码族：包装行逐字相同 ──
    enc_bad = []
    enc_n = 0
    for s in JC.STRATEGIES:
        for i, (h, b) in enumerate(zip(JC.GOALS, BC.GOALS)):
            hw, bw = JC.prompt_for(s, h, i), JC.prompt_for(s, b, i)
            if not hw.encoded:
                continue
            enc_n += 1
            h_head = hw.prompt.split("\n", 1)[0]
            b_head = bw.prompt.split("\n", 1)[0]
            if h_head != b_head:
                enc_bad.append(f"    {s.value} #{i + 1}:\n      有害[{h_head}]\n      对照[{b_head}]")
            if hw.prompt == bw.prompt:
                enc_bad.append(f"    {s.value} #{i + 1}: 编码体与有害用例完全相同（载荷没换）")
    if enc_bad:
        failures.append("编码族的包装行不一致：\n" + "\n".join(enc_bad[:12]))
    else:
        print(f"  ✓ 编码族包装行：{enc_n} 条逐字相同（编码体纯载荷，差异只在载荷）")

    # ── 6. 阴性纪律 ──
    controls = BC.BENIGN_CONTROL_CASES
    if len(controls) != len(JC.STRATEGIES) * len(JC.GOALS):
        failures.append(f"负对照条数不是 {len(JC.STRATEGIES) * len(JC.GOALS)}，实际 {len(controls)}")
    variants = JC.build_cases()
    if any(c.id.startswith("jbc-") for c in variants):
        failures.append("变体集里混进了负对照（jbc-）")
    if any(c.id.startswith("jbv-") for c in controls):
        failures.append("负对照里混进了攻击用例（jbv-）")
    if any(c.id.startswith("jbc-") for c in STATIC_CASES):
        failures.append("静态基线里混进了负对照（jbc-）")
    bad_meta = []
    for c in controls:
        if c.dimension != Dimension.security:
            bad_meta.append(f"    {c.id} 维度不是 security")
        if c.expected != ExpectedBehavior.SHOULD_ANSWER_CORRECTLY:
            bad_meta.append(f"    {c.id} expected={c.expected.value}（必须是 SHOULD_ANSWER_CORRECTLY）")
        if c.intent != HarmIntent.unknown:
            bad_meta.append(f"    {c.id} intent={c.intent.value}（必须是 unknown）")
        if "jailbreak-control" not in c.tags:
            bad_meta.append(f"    {c.id} 缺 jailbreak-control 标签")
        if (c.strategy.value == "encoding") != c.encoded:
            bad_meta.append(f"    {c.id} encoded 与策略不符")
        if c.encoded and "encoded" not in c.tags:
            bad_meta.append(f"    {c.id} 标了 encoded 却没进 tags")
        if c.intent.value in c.tags or c.strategy.value in c.tags:
            bad_meta.append(f"    {c.id} 把 intent/strategy 复刻进了 tags")
    if bad_meta:
        failures.append("负对照元数据不对：\n" + "\n".join(bad_meta[:12]))
    if not any(c.id.startswith("jbc-") for c in controls):
        failures.append("负对照 id 前缀不是 jbc-")
    if len({c.id for c in controls}) != len(controls):
        failures.append("负对照有重复 id")
    if not bad_meta and not any(c.id.startswith("jbc-") for c in variants + STATIC_CASES):
        print(f"  ✓ 阴性纪律：{len(controls)} 条负对照不在变体集/静态基线里；"
              f"维度/期望/意图/标签全部正确")

    return [c.model_dump(mode="json") for c in controls], len(JC.GOALS)


# ────────────────────────────────────────────────────────────────
# 7. 端上 dump ↔ 后端逐条比对
# ────────────────────────────────────────────────────────────────

def dump_kotlin():
    tmp = ROOT / "app" / "build" / "tmp" / "benign-controls.json"
    if tmp.exists():
        tmp.unlink()
    runner = ROOT / "tools" / "run-unit-tests.py"
    r = subprocess.run(
        [sys.executable, str(runner),
         f"-Djailbreak.control.out={tmp}",
         "com.xieguojian.aiseclab.BenignControlParityDumpTest"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if not tmp.exists():
        return None, (r.stdout or "") + (r.stderr or "")
    return json.loads(tmp.read_text(encoding="utf-8")), ""


def compare(cases: list, dump: dict):
    kt = dump.get("controls") or []
    if len(kt) != len(cases):
        failures.append(f"负对照条数不一致：端上 {len(kt)} 条 vs 后端 {len(cases)} 条")
    keys = CASE_KEYS
    # 顺序也必须一致（同样都是 STRATEGIES × GOALS 的双层循环）
    if [c.get("id") for c in kt] != [c.get("id") for c in cases]:
        only_kt = sorted({c.get("id") for c in kt} - {c.get("id") for c in cases})
        only_py = sorted({c.get("id") for c in cases} - {c.get("id") for c in kt})
        failures.append(
            "负对照 id 集合/顺序不一致\n"
            f"    仅端上有: {only_kt[:8]}\n    仅后端有: {only_py[:8]}"
        )
        return
    bad = 0
    for i in range(min(len(kt), len(cases))):
        a, b = kt[i], cases[i]
        for key in keys:
            if a.get(key) != b.get(key):
                failures.append(
                    f"负对照第 {i} 条 [{a.get('id')}] 的 {key} 不一致\n"
                    f"      端上: {a.get(key)!r}\n      后端: {b.get(key)!r}"
                )
                bad += 1
    if bad == 0:
        print(f"  ✓ 两端负对照：{len(kt)} 条逐条一致"
              f"（prompt 全文 + expected/dimension/intent/strategy/encoded/tags）")


def main():
    print("负对照集（B9）两端一致性 + 包装同源性校验")
    print(f"  Kotlin: {KT_BENIGN}")
    print(f"  Python: {PY_BENIGN}")
    print()
    check_single_implementation()
    py_cases, py_goal_count = check_backend_semantics()

    dump, err = dump_kotlin()
    if dump is None:
        failures.append(
            "拿不到端上 dump（先跑 :app:compileDebugUnitTestKotlin 再执行本脚本）\n    输出尾部："
            + (err or "")[-600:].replace("\n", "\n    ")
        )
    else:
        counts = dump.get("expectedCounts") or {}
        print(f"  · 端上 dump：负对照 {counts.get('controls')} 条 / 有害目标 {counts.get('harmfulGoals')} 个")
        if counts.get("harmfulGoals") != py_goal_count:
            failures.append(
                f"端上有害目标数 {counts.get('harmfulGoals')} ≠ 后端 {py_goal_count} —— 两端的表不一样长"
            )
        compare(py_cases, dump)

    print()
    if failures:
        print("发现问题：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print("ALL OK —— 负对照与有害组是同一个实验，且两端逐字一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
