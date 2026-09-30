"""交叉编译 libxguard.so（arm64-v8a）。

产物直接落到 `app/src/main/jniLibs/arm64-v8a/libxguard.so`，
Gradle 的 assembleDebug 只管打包，不再重复编译 native。

llama.cpp 全量编译很慢（十几分钟起步），所以本脚本设计成可重复调用：
build 目录保留增量，改完 C++ 再跑一次只编改动的部分。
"""
import os
import subprocess
import sys
import time

VENV = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default"
CMAKE = os.path.join(VENV, "Scripts", "cmake.exe")
NINJA = os.path.join(VENV, "Scripts", "ninja.exe")

NDK = r"D:\SDK\ndk\r27c"
TOOLCHAIN = os.path.join(NDK, "build", "cmake", "android.toolchain.cmake")
LLVM_BIN = os.path.join(NDK, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin")
STRIP = os.path.join(LLVM_BIN, "llvm-strip.exe")
NM = os.path.join(LLVM_BIN, "llvm-nm.exe")
LLAMA_DIR = r"D:\third_party\llama_cpp_src"

PROJECT = r"C:\AISecLab"
SRC = os.path.join(PROJECT, "app", "src", "main", "cpp")
BUILD = os.path.join(PROJECT, "build", "native-arm64")
JNI_LIBS = os.path.join(PROJECT, "app", "src", "main", "jniLibs", "arm64-v8a")
SO = os.path.join(JNI_LIBS, "libxguard.so")

ABI = "arm64-v8a"
PLATFORM = "android-24"  # 覆盖 Android 7.0+；AGP 的 minSdk 若更低需同步下调


def check_prereqs():
    problems = []
    for p, what in ((CMAKE, "cmake"), (NINJA, "ninja"), (TOOLCHAIN, "NDK 工具链"),
                    (os.path.join(LLAMA_DIR, "CMakeLists.txt"), "llama.cpp 源码")):
        if not os.path.exists(p):
            problems.append(f"缺少 {what}: {p}")
    if problems:
        for p in problems:
            print("❌", p)
        print("\n提示：cmake/ninja 用 pip 装进默认 venv；NDK 用 tools/install-ndk.py；"
              "llama.cpp 源码用 tools/fetch-llamacpp-from-pypi.py")
        return False
    return True


def run(cmd, cwd=None):
    print("$", " ".join(cmd), flush=True)
    t0 = time.time()
    r = subprocess.run(cmd, cwd=cwd)
    print(f"  → 退出码 {r.returncode}，用时 {time.time()-t0:.0f}s", flush=True)
    return r.returncode


def main():
    if not check_prereqs():
        return 1

    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(JNI_LIBS, exist_ok=True)

    # CMAKE_POLICY_VERSION_MINIMUM：CMake 4.x 会拒绝 `cmake_minimum_required(VERSION <3.5)`
    # 的子项目（ggml 的老依赖里可能还有）。钉住 3.5 让它按老策略走。
    configure = [
        CMAKE,
        "-S", SRC,
        "-B", BUILD,
        "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={NINJA}",
        f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN}",
        f"-DANDROID_ABI={ABI}",
        f"-DANDROID_PLATFORM={PLATFORM}",
        "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        f"-DLLAMA_DIR={LLAMA_DIR}",
    ]
    if run(configure) != 0:
        print("❌ configure 失败")
        return 1

    if run([CMAKE, "--build", BUILD, "--target", "xguard", "xguard_cli", "--parallel"]) != 0:
        print("❌ 编译失败")
        return 1

    def find_artifact(name):
        """在 build 目录里找产物。NDK 交叉编译在 Windows 宿主上可能带也可能不带 .exe。"""
        for cand in (os.path.join(BUILD, name), os.path.join(BUILD, name + ".exe")):
            if os.path.exists(cand):
                return cand
        for root, _dirs, files in os.walk(BUILD):
            for f in files:
                if f == name or f == name + ".exe":
                    return os.path.join(root, f)
        return None

    built = find_artifact("libxguard.so")
    if built is None:
        print("❌ 找不到产物 libxguard.so")
        return 1

    import shutil

    # ---- strip：37 MB 里 34 MB 是调试符号 -------------------------------
    # 运行时 dlopen 需要的只是 .dynsym 里的 JNI 入口和代码段，调试符号对真机毫无用处，
    # 装进 APK 等于白白多传 34 MB。AGP 只在打 release 时自动 strip，
    # debug 包会原样塞进去 —— 所以在这里就 strip 掉。
    # 但真机 native crash 栈需要符号才能读，所以未 strip 的那份单独留档
    # （Android 惯例：与 .so 同名的 .so.unstripped）。
    JNI_SYMBOLS = (
        "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeLoadModel",
        "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeFreeModel",
        "Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeClassify",
    )

    def install(src, dest, require_dynsyms=()):
        """复制 + strip + 校验动态符号。返回 (原大小, 装后大小)。"""
        unstripped = src + ".unstripped"
        if (not os.path.exists(unstripped)
                or os.path.getmtime(src) > os.path.getmtime(unstripped)):
            shutil.copy2(src, unstripped)

        before = os.path.getsize(src)
        if not os.path.exists(STRIP):
            print(f"⚠️  找不到 llvm-strip，跳过 strip —— 产物会大很多（{src}）")
            shutil.copy2(src, dest)
            return before, os.path.getsize(dest)

        stripped = src + ".stripped"
        if run([STRIP, "--strip-unneeded", "-o", stripped, src]) != 0:
            raise RuntimeError("strip 失败：" + src)

        # strip 改错参数会静默删掉导出符号，而这种错要到真机 loadLibrary/exec
        # 才暴露。所以这里立刻回查一次 .dynsym / 符号表。
        r = subprocess.run([NM, "-D", "--defined-only", stripped],
                           capture_output=True, text=True, errors="replace")
        syms = r.stdout or ""
        if not syms.strip():
            r = subprocess.run([NM, "--defined-only", stripped],
                               capture_output=True, text=True, errors="replace")
            syms = r.stdout or ""
        missing = [s for s in require_dynsyms if s not in syms]
        if missing:
            raise RuntimeError("strip 后符号丢失，拒绝安装该产物：" + ", ".join(missing))

        shutil.copy2(stripped, dest)
        return before, os.path.getsize(dest)

    try:
        b_before, b_after = install(built, SO, JNI_SYMBOLS)
    except RuntimeError as e:
        print("❌", e)
        return 1

    print(f"\n✅ JNI 库：{SO}  {b_after/1048576:.1f} MB（strip 前 {b_before/1048576:.1f} MB）")
    print(f"   符号文件：{built}.unstripped")

    cli = find_artifact("xguard_cli")
    if cli is None:
        print("⚠️  找不到 xguard_cli（验收工具），跳过")
        return 0

    cli_dest = os.path.join(PROJECT, "build", "native-tools", "xguard_cli")
    os.makedirs(os.path.dirname(cli_dest), exist_ok=True)
    try:
        c_before, c_after = install(cli, cli_dest)
    except RuntimeError as e:
        print("❌", e)
        return 1
    try:
        os.chmod(cli_dest, 0o755)  # adb push 后要能直接执行
    except OSError:
        pass

    print(f"✅ 设备端验收工具：{cli_dest}  {c_after/1048576:.1f} MB"
          f"（strip 前 {c_before/1048576:.1f} MB）")
    print("   推设备：adb push build/native-tools/xguard_cli /data/local/tmp/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
