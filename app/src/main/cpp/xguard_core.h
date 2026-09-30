// XGuard 端上护栏 —— 与 JNI 无关的核心逻辑。
//
// 为什么要把核心从 JNI 里拆出来：
//   验收"端上判定 == PC 判定"这件事，如果只能靠"装 App → 手点界面 → 肉眼看结果"，
//   那它永远只会被验一次，之后必定腐烂。拆出核心后可以另编一个**命令行可执行文件**
//   直接推到设备上跑（`xguard_cli`），端上推理的结果能被脚本拉回 PC 逐条比对。
//
// 拆分原则：核心不认识 JNI，只认识「已渲染好的 prompt 字符串」。
// prompt 的渲染由 Kotlin 侧的 XGuardPrompt 独占负责（端上/PC 共用同一份常量），
// 这里不做任何模板拼接 —— 否则模板就会在两个地方各写一遍，迟早漂移。
#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace xguard {

/// 一次加载好的模型 + context。**非线程安全**（llama_context 本身不是），
/// 多线程调用方必须自己用 [Handle::mtx] 或外层锁串行化。
struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t n_vocab = 0;
    int32_t n_ctx = 0;
    int32_t n_batch = 0;
    std::mutex mtx;
};

/// 全局只初始化一次 ggml/llama 后端。必须在任何 load_model 之前调用。
void backend_init_once();

/// UTF-16 → 标准 UTF-8。
///
/// 存在的理由是一个**很容易漏掉**的坑：JNI 的 `GetStringUTFChars` 返回的不是标准 UTF-8，
/// 而是 **modified UTF-8（CESU-8）** —— 非 BMP 字符（emoji 等）会被编成
/// **两个 3 字节代理对**（6 字节），而不是标准的 4 字节序列。
///
/// 后果：用户在提问里打一个 emoji，端上切出来的 token 序列就和 PC 不一样，
/// 于是 P(sec) 与危险度和 PC 对不上 —— 而报告里完全看不出来，
/// 只会表现为"同样的用例，手机和 PC 的结论偶尔不一样"。
///
/// 所以 JNI 侧必须走 `GetStringChars` 拿 UTF-16，再用这个函数编码成标准 UTF-8。
/// 这个函数不依赖 JNI 类型，因此可以在设备上用 `xguard_cli --selftest` 直接验。
std::string utf16_to_utf8(const char16_t *data, int32_t len);

/// 加载 GGUF。失败返回 nullptr（调用方负责给出可读错误，别静默）。
Handle *load_model(const std::string &path, int32_t n_ctx, int32_t n_threads);

/// 释放模型与 context，并销毁 Handle。
void free_model(Handle *h);

/// 核心推理：喂一条**已渲染好**的 prompt，返回 JSON 文本。
///
/// 返回体永远是合法 JSON（失败时形如 `{"error":"..."}`），字段：
///   `top`: [{"token":"...","p":0.98}, ...]  首位置全词表 softmax 的 top-k
///   `text`: 贪心生成的归因文本
///   `promptTokens` / `generated` / `ms` / `truncated`
///
/// **判定不在这一层**：标签映射、1-P(sec)、阈值、保守策略都在 Kotlin 的
/// XGuardParser 里，端上与 PC 共用同一份实现。这里只搬数据。
std::string classify(Handle *h, const std::string &prompt, int32_t top_k,
                     int32_t max_tokens);

}  // namespace xguard
