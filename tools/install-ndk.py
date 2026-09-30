"""下载并解压 Android NDK（用于交叉编译 llama.cpp → arm64-v8a）。

为什么不用 sdkmanager：本机 SDK 里没有 cmdline-tools，而 dl.google.com 的
NDK zip 地址是稳定的（https://dl.google.com/android/repository/android-ndk-<ver>-windows.zip），
直连下载比补装 cmdline-tools + 过 license 交互更省事。
"""
import os
import sys
import time
import zipfile

import requests

PROXY = {"http": "http://127.0.0.1:7585", "https": "http://127.0.0.1:7585"}
VER = "r27c"
URL = f"https://dl.google.com/android/repository/android-ndk-{VER}-windows.zip"
DEST_DIR = r"D:\SDK\ndk"
ZIP_PATH = rf"D:\android-ndk-{VER}-windows.zip"
TARGET = os.path.join(DEST_DIR, VER)


def download():
    if os.path.exists(ZIP_PATH):
        print("已有 zip，跳过下载:", ZIP_PATH, os.path.getsize(ZIP_PATH))
        return
    print(f"下载 {URL}")
    with requests.get(URL, proxies=PROXY, stream=True, timeout=120) as r:
        r.raise_for_status()
        total = int(r.headers.get("content-length", 0))
        done = 0
        t0 = time.time()
        with open(ZIP_PATH, "wb") as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if done % (50 << 20) < (1 << 20):
                    pct = done * 100 / total if total else 0
                    mbps = done / 1048576 / max(time.time() - t0, 0.1)
                    print(f"  {pct:5.1f}%  {done>>20} MB / {total>>20} MB  ({mbps:.1f} MB/s)")
    print("下载完成:", round(os.path.getsize(ZIP_PATH) / 1048576, 1), "MB")


def extract():
    if os.path.isdir(TARGET) and os.path.isdir(os.path.join(TARGET, "build", "cmake")):
        print("已解压，跳过:", TARGET)
        return
    os.makedirs(DEST_DIR, exist_ok=True)
    print("解压到", DEST_DIR, "（NDK 文件很多，慢是正常的）")
    t0 = time.time()
    with zipfile.ZipFile(ZIP_PATH) as z:
        names = z.namelist()
        # zip 内顶层目录是 android-ndk-r27c/，改名成版本目录以符合 AGP 的 ndk/<version> 约定
        for i, n in enumerate(names):
            z.extract(n, DEST_DIR)
            if i % 2000 == 0:
                print(f"  {i}/{len(names)} ...", flush=True)
    src = os.path.join(DEST_DIR, f"android-ndk-{VER}")
    if os.path.isdir(src) and not os.path.isdir(TARGET):
        os.rename(src, TARGET)
    print(f"解压完成，用时 {time.time() - t0:.0f}s")


def main():
    download()
    extract()
    toolchain = os.path.join(TARGET, "build", "cmake", "android.toolchain.cmake")
    print("NDK 根目录 :", TARGET)
    print("工具链文件 :", toolchain, "存在" if os.path.exists(toolchain) else "❌ 缺失")
    clang = os.path.join(TARGET, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin", "clang.exe")
    print("clang     :", clang, "存在" if os.path.exists(clang) else "❌ 缺失")
    return 0


if __name__ == "__main__":
    sys.exit(main())
