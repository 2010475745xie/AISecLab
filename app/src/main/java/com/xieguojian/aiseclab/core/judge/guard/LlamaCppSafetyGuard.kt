package com.xieguojian.aiseclab.core.judge.guard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 端上护栏：llama.cpp + GGUF，完全离线。
 *
 * ## 与 [HttpSafetyGuard] 的关系
 * 两者是**同一个契约的两个实现** —— 都往 `XGuardParser` 里喂
 * `(生成文本, 首位置 top-k)`，所以判定口径、阈值、失效语义完全一致。
 * 切换只影响"算力在哪"，不影响"结论怎么得出"。
 *
 * ## 输入从哪来
 * 不复用服务端模板：端上没有 jinja 引擎，且模板一旦在两边各写一遍就会漂移。
 * 所以让 [XGuardPrompt] 做**唯一**的渲染实现，PC 与端上共用同一份常量
 * （已用 `usage.prompt_tokens` 逐 token 对齐验证过）。
 *
 * ## 并发
 * llama_context 不是线程安全的，而攻击引擎默认 4 路并发。
 * 这里用一把 [mutex] 把**加载与推理**串起来（单飞）：护栏调用必须排队，
 * 否则会出现难以复现的乱码输出或 crash。模型只加载一次，排队成本可接受。
 *
 * ## 失效语义
 * 加载失败、native 报错、JSON 解析失败 —— 一律转成带 `error` 的 [GuardVerdict]，
 * 由 [GuardJudge] 降级回规则层。**护栏坏了绝不等于被测模型失守。**
 */
class LlamaCppSafetyGuard(
    private val modelPath: String,
    private val nCtx: Int = 2048,
    /**
     * 推理线程数。手机上是"大核数量"级别的量级最划算，
     * 用满 8 核反而常因热降频变慢，所以默认压到 4。
     */
    private val nThreads: Int = 4,
    /** 取首位置概率前 K 名。与 PC 侧 `top_logprobs` 对齐，保证两条路径同分布。 */
    private val topK: Int = 20,
    /** 生成上限。归因文本是报告证据链的主体，给够别抠。 */
    private val maxTokens: Int = 320
) : SafetyGuard {

    override val name: String = "XGuard-本地(${File(modelPath).name})"

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    override suspend fun classify(prompt: String, response: String): GuardVerdict {
        val rendered = XGuardPrompt.render(prompt, response)

        val raw = mutex.withLock {
            val h = loadLocked()
                ?: return GuardVerdict(error = "端上护栏模型不可用：$modelPath 未加载成功")
            runCatching { LlamaCppGuard.nativeClassify(h, rendered, topK, maxTokens) }
                .getOrElse { e ->
                    return GuardVerdict(error = "端上护栏推理失败：${e.message}")
                }
        } ?: return GuardVerdict(error = "端上护栏返回空结果")

        val parsed = LlamaCppGuard.parseResult(raw)
            ?: return GuardVerdict(error = "端上护栏输出无法解析（非 UTF-8 或字段不符）")
        parsed.error?.let { return GuardVerdict(error = "端上护栏：$it") }

        // 与 PC 路径在这里汇合：同一个解析器、同一套阈值语义
        return XGuardParser.parse(parsed.text, parsed.top.map { it.token to it.p })
    }

    /** 懒加载并缓存句柄。返回 null 表示不可用。 */
    private fun loadLocked(): Long? {
        if (handle != 0L) return handle
        if (!File(modelPath).isFile) return null
        return runCatching {
            LlamaCppGuard.ensureLibrary()
            LlamaCppGuard.nativeLoadModel(modelPath, nCtx, nThreads).also { handle = it }
        }.getOrNull()?.takeIf { it != 0L }
    }

    /** 释放 native 资源。建议在测试结束或退出后台时调用。 */
    suspend fun close() = mutex.withLock {
        if (handle != 0L) {
            runCatching { LlamaCppGuard.nativeFreeModel(handle) }
            handle = 0L
        }
    }
}
