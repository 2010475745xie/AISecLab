#!/usr/bin/env python3
"""判定层验收汇总（豁免洞那轮 + 本轮回归）。一次性脚本。

跑 5 套后端验收 + 2 套探针 + 2 套两端 parity，把结果写 _shot/_judge-verify.log。
"""
import os
import subprocess
import pathlib

ROOT = pathlib.Path(r"C:\AISecLab")
VENV = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
LOG = ROOT / "_shot" / "_judge-verify.log"

JOBS = [
    ("_verify_judge_fix", [VENV, str(ROOT / "backend" / "_verify_judge_fix.py")]),
    ("_verify_refusal", [VENV, str(ROOT / "backend" / "_verify_refusal.py")]),
    ("_verify_compliance", [VENV, str(ROOT / "backend" / "_verify_compliance.py")]),
    ("_verify_frozen", [VENV, str(ROOT / "backend" / "_verify_frozen.py")]),
    ("probe-l1-exempt-hole", [VENV, str(ROOT / "_shot" / "probe-l1-exempt-hole.py")]),
    ("check-judge-parity", [VENV, str(ROOT / "tools" / "check-judge-parity.py")]),
]

env = dict(os.environ)
out = []
for name, cmd in JOBS:
    out.append("")
    out.append("=" * 70)
    out.append(f"### {name}")
    out.append("=" * 70)
    r = subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    body = ((r.stdout or "") + ("\n--- stderr ---\n" + r.stderr if (r.stderr or "").strip() else "")).strip()
    out.append(body[-5000:] if len(body) > 5000 else body)
    out.append(f">>> exit code = {r.returncode}")
    LOG.write_text("\n".join(out), encoding="utf-8")
    print(name, r.returncode)

print("done")
