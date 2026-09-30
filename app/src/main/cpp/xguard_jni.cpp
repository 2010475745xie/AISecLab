// XGuard 端上护栏 —— llama.cpp 的 JNI 薄封装。
//
// 设计原则：**native 不做任何判定**。
// 它只负责"喂 prompt → 取首位置 top-k 概率 → 贪心生成归因文本"，然后把结果交给 Kotlin。
// 判定口径（标签映射、1 - P(sec) 的换算、保守策略）全部复用 Kotlin 侧的 XGuardParser，
// 与 PC 路径共用同一份实现。这样"端上/PC 判定一致"是结构上保证的，而不是靠两边各写一遍碰运气。
//
// 另一个刻意选择：nativeClassify 返回 jbyteArray 而不是 jstring。
// JNI 的 NewStringUTF 要求 modified UTF-8（不接受 4 字节序列、NUL 必须编成 C0 80），
// 而 top-k 里的 token 片段是词表原始字节，可能是非法 UTF-8 片段。
// 返回字节数组让 Kotlin 用标准 UTF-8 解码才不会损坏内容（见 LlamaCppGuard.kt 注释）。
//
// 真正的推理实现在 xguard_core.cpp —— 拆出去是为了能另编一个设备端命令行工具
// （xguard_cli）来验收"端上判定 == PC 判定"，见 xguard_core.h 的说明。

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "xguard_core.h"

#define TAG "XGuardJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/**
 * jstring → 标准 UTF-8。
 *
 * **不要用 `GetStringUTFChars`。** 它返回的是 modified UTF-8（CESU-8）：
 * 非 BMP 字符（emoji 等）会变成两个 3 字节代理对（共 6 字节），而不是标准 4 字节。
 * 后果是"用户在 prompt 里打了个 emoji，端上的 token 序列就和 PC 不一样"，
 * 于是危险度与 PC 对不上，而报告里完全看不出来 —— 这类静默偏差最难查。
 *
 * 所以这里拿 UTF-16（`GetStringChars` 给的是 UTF-16，没有这个编码问题），
 * 再用 `xguard::utf16_to_utf8` 编成标准 UTF-8。
 * 该转换函数与 JNI 无关，可用 `xguard_cli --selftest` 在真机上直接验。
 */
std::string jstring_to_utf8(JNIEnv *env, jstring js) {
    if (js == nullptr) return {};
    const jsize len = env->GetStringLength(js);
    if (len <= 0) return {};
    const jchar *chars = env->GetStringChars(js, nullptr);
    if (chars == nullptr) return {};
    std::string out = xguard::utf16_to_utf8(
            reinterpret_cast<const char16_t *>(chars), static_cast<int32_t>(len));
    env->ReleaseStringChars(js, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring j_path, jint n_ctx, jint n_threads) {
    const std::string path = jstring_to_utf8(env, j_path);
    if (path.empty()) return 0;

    xguard::Handle *h = xguard::load_model(path, n_ctx, n_threads);
    if (h == nullptr) {
        LOGE("加载模型失败: %s", path.c_str());
        return 0;
    }

    LOGI("模型就绪: n_vocab=%d n_ctx=%d n_threads=%d", h->n_vocab, h->n_ctx, n_threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeFreeModel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    xguard::free_model(reinterpret_cast<xguard::Handle *>(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_com_xieguojian_aiseclab_core_judge_guard_LlamaCppGuard_nativeClassify(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring j_prompt, jint top_k,
        jint max_tokens) {
    auto *h = reinterpret_cast<xguard::Handle *>(handle);
    if (h == nullptr) return nullptr;

    const std::string prompt = jstring_to_utf8(env, j_prompt);

    const std::string out = xguard::classify(h, prompt, top_k, max_tokens);

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                           reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

}  // extern "C"
