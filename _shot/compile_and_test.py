#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""先让 Gradle 编译单测（这步是好的），再用 tools/run-unit-tests.py 跑 JUnitCore。

为什么要包一层：Gradle 要写 ~/.gradle，必须在**沙箱外**跑；而两个子步骤的
环境（JAVA_HOME=C:\\jdk21 / 剥掉沙箱代理）必须一致，手敲容易漏。
"""

import os
import subprocess
import sys
import time

PROJECT = r"C:\AISecLab"
JAVA_HOME = r"C:\jdk21"
GRADLE = r"D:\android\gradle-8.7\bin\gradle.bat"
LOG = r"D:\gradle-unit-compile.log"

env = dict(os.environ)
env["JAVA_HOME"] = JAVA_HOME
# 沙箱代理会把本机探测也拦掉，这里不需要网络，直接剥干净
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    env.pop(k, None)

cmd = [GRADLE, "-p", PROJECT, ":app:compileDebugUnitTestKotlin", "--console=plain"]
print("$ " + " ".join(cmd))
t0 = time.time()
with open(LOG, "w", encoding="utf-8", errors="replace") as log:
    p = subprocess.run(cmd, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
text = open(LOG, encoding="utf-8", errors="replace").read()
print(f"[compile] exit={p.returncode} in {time.time() - t0:.1f}s")
tail = text.splitlines()[-30:]
print("\n".join(tail))
if p.returncode != 0:
    print("!!! 编译失败，不再跑测试")
    sys.exit(1)

r = subprocess.run(
    [sys.executable, os.path.join(PROJECT, "tools", "run-unit-tests.py")],
    cwd=PROJECT, capture_output=True, text=True,
    encoding="utf-8", errors="replace", env=env,
)
out = (r.stdout or "")
print(out[-8000:] if len(out) > 8000 else out)
if r.stderr:
    print("--- stderr ---")
    print((r.stderr or "")[-2000:])
print(f"[tests] exit={r.returncode}")
sys.exit(r.returncode)
