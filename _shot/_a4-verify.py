#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A4 验收：TPE 搜索必须**真的在学**，且**可复现**、**受预算约束**。

为什么不是"跑一遍看着有数就行"：一个跑不出收敛的搜索，报告上照样能打印一串
漂亮的参数，只是那些参数跟随机选出来的一样 —— 所以这里把"学习"做成**可证伪的断言**：

  1. 收敛：40 trial 里最优失守率 ≥ 0.95（能到破防区）；
  2. **有学习**：后 10 trial 的平均失守率 **显著高于** 前 10 trial
     （前 10 是 TPE 的随机探索期）—— 这一条是"TPE 有没有用"的直接检验；
  3. 可复现：同 seed 两次跑，Pareto 前沿指纹一致；
  4. 预算闸门：小预算下 study 提前停、且**不超太多**；
  5. 断点续跑：不带 --fresh 再跑，trial 数接着涨；跑到目标数后再跑不新增；
  6. 不可复现要显式标记：http 目标必须 `reproducible=False`，且缺参数时直接拒绝。

用法（在 C:\\AISecLab 下，venv python）：
    <venv-py> _shot\\_a4-verify.py
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PY = sys.executable
TOOL = ROOT / "tools" / "attack-search.py"
TMP = pathlib.Path(tempfile.mkdtemp(prefix="a4-verify-"))

fails: "list[str]" = []


def run(args, expect_rc=0):
    r = subprocess.run([PY, str(TOOL), *args], cwd=str(ROOT),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != expect_rc:
        fails.append(f"`attack-search.py {' '.join(args)}` rc={r.returncode} != {expect_rc}\n"
                     + (r.stdout or "")[-800:] + (r.stderr or "")[-800:])
    return r


def check(label, ok, detail=""):
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}" + (f" -> {detail}" if detail else ""))
    if not ok:
        fails.append(f"{label}（{detail}）")


print("A4 验收：TPE 搜索的收敛 / 学习 / 复现 / 预算 / 续跑")
print()

# ── 1~3) 收敛 + 学习 + 复现 ──────────────────────────────────────────
out = TMP / "main.json"
run(["--target", "mock", "--n-trials", "40", "--seed", "7",
     "--check-repro", "--fresh", "--storage", str(TMP / "s1"), "--out", str(out)])
if not out.exists():
    print("!! 没拿到结果文件，后续断言跳过")
    sys.exit(1)

d = json.loads(out.read_text(encoding="utf-8"))
trials = d["trials"]
check("40 个 trial 全部落库", len(trials) == 40, f"实得 {len(trials)}")

vals = [t["fail_rate"] for t in trials]
check("没有 None 值（没有整条失败的 trial 被吞掉）", all(v is not None for v in vals))

best = max(vals) if vals else 0
check("收敛：最优失守率 ≥ 0.95", best >= 0.95, f"best={best}")

head = sum(vals[:10]) / 10
tail = sum(vals[-10:]) / 10
check("有学习：后 10 trial 均值 > 前 10 trial 均值",
      tail > head, f"前 {head:.2f} → 后 {tail:.2f}")

check("可复现：同 seed 两次 Pareto 前沿指纹一致",
      bool(d.get("repro_check", {}).get("ok")), f"fp={d['pareto_fingerprint']}")

check("mock 目标标记为可复现", d["reproducible"] is True)

# ── 4) 预算闸门 ──────────────────────────────────────────────────────
out_b = TMP / "budget.json"
run(["--target", "mock", "--n-trials", "60", "--seed", "3",
     "--budget-tokens", "3000", "--fresh", "--storage", str(TMP / "s2"), "--out", str(out_b)])
db = json.loads(out_b.read_text(encoding="utf-8"))
b = db["budget"]
check("预算闸门：study 提前停（trial 数 < 60）", db["n_trials"] < 60, f"n={db['n_trials']}")
check("预算闸门：标记 stopped", b.get("stopped") is True)
check("预算闸门：没有失控（花费 < 预算的 3 倍）",
      b["spent"] < 3 * b["limit"], f"spent={b['spent']} limit={b['limit']}")

# ── 5) 断点续跑 ──────────────────────────────────────────────────────
s3 = str(TMP / "s3")
run(["--target", "mock", "--n-trials", "8", "--seed", "5", "--fresh", "--storage", s3])
out_r = TMP / "resume.json"
run(["--target", "mock", "--n-trials", "20", "--seed", "5", "--storage", s3, "--out", str(out_r)])
r1 = json.loads(out_r.read_text(encoding="utf-8"))["n_trials"]
out_r2 = TMP / "resume2.json"
run(["--target", "mock", "--n-trials", "20", "--seed", "5", "--storage", s3, "--out", str(out_r2)])
r2 = json.loads(out_r2.read_text(encoding="utf-8"))["n_trials"]
check("断点续跑：8 → 20（接着跑，不是重来）", r1 == 20, f"实得 {r1}")
check("断点续跑：到目标数后再跑不新增", r2 == 20, f"实得 {r2}")

# ── 6) 不可复现的显式标记 ────────────────────────────────────────────
r = run(["--target", "http"], expect_rc=2)
check("http 目标缺 base-url/model 时直接拒绝（rc=2）", r.returncode == 2)

print()
if fails:
    print("发现问题：")
    for f in fails:
        print("  ✗ " + f)
    sys.exit(1)
print("[A4-search] ALL OK —— 搜索会收敛、会学习、可复现、受预算约束、能断点续跑")
