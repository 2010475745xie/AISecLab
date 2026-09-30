#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验收 libxguard.so：架构正确、JNI 符号可见、动态依赖干净、可 strip。

为什么要单独写一个脚本而不是肉眼看一次：
  交叉编译"成功"只说明链接过了 —— 完全可能编出一个 x86_64 的 .so，
  或者 JNI 符号因为 C++ name mangling / 忘了 extern "C" 而根本没导出。
  这两种错在 assembleDebug 时都不报错，要到真机 dlopen / System.loadLibrary 才炸，
  而那时错误信息是 "UnsatisfiedLinkError"，很难反推是构建错的。
  所以这里把"能不能被 Android 加载"的静态条件一次验完。
"""

import os
import re
import subprocess
import sys

NDK_BIN = r"D:\SDK\ndk\r27c\toolchains\llvm\prebuilt\windows-x86_64\bin"
READELF = os.path.join(NDK_BIN, "llvm-readelf.exe")
NM = os.path.join(NDK_BIN, "llvm-nm.exe")
STRIP = os.path.join(NDK_BIN, "llvm-strip.exe")

SO = r"C:\AISecLab\app\src\main\jniLibs\arm64-v8a\libxguard.so"

# 必须是这三个：Kotlin 侧 external fun 声明的就是这三个
REQUIRED_JNI = [
    "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeLoadModel",
    "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeFreeModel",
    "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeClassify",
]

# Android 上真正能用的动态库（NDK 平台库）。出现别的就是链接错了东西。
ALLOWED_NEEDED_PREFIXES = ("liblog.so", "libc.so", "libm.so", "libdl.so",
                           "libandroid.so", "libz.so", "libstdc++.so")

results = []


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + name + ("  " + detail if detail else ""))


def main():
    if not os.path.isfile(SO):
        print(f"❌ 找不到产物：{SO}")
        return 1
    size = os.path.getsize(SO)
    print(f"产物：{SO}")
    print(f"大小：{size:,} 字节（{size / 1024 / 1024:.1f} MB）")
    print()

    print("[1] ELF 架构")
    rc, out = run([READELF, "-h", SO])
    m = re.search(r"Machine:\s*(.+)", out)
    machine = (m.group(1).strip() if m else "?")
    check("Machine == AArch64", "AArch64" in machine, f"实际: {machine}")
    m = re.search(r"Type:\s*(.+)", out)
    check("Type == DYN (共享库)", bool(m and "DYN" in m.group(1)),
          f"实际: {m.group(1).strip() if m else '?'}")

    print()
    print("[2] JNI 导出符号（.dynsym 里必须有，否则 System.loadLibrary 后 UnsatisfiedLinkError）")
    rc, out = run([NM, "-D", "--defined-only", SO])
    dynsyms = out
    for sym in REQUIRED_JNI:
        # 必须是 T（代码段全局）而不是 U（未定义）
        found = re.search(r"\bT\s+" + re.escape(sym) + r"\s*$", dynsyms, re.M)
        check(f"导出 {sym.split('_')[-1]}", bool(found),
              "" if found else "未在 .dynsym 中找到（检查 extern \"C\" 与函数名拼写）")

    print()
    print("[3] 动态依赖")
    rc, out = run([READELF, "-d", SO])
    needed = re.findall(r"NEEDED\)\s+Shared library:\s+\[([^\]]+)\]", out)
    print(f"    NEEDED: {', '.join(needed) if needed else '(无)'}")
    bad = [n for n in needed if not n.startswith(ALLOWED_NEEDED_PREFIXES)]
    check("无非法动态依赖", not bad, f"可疑: {bad}" if bad else "")
    # libc++_shared.so 不应该出现：CMake 里我们用静态 libc++（NDK 默认 c++_static）
    check("未依赖 libc++_shared.so（否则 APK 必须额外打包它）",
          "libc++_shared.so" not in needed)

    print()
    print("[4] strip 后体积（AGP 打 release 会自动 strip；这里预估收益）")
    stripped = SO + ".stripped"
    rc, out = run([STRIP, "--strip-unneeded", "-o", stripped, SO])
    if rc == 0 and os.path.isfile(stripped):
        ss = os.path.getsize(stripped)
        print(f"    strip 后：{ss:,} 字节（{ss / 1024 / 1024:.1f} MB），"
              f"减少 {(size - ss) / 1024 / 1024:.1f} MB")
        # strip 后符号必须还在：JNI 符号是动态符号，strip --strip-unneeded 会保留
        rc2, out2 = run([NM, "-D", "--defined-only", stripped])
        still = all(re.search(r"\bT\s+" + re.escape(s) + r"\s*$", out2, re.M)
                    for s in REQUIRED_JNI)
        check("strip 后 JNI 符号仍导出", still)
        os.remove(stripped)
    else:
        check("strip 可用", False, out.strip()[:200])

    print()
    ok_n = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"===== {ok_n}/{total} 项通过 =====")
    if ok_n != total:
        print("失败项：")
        for n, ok, d in results:
            if not ok:
                print(f"  - {n} {d}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
