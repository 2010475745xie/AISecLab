package com.xieguojian.aiseclab.core.judge.guard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * llama.cpp 的 JNI 薄绑定。**刻意做得很笨**：只负责"喂 prompt、吐首位置的 top-k 与生成文本"，
 * 不掺任何判定逻辑。
 *
 * ## 为什么不让 native 直接判风险
 * 端上与 PC 的判定必须一致，而"一致"只能靠**共用同一份判定代码**来保证，
 * 不能靠"两边各写一遍、期望结果相同"。所以：
 *
 * ```
 * [PC]   llama-server → HTTP JSON → OpenAiCompatibleClient.parseCompletion → XGuardParser
 * [端上] libxguard.so → JNI JSON   → LlamaCppGuard.Result                   → XGuardParser
 * ```
 * 两条路径在 `XGuardParser` 汇合 —— 标签映射、`1 - P(sec)` 的危险度换算、
 * "解析不出来就返回 null"的保守策略，全都是同一份实现。
 *
 * ## 为什么返回 ByteArray 而不是 String
 * JNI 的 `NewStringUTF` 要求 **modified UTF-8**：不接受 4 字节序列、
 * 且要求 NUL 编码成 `C0 80`。而 top-k 里的 token 片段是模型词表里的原始字节，
 * 完全可能出现非法 UTF-8 片段或 4 字节字符 —— 直接当 String 传会静默损坏内容。
 * 传 `jbyteArray` 让 Kotlin 用标准 UTF-8 解码（非法序列变 U+FFFD，不抛错）才是安全的。
 */
internal object LlamaCppGuard {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var libraryLoaded = false

    /** 加载 natives。失败时抛异常，由调用方转成"护栏不可用"而不是崩溃。 */
    fun ensureLibrary() {
        if (libraryLoaded) return
        synchronized(this) {
            if (libraryLoaded) return
            System.loadLibrary("xguard")
            libraryLoaded = true
        }
    }

    /**
     * 加载模型。返回 0 表示失败，非 0 为 native 句柄。
     * 这是耗时操作（484 MB 从磁盘 mmap + 元数据解析），必须在后台线程调用。
     */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long

    /** 释放句柄。[handle] 为 0 时安全无操作。 */
    external fun nativeFreeModel(handle: Long)

    /**
     * 判定一次。
     *
     * @param prompt 已由 [XGuardPrompt] 渲染好的完整输入（端上不依赖服务端模板）。
     * @param topK 取首位置概率前 K 名。
     * @param maxTokens 最大生成 token 数（含作为结论的首 token）。
     * @return UTF-8 编码的 [Result] JSON；失败时返回带 `error` 字段的 JSON。
     */
    external fun nativeClassify(handle: Long, prompt: String, topK: Int, maxTokens: Int): ByteArray?

    /** native 返回的结构。字段名与 `src/main/cpp/xguard_jni.cpp` 一一对应。 */
    @Serializable
    data class Result(
        val top: List<TokenProb> = emptyList(),
        val text: String = "",
        val promptTokens: Int = 0,
        val ms: Long = 0,
        val error: String? = null
    )

    @Serializable
    data class TokenProb(val token: String = "", val p: Double = 0.0)

    /** 解析 native 的 JSON 输出。解析失败返回 null（上层转成"护栏不可用"）。 */
    fun parseResult(raw: ByteArray?): Result? {
        if (raw == null || raw.isEmpty()) return null
        return runCatching {
            json.decodeFromString(Result.serializer(), String(raw, Charsets.UTF_8))
        }.getOrNull()
    }
}
