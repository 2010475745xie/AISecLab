# libxguard.so —— 把 llama.cpp 与 XGuard 的 JNI 封装静态链接成一个 .so。
#
# ## 为什么不用 AGP 的 externalNativeBuild
# 这里刻意做成**独立 CMake 工程**，由 `tools/build-native.py` 调用，产物落到
# `app/src/main/jniLibs/arm64-v8a/`，让 Gradle 只负责打包。
# 原因：llama.cpp 全量编译在小机器上要十几分钟，挂在 `assembleDebug` 里会让
# 每次改一行 Kotlin 都重新过一遍 native 构建；而且 AGP 找 NDK/CMake 还多一层路径配置。
# 想改回 AGP 集成也很简单 —— 加上 `externalNativeBuild { cmake { path = ... } }` 即可。
#
# ## 用法
#   cmake -S app/src/main/cpp -B build/native-arm64 -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
#     -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR=D:/third_party/llama.cpp
#   cmake --build build/native-arm64
# （`tools/build-native.py` 已封装以上步骤）

cmake_minimum_required(VERSION 3.14)
project(xguard C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "必须指定 -DLLAMA_DIR=<llama.cpp 源码目录>")
endif()
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_DIR 下没有 CMakeLists.txt：${LLAMA_DIR}")
endif()

# ---- llama.cpp 构建开关：只要推理库，不要工具/示例/服务 ----
# 每个都显式写给 CACHE 并 FORCE，避免被父工程的缓存值覆盖。
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
# 关键：这个版本的 llama.cpp 里 LLAMA_OPENSSL 默认 **ON**，
# 交叉编译到 Android 时会让 CMake 去找宿主机/目标机的 OpenSSL 而失败。
# 我们不用它的 HTTPS 能力（服务端示例才需要），直接关掉。
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_SYSTEM_GGML  OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # NDK 默认没有 OpenMP
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)  # 先关掉，减少交叉编译变量
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # 交叉编译必须关
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)

# 注意：这里**没有**打开 GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16。
# 它能让 0.6B 推理快一截，但会在不支持 dotprod 的老机器上直接非法指令崩溃。
# 安全工具宁可慢也不能在别人手机上崩；确认目标机型下限后再作为性能选项打开。

add_subdirectory(${LLAMA_DIR} "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# ---- 核心逻辑（与 JNI 无关）----
# 单独成静态库，让 .so 和验收用 CLI 共用同一份实现。
# 否则两边各编一遍源码，就会出现"验的是 A、发的是 B"这种最没意义的假验收。
add_library(xguard_core STATIC xguard_core.cpp)
target_link_libraries(xguard_core PUBLIC llama)
target_compile_options(xguard_core PRIVATE -fvisibility=hidden)

# ---- 打包进 APK 的 JNI 库 ----
add_library(xguard SHARED xguard_jni.cpp)
target_link_libraries(xguard PRIVATE xguard_core llama log)

# 只导出 JNI 入口，其余符号隐藏：减小体积，也避免和宿主里其它 .so 的 ggml 符号撞名
target_compile_options(xguard PRIVATE -fvisibility=hidden)
target_link_options(xguard PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
)

# JNI 入口必须可见（默认构造的类名函数），单独放开
set_target_properties(xguard PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# ---- 设备端验收工具（不进 APK）----
# 推到 /data/local/tmp 直接跑，用来验证"端上判定 == PC 判定"。
# 之所以做 CLI 而不是让 App 弹个界面手点：手点的验收只会发生一次，
# 之后每次改模板/阈值都会静默腐烂（见 xguard_cli.cpp 顶部说明）。
add_executable(xguard_cli xguard_cli.cpp)
target_link_libraries(xguard_cli PRIVATE xguard_core llama log)
# Android 要求可执行文件是 PIE（NDK 对 android-21+ 默认如此，这里显式写死更稳）
target_compile_options(xguard_cli PRIVATE -fPIE -fvisibility=hidden)
target_link_options(xguard_cli PRIVATE -fPIE -pie)
