#!/usr/bin/env python3
"""
在 Gradle test worker 跑不起来的环境里，直接喂 JUnitCore 跑单元测试。

## 为什么要这个脚本

本机（Windows + WorkBuddy 沙箱）执行 `gradle :app:testDebugUnitTest` 会失败：

    ???: java.lang.ClassNotFoundException:
         worker.org.gradle.process.internal.worker.GradleWorkerMain
    Could not write standard input to Gradle Test Executor 1.
    java.io.IOException: 管道正在被关闭。

`--stop` / `--no-daemon` 都无效。但 `:app:compileDebugUnitTestKotlin` 是好的 ——
说明测试代码没问题，只是 Gradle 派生测试 worker JVM 时加载不到 worker 主类。
所以绕开 Gradle 的 Test task，直接用编译产物 + 缓存的 junit jar 跑 JUnitCore。

## 用法

    # 1) 先让 Gradle 把测试编译出来（这步是好的）
    JAVA_HOME=C:/jdk21 D:/android/gradle-8.7/bin/gradle.bat -p C:/AISecLab \
        --console=plain :app:compileDebugUnitTestKotlin

    # 2) 再跑本脚本（不指定类名 = 自动发现 debugUnitTest 下所有 *Test 类）
    python tools/run-unit-tests.py
    python tools/run-unit-tests.py com.xieguojian.aiseclab.JudgeDirectionTest

## 两个坑（都是踩过的）

* **不要给这个脚本接 `| tail` / `| grep`**：沙箱里 bash 的管道会失败，
  表现为"无输出 + 非零退出"，看起来像测试挂了。
* **中文路径下 `glob.glob` 匹配不到**（`C:\\Users\\谢国健\\...` 实测返回 0 命中），
  所以 jar 必须用 `os.walk` 找。
"""

import os
import subprocess
import sys

GRADLE_CACHE = r"C:\Users\谢国健\.gradle\caches\modules-2\files-2.1"
PROJECT = r"C:\AISecLab"
JAVA = r"C:\jdk21\bin\java.exe"

MAIN_CLASSES = os.path.join(PROJECT, "app", "build", "tmp", "kotlin-classes", "debug")
TEST_CLASSES = os.path.join(PROJECT, "app", "build", "tmp", "kotlin-classes", "debugUnitTest")

# (jar 文件名前缀, 显示名)。前缀要能唯一命中，取路径最短的那个版本。
# 注意前缀要写成带尾破折号的形式，否则 `okhttp-` 会把 `okhttp-4.12.0-javadoc.jar` 也匹配进来。
WANTED_JARS = {
    "junit-4.": "junit",
    "hamcrest-core-": "hamcrest",
    "kotlin-stdlib-": "kotlin-stdlib",
    "kotlinx-coroutines-core-jvm-": "coroutines",
    "kotlinx-serialization-core-jvm-": "serialization-core",
    "kotlinx-serialization-json-jvm-": "serialization-json",
    # 联调脚本（GuardLiveSmoke）要走真实 HTTP，需要 OkHttp 及其 Okio 依赖
    "okhttp-4.": "okhttp",
    "okio-jvm-": "okio",
}

# 这些后缀不是运行时产物，不要进 classpath
SKIP_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-tests.jar")


def find_jars():
    found = {}
    if not os.path.isdir(GRADLE_CACHE):
        sys.exit(f"找不到 Gradle 缓存目录：{GRADLE_CACHE}")
    for root, dirs, files in os.walk(GRADLE_CACHE):
        # 剪枝：files-2.1/<group>/<artifact>/<ver>/<sha1>/<file>.jar 之后不再深入
        if root.count(os.sep) - GRADLE_CACHE.count(os.sep) >= 5:
            dirs[:] = []
            continue
        for name in files:
            if not name.endswith(".jar") or name.endswith(SKIP_SUFFIXES):
                continue
            for prefix, label in WANTED_JARS.items():
                if name.startswith(prefix):
                    path = os.path.join(root, name)
                    if label not in found or len(path) < len(found[label]):
                        found[label] = path
    return found


def discover_tests():
    """自动发现 debugUnitTest 下所有顶层 *Test.class。"""
    names = []
    if not os.path.isdir(TEST_CLASSES):
        return names
    for root, _, files in os.walk(TEST_CLASSES):
        for name in files:
            if not name.endswith("Test.class") or "$" in name:
                continue
            rel = os.path.relpath(os.path.join(root, name), TEST_CLASSES)
            names.append(rel[: -len(".class")].replace(os.sep, "."))
    return sorted(names)


def main():
    for d in (MAIN_CLASSES, TEST_CLASSES):
        if not os.path.isdir(d):
            sys.exit(f"缺少编译产物：{d}\n先跑 :app:compileDebugUnitTestKotlin")

    jars = find_jars()
    for label in WANTED_JARS.values():
        if label not in jars:
            print(f"  [警告] 缓存里没找到 {label} 的 jar")

    classpath = os.pathsep.join(
        [MAIN_CLASSES, TEST_CLASSES] + [jars[k] for k in WANTED_JARS.values() if k in jars]
    )

    tests = [a for a in sys.argv[1:] if not a.startswith("-D")]
    props = [a for a in sys.argv[1:] if a.startswith("-D")]
    if not tests:
        tests = discover_tests()
    if not tests:
        sys.exit("没有发现任何测试类")

    print(f"运行 {len(tests)} 个测试类：")
    for t in tests:
        print(f"  - {t}")
    if props:
        print(f"JVM 参数：{' '.join(props)}")
    print()

    result = subprocess.run(
        [JAVA,
         "-Dfile.encoding=UTF-8",
         # 中文输出乱码的根因：JVM 按控制台代码页(GBK)编码 System.out，
         # 而我们按 UTF-8 解码 → 中文变 `��������`。显式锁定编码。
         "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
         *props,
         "-cp", classpath,
         "org.junit.runner.JUnitCore", *tests],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    print(result.stdout, flush=True)
    if result.stderr.strip():
        print("--- stderr ---")
        print(result.stderr, flush=True)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
