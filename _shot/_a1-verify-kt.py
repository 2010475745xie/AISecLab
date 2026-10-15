#!/usr/bin/env python3
"""A1 端上验收驱动：编译测试代码 → 跑越狱单测 → 重生成 dump → 两端 parity。

一次性脚本（_shot/ 不进仓库）。必须在**沙箱外**跑：Gradle 要写 ~/.gradle，
沙箱内会报 journal-1.lock 拒绝访问。

日志写到 _shot/_a1-verify.log，跑完直接读文件（本工具链的 stdout 回显不可靠）。
"""
import os
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(r"C:\AISecLab")
VENV_PY = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
MANAGED_PY = r"C:\Users\谢国健\.workbuddy\binaries\python\versions\3.13.12\python.exe"
GRADLE = r"D:\android\gradle-8.7\bin\gradle.bat"
LOG = ROOT / "_shot" / "_a1-verify.log"

env = dict(os.environ)
env["JAVA_HOME"] = r"C:\jdk21"

lines = []


def step(title, cmd):
    lines.append("")
    lines.append("=" * 70)
    lines.append(f"### {title}")
    lines.append(f"$ {' '.join(cmd)}")
    lines.append("=" * 70)
    r = subprocess.run(
        cmd, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    out = (r.stdout or "").strip()
    err = (r.stderr or "").strip()
    # Gradle 输出很长：留尾部（错误总在尾部）
    if out:
        lines.append(out[-6000:] if len(out) > 6000 else out)
    if err:
        lines.append("--- stderr ---")
        lines.append(err[-6000:] if len(err) > 6000 else err)
    lines.append(f">>> exit code = {r.returncode}")
    LOG.write_text("\n".join(lines), encoding="utf-8")
    return r.returncode


rc = []

# 1) 编译（这一步是好的；测试 worker 才起不来，见 run-unit-tests.py 的说明）
rc.append(("gradle-compile", step(
    "1) Gradle 编译 debugUnitTest",
    [GRADLE, "-p", str(ROOT), "--console=plain", ":app:compileDebugUnitTestKotlin"],
)))

# 2) 越狱单测（编译不过就没必要跑）
if rc[-1][1] == 0:
    rc.append(("jailbreak-tests", step(
        "2) 越狱单测（变体集 + 指纹 + 编码还原）",
        [MANAGED_PY, str(ROOT / "tools" / "run-unit-tests.py"),
         "com.xieguojian.aiseclab.JailbreakVariantsTest",
         "com.xieguojian.aiseclab.JailbreakFingerprintTest"],
    )))

# 3) 后端 A1 验收（确保没被端上改动带偏）
rc.append(("backend-encodings", step(
    "3) 后端 A1 验收 _verify_encodings.py",
    [VENV_PY, str(ROOT / "backend" / "_verify_encodings.py")],
)))

# 4) 两端 parity（内部会重跑 JailbreakParityDumpTest 生成 dump）
rc.append(("jailbreak-parity", step(
    "4) 两端 parity check-jailbreak-parity.py",
    [VENV_PY, str(ROOT / "tools" / "check-jailbreak-parity.py")],
)))

lines.append("")
lines.append("=" * 70)
lines.append("汇总")
for name, code in rc:
    lines.append(f"  {name}: {'OK' if code == 0 else f'FAIL(rc={code})'}")
LOG.write_text("\n".join(lines), encoding="utf-8")
print("done", [(n, c) for n, c in rc])
