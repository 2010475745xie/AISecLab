#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打 APK 的封装脚本。

为什么要有这个脚本（而不是直接敲 gradle 命令）：
  这个项目里 gradle 有三个容易踩的坑，每次手敲就会重新踩一遍：
  1. **JDK 版本**：`D:\\android\\jbr` 是 Java 25，Gradle 8.7 不支持它，
     失败信息只有一行 `What went wrong: 25.0.2`，看不出是 JDK 的问题。
     必须用 `C:\\jdk21`（21 LTS）。
  2. **沙箱**：Gradle 要写 `%USERPROFILE%\\.gradle`，这在沙箱白名单之外，
     报 `journal-1.lock (拒绝访问)`。所以本脚本必须在**沙箱外**运行：
     调用方需要 `dangerouslyDisableSandbox=true`。
  3. **残留守护进程**：一旦在沙箱里成功起过 daemon，它会一直占着
     `journal-1.lock`，导致之后所有构建都报"拒绝访问"。
     脚本因此会在**构建失败且报的是锁被占**时，清理僵死的 Gradle 守护进程并重试一次
     （不在开头就清理：那样每次都会白扔一个热守护进程，首次构建慢一倍）。

用法：
    python tools/build-apk.py [任务名...]     # 默认 :app:assembleDebug
环境变量：
    XGUARD_SKIP_DAEMON_CLEANUP=1  禁止清理守护进程（调试脚本自身时用）
"""

import os
import re
import subprocess
import sys
import time

PROJECT = r"C:\AISecLab"
JAVA_HOME = r"C:\jdk21"
GRADLE = r"D:\android\gradle-8.7\bin\gradle.bat"
LOG = r"D:\gradle-build.log"

DEFAULT_TASKS = [":app:assembleDebug"]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def java_home_version(java_home):
    exe = os.path.join(java_home, "bin", "java.exe")
    if not os.path.exists(exe):
        return None
    r = run([exe, "-version"])
    text = (r.stderr or "") + (r.stdout or "")
    m = re.search(r'version "([^"]+)"', text)
    return m.group(1) if m else "?"


def gradle_bad_java_lock_warning(exclude=()):
    """找出可能占着 ~/.gradle 锁的僵死 java 进程。

    只报告、不擅自杀：用户机器上也可能有别的 Java 程序。
    本脚本只清理**确认是 Gradle 守护进程**的那些（判据：PID 能在
    ~/.gradle/daemon/<ver>/daemon-<pid>.out.log 里找到同名日志文件）。
    """
    r = run(["tasklist", "/FI", "IMAGENAME eq java.exe", "/FO", "CSV"])
    pids = set(int(x) for x in re.findall(r'"java\.exe","(\d+)"', r.stdout or ""))
    pids -= set(exclude)

    daemon_dir = os.path.join(os.path.expanduser("~"), ".gradle", "daemon")
    known = set()
    if os.path.isdir(daemon_dir):
        for ver in os.listdir(daemon_dir):
            d = os.path.join(daemon_dir, ver)
            if not os.path.isdir(d):
                continue
            for f in os.listdir(d):
                m = re.match(r"daemon-(\d+)\.out\.log$", f)
                if m:
                    known.add(int(m.group(1)))
    return sorted(pids & known)


def build_once(tasks, env):
    cmd = [GRADLE] + tasks + ["--console=plain"]
    print("$ " + " ".join(cmd))
    t0 = time.time()
    with open(LOG, "w", encoding="utf-8", errors="replace") as log:
        p = subprocess.run(cmd, cwd=PROJECT, env=env, stdout=log,
                           stderr=subprocess.STDOUT)
    text = open(LOG, encoding="utf-8", errors="replace").read()
    return p.returncode, text, time.time() - t0


def looks_like_lock_denied(text):
    return ("拒绝访问" in text or "Access is denied" in text
            or "FileAccessTimeJournal" in text or "journal-1.lock" in text)


def main():
    tasks = sys.argv[1:] or DEFAULT_TASKS

    ver = java_home_version(JAVA_HOME)
    if ver is None:
        print(f"❌ 找不到 JAVA_HOME 下的 java：{JAVA_HOME}")
        return 1
    major = int(ver.split(".")[0])
    print(f"JDK: {JAVA_HOME} -> {ver}")
    if major != 21:
        print(f"⚠️  Gradle 8.7 支持到 Java 21；当前 {ver} 可能导致构建失败")

    if not os.path.exists(GRADLE):
        print(f"❌ 找不到 gradle：{GRADLE}")
        return 1

    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    # 让 gradle 的输出不要被系统代码页搞乱（中文错信息会变乱码，没法排查）
    env["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"

    rc, text, dur = build_once(tasks, env)

    # 只在真的撞上"锁被占"时才去杀守护进程重试。
    # 一上来就杀会让每次构建都白扔一个热守护进程（首次构建慢一倍）。
    if rc != 0 and looks_like_lock_denied(text) and not os.environ.get("XGUARD_SKIP_DAEMON_CLEANUP"):
        stale = gradle_bad_java_lock_warning()
        if stale:
            print(f"检测到锁被占，清理僵死 Gradle 守护进程 {stale} 后重试…")
            for pid in stale:
                # /T 连子进程一起收掉（Kotlin 编译守护进程是它的子进程）
                run(["taskkill", "/PID", str(pid), "/F", "/T"])
            time.sleep(1.5)
            rc, text, dur = build_once(tasks, env)

    ok = "BUILD SUCCESSFUL" in text
    print(f"  → 退出码 {rc}，用时 {dur:.0f}s，日志 {LOG}")
    if ok:
        print("✅ BUILD SUCCESSFUL")
        for t in tasks:
            if "assemble" in t:
                apk = os.path.join(PROJECT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
                if os.path.exists(apk):
                    print(f"   APK: {apk}  ({os.path.getsize(apk)/1048576:.1f} MB)")
        return 0

    print("❌ 构建失败，关键行：")
    shown = 0
    for line in text.splitlines():
        if re.search(r"error:|^e: |FAILURE|What went wrong|拒绝访问|Could not|cannot find symbol", line):
            print("   " + line.strip()[:240])
            shown += 1
            if shown >= 25:
                break
    if shown == 0:
        print("   （日志里没匹配到已知错误模式，请直接看 " + LOG + "）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
