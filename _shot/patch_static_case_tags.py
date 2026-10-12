#!/usr/bin/env python3
"""一次性补丁：给 backend/app/attack.py 的 STATIC_CASES 逐条补 intent/strategy 标注。

为什么用脚本而不是手改 50 处：50 条散在 100 多行里，手改必然漏几条或串行，
而"漏了一条"不会报错 —— 那条用例的 intent 就永远是 unknown，静默地不进任何统计。
脚本按传入的映射表逐条核对，**id 对不上就报错退出**，漏改会当场暴露。

用法： <venv>/Scripts/python.exe _shot/patch_static_case_tags.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = ROOT / "backend" / "app" / "attack.py"

# id -> (HarmIntent 成员名, JailbreakStrategy 成员名)
TAGS = {
    # 安全性：提示注入 / 越狱 / 系统提示泄露 / 危险内容 / PII
    "sec-inj-1": ("chem_weapon", "overwrite"),
    "sec-inj-2": ("chem_weapon", "roleplay"),
    "sec-inj-3": ("malware", "overwrite"),
    "sec-jb-1": ("chem_weapon", "roleplay"),
    "sec-jb-2": ("chem_weapon", "roleplay"),
    "sec-jb-3": ("chem_weapon", "roleplay"),
    "sec-jb-4": ("chem_weapon", "fiction"),
    "sec-leak-1": ("sysprompt_leak", "none"),
    "sec-leak-2": ("sysprompt_leak", "none"),
    "sec-leak-3": ("sysprompt_leak", "none"),
    "sec-harm-1": ("chem_weapon", "none"),
    "sec-harm-2": ("chem_weapon", "none"),
    "sec-harm-3": ("malware", "none"),
    "sec-harm-4": ("self_harm", "none"),
    "sec-pii-1": ("privacy_exfil", "none"),
    "sec-pii-2": ("privacy_exfil", "none"),
    "sec-pii-3": ("privacy_exfil", "none"),
    "sec-pii-4": ("privacy_exfil", "none"),
    # 一致性 / 事实性：本来就不是越狱，如实标 unknown/none
    "con-math-1": ("unknown", "none"),
    "con-math-2": ("unknown", "none"),
    "con-math-3": ("unknown", "none"),
    "con-math-4": ("unknown", "none"),
    "con-logic-1": ("unknown", "none"),
    "con-logic-2": ("unknown", "none"),
    "con-logic-3": ("unknown", "none"),
    "con-contra-1": ("unknown", "none"),
    "con-contra-2": ("unknown", "none"),
    "con-consis-1": ("unknown", "none"),
    "con-consis-2": ("unknown", "none"),
    "fact-fab-1": ("unknown", "none"),
    "fact-fab-2": ("unknown", "none"),
    "fact-fab-3": ("unknown", "none"),
    "fact-cite-1": ("unknown", "none"),
    "fact-cite-2": ("unknown", "none"),
    "fact-bound-1": ("unknown", "none"),
    "fact-bound-2": ("unknown", "none"),
    "fact-bound-3": ("unknown", "none"),
    "fact-known-1": ("unknown", "none"),
    "fact-known-2": ("unknown", "none"),
    # 鲁棒性：只有 4 条真带包装，其余是纯噪声
    "rob-noise-1": ("unknown", "none"),
    "rob-noise-2": ("unknown", "none"),
    "rob-noise-3": ("unknown", "none"),
    "rob-encode-1": ("unknown", "encoding"),
    "rob-encode-2": ("unknown", "encoding"),
    "rob-lang-1": ("unknown", "none"),
    "rob-lang-2": ("unknown", "none"),
    "rob-suffix-1": ("unknown", "overwrite"),
    "rob-suffix-2": ("sysprompt_leak", "overwrite"),
    "rob-case-1": ("unknown", "none"),
    "rob-case-2": ("unknown", "none"),
}

OLD_IMPORT = "from .models import AttackRound, Dimension, ExpectedBehavior, TestCase"
NEW_IMPORT = (
    "from .models import (\n"
    "    AttackRound,\n"
    "    Dimension,\n"
    "    ExpectedBehavior,\n"
    "    HarmIntent,\n"
    "    JailbreakStrategy,\n"
    "    TestCase,\n"
    ")"
)


def main() -> int:
    src = TARGET.read_text(encoding="utf-8")

    if OLD_IMPORT in src:
        src = src.replace(OLD_IMPORT, NEW_IMPORT, 1)
    elif "HarmIntent," in src:
        pass
    else:
        print("✗ 找不到 import 行，先看看 attack.py 头部", file=sys.stderr)
        return 1

    # 按 `TestCase(` 切块：每块 = 一条用例（到下一个 TestCase( 之前）。
    # 只在含 `id="` 的块里动手 —— generate_cases() 里那处 TestCase 没有 id，跳过。
    parts = src.split("TestCase(")
    patched, seen = 0, set()
    out = [parts[0]]
    for chunk in parts[1:]:
        m_id = re.search(r'id="([\w\-]+)"', chunk)
        m_exp = re.search(r"expected=ExpectedBehavior\.(\w+)\)", chunk)
        if not m_id or not m_exp:
            out.append("TestCase(" + chunk)
            continue
        cid = m_id.group(1)
        if cid not in TAGS:
            print(f"✗ STATIC_CASES 里有未登记的用例 id：{cid}", file=sys.stderr)
            return 1
        if "intent=" in chunk[: m_exp.end() + 20]:
            out.append("TestCase(" + chunk)   # 已经打过，幂等
            continue
        seen.add(cid)
        intent, strategy = TAGS[cid]
        new = (
            chunk[: m_exp.end() - 1]
            + f", intent=HarmIntent.{intent}, strategy=JailbreakStrategy.{strategy})"
            + chunk[m_exp.end():]
        )
        out.append("TestCase(" + new)
        patched += 1

    src = "".join(out)
    TARGET.write_text(src, encoding="utf-8")

    missing = sorted(set(TAGS) - seen)
    print(f"补标注 {patched} 条；映射表登记 {len(TAGS)} 条")
    if missing:
        print("✗ 映射表里有、源码里没找到的 id：" + ", ".join(missing), file=sys.stderr)
        return 1
    if patched == 0:
        print("⚠ 一条都没改 —— 可能已经打过补丁", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
