package com.xieguojian.aiseclab.core.api

import com.xieguojian.aiseclab.core.model.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本轮测试的 token 消耗计数器（进程级单例）。
 *
 * 为什么需要它：一轮测试的调用分散在三条链路上 —— 被测模型（AttackEngine/TargetCaller）、
 * 裁判模型（LlmJudge）、递归对抗的攻击模型 —— 每条链路各自持有自己的
 * [OpenAiCompatibleClient]，调用点互不相识。想告诉用户"这一轮烧了多少"，就必须有个
 * 共同的账本：各 client 在每次成功响应后按自己的角色记账，UI 只订阅一个 [state]。
 *
 * 三个刻意的取舍：
 * 1. **只记成功响应**。失败/重试的请求端点数不到 usage，宁可不计也不估算 —— 估出来的
 *    数字会被用户当账单看。
 * 2. **端点不回 usage 就不计**（本地 llama.cpp 默认不回）。这会让总数偏低，所以单独记
 *    [Snapshot.unmeteredCalls]，UI 据此提示"有 N 次调用未返回用量"，而不是假装完整。
 * 3. **不随报告归档**。它只服务"跑的时候看一眼"，历史报告 schema 不动。
 */
object TokenMeter {

    enum class Role(val label: String) {
        TARGET("被测"),
        JUDGE("裁判"),
        ATTACKER("攻击"),
        GUARD("护栏"),
    }

    data class Snapshot(
        val total: Long = 0,
        val prompt: Long = 0,
        val completion: Long = 0,
        /** 成功且**带回 usage** 的调用数。 */
        val meteredCalls: Int = 0,
        /** 成功但**没带 usage** 的调用数（端点不支持 / 被网关省略）。 */
        val unmeteredCalls: Int = 0,
        val byRole: Map<Role, Long> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = meteredCalls == 0 && unmeteredCalls == 0

        fun of(role: Role): Long = byRole[role] ?: 0L

        /** 一行摘要，如 `1.2万 token（被测 8,000 · 裁判 4,340）`；完全没调用时返回 null。 */
        fun summary(): String? {
            if (isEmpty) return null
            val parts = Role.values().filter { of(it) > 0 }
                .joinToString(" · ") { "${it.label} ${fmt(of(it))}" }
            val base = "${fmt(total)} token" + if (parts.isNotEmpty()) "（$parts）" else ""
            return if (unmeteredCalls > 0) "$base · $unmeteredCalls 次未返回用量" else base
        }

        companion object {
            /** 过万折算成 `1.2万`，避免长数字撑破一行。 */
            fun fmt(v: Long): String =
                if (v >= 10_000) "%.1f万".format(v / 10_000.0) else "%,d".format(v)
        }
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * 记一次调用。[usage] 为 null 表示端点没回用量 —— 只累加 [Snapshot.unmeteredCalls]。
     * 多协程并发调用（4 路并发跑用例），故加锁。
     */
    @Synchronized
    fun record(role: Role, usage: Usage?) {
        val cur = _state.value
        if (usage == null) {
            _state.value = cur.copy(unmeteredCalls = cur.unmeteredCalls + 1)
            return
        }
        // 少数端点只回 total_tokens 或三值不自洽：total 缺失时用 p+c 兜底
        val p = usage.prompt_tokens.toLong()
        val c = usage.completion_tokens.toLong()
        val t = if (usage.total_tokens > 0) usage.total_tokens.toLong() else p + c
        _state.value = cur.copy(
            total = cur.total + t,
            prompt = cur.prompt + p,
            completion = cur.completion + c,
            meteredCalls = cur.meteredCalls + 1,
            byRole = cur.byRole + (role to (cur.of(role) + t)),
        )
    }

    /**
     * 后端委托模式：服务端已按角色汇总好用量，且每次发来的都是**累计值**（不是增量），
     * 所以这里是覆盖而非累加。角色名用字符串传，按枚举名宽松匹配。
     */
    @Synchronized
    fun setFromBackend(
        total: Long, prompt: Long, completion: Long, calls: Int, byRole: Map<String, Long>
    ) {
        if (total <= 0 && calls <= 0) return
        val mapped = byRole.entries.mapNotNull { (k, v) ->
            Role.entries.firstOrNull { it.name.equals(k, ignoreCase = true) }?.let { it to v }
        }.toMap()
        _state.value = Snapshot(
            total = total,
            prompt = prompt,
            completion = completion,
            meteredCalls = calls,
            byRole = mapped,
        )
    }

    /** 新一轮测试开始时清零。两条入口（端上服务 / 后端委托）都要调，别把上一轮的数字带进来。 */
    @Synchronized
    fun reset() {
        _state.value = Snapshot()
    }
}

/**
 * 把实时计数快照转成可以落进报告的账单（[TokenUsage]）。
 *
 * 转换点**只有这一处** —— 报告页、Markdown 导出、HTML 导出显示的都是这个结果，
 * 别再各自从 [TokenMeter.Snapshot] 拼一份（那样「跑的时候显示 1.2万、报告里写 12000」)。
 *
 * 一次调用都没有（[TokenMeter.Snapshot.isEmpty]）时返回 `null`，让报告能明确区分
 * 「没记录」与「没烧」。
 */
fun TokenMeter.Snapshot.toTokenUsage(): TokenUsage? {
    if (isEmpty) return null
    return TokenUsage(
        total = total,
        prompt = prompt,
        completion = completion,
        meteredCalls = meteredCalls,
        unmeteredCalls = unmeteredCalls,
        byRole = byRole.entries.associate { (role, v) -> role.label to v },
    )
}
