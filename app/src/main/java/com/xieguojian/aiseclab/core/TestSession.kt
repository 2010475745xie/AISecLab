package com.xieguojian.aiseclab.core

import android.app.Application
import com.xieguojian.aiseclab.core.api.ChatMessage
import com.xieguojian.aiseclab.core.api.OpenAiCompatibleClient
import com.xieguojian.aiseclab.core.api.TokenMeter
import com.xieguojian.aiseclab.core.engine.AttackModule
import com.xieguojian.aiseclab.core.engine.modules.ConsistencyModule
import com.xieguojian.aiseclab.core.engine.modules.FactualityModule
import com.xieguojian.aiseclab.core.engine.modules.RobustnessModule
import com.xieguojian.aiseclab.core.engine.modules.SecurityModule
import com.xieguojian.aiseclab.core.judge.Judge
import com.xieguojian.aiseclab.core.judge.LlmJudge
import com.xieguojian.aiseclab.core.judge.RuleJudge
import com.xieguojian.aiseclab.core.judge.guard.GuardJudge
import com.xieguojian.aiseclab.core.judge.guard.HttpSafetyGuard
import com.xieguojian.aiseclab.core.judge.guard.LlamaCppSafetyGuard
import com.xieguojian.aiseclab.core.judge.guard.LlmSafetyGuard
import com.xieguojian.aiseclab.core.judge.guard.OnDeviceGuardModel
import com.xieguojian.aiseclab.core.judge.guard.SafetyGuard
import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.GuardMode
import com.xieguojian.aiseclab.core.model.GuardProfile
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.model.RoleSource

/**
 * 一次测试的「配置 → 编排」工厂。
 *
 * 把原来散在 [com.xieguojian.aiseclab.ui.TestViewModel.startTest] 里的建链逻辑抽出来，
 * 让前台服务（[com.xieguojian.aiseclab.ui.TestRunService]）也能复用同一套编排，
 * 不再依赖 ViewModel 的内存状态 —— 这样切后台 / 息屏时协程跑在服务进程里，不会被回收。
 *
 * 来源解析与护栏构造逻辑和原 VM 完全一致：
 * - 裁判 / 攻击者端点按 [RoleSource] 解析（AUTO=有 aux 用 aux 否则目标自身）；
 * - 安全维度最终裁决始终是护栏，这里只决定非安全维度 LLM 裁判来源与递归攻击者；
 * - USER_LLM 护栏取「裁判来源」端点，单模型也能用。
 */
class TestSession(
    private val app: Application,
    private val target: ModelProfile,
    private val aux: AuxModelProfile?,
    private val guardProfile: GuardProfile?,
    private val roleSource: RoleSource,
    private val dimensions: Set<Dimension>,
    private val useRecursive: Boolean
) {

    private var safetyGuard: SafetyGuard? = null

    private data class Endpoint(val baseUrl: String, val apiKey: String, val model: String)

    private fun resolveEndpoint(): Endpoint {
        return when (roleSource) {
            RoleSource.TARGET -> Endpoint(target.baseUrl, target.apiKey, target.model)
            RoleSource.AUX -> aux?.let { Endpoint(it.baseUrl, it.apiKey, it.model) }
                ?: Endpoint(target.baseUrl, target.apiKey, target.model)
            RoleSource.AUTO -> if (aux != null) Endpoint(aux.baseUrl, aux.apiKey, aux.model)
            else Endpoint(target.baseUrl, target.apiKey, target.model)
        }
    }

    private fun buildGuard(): SafetyGuard? {
        val g = guardProfile ?: return null
        return when (g.mode) {
            GuardMode.HTTP_SERVICE -> HttpSafetyGuard(
                baseUrl = g.baseUrl,
                apiKey = g.apiKey,
                model = g.model
            )

            GuardMode.ON_DEVICE -> LlamaCppSafetyGuard(
                modelPath = OnDeviceGuardModel.file(app, g.tier).absolutePath
            )

            GuardMode.USER_LLM -> {
                val ep = resolveEndpoint()
                val c = OpenAiCompatibleClient(ep.baseUrl, ep.apiKey, ep.model, TokenMeter.Role.GUARD)
                LlmSafetyGuard(
                    chat = { msgs -> c.chat(msgs, temperature = 0.0) },
                    modelLabel = ep.model
                )
            }
        }
    }

    /**
     * 跑完整一轮测试。进度通过 [onProgress] 回传（在 IO 线程调用，调用方负责更新 UI / 通知）；
     * [onLog] 在关键节点回传一行进程日志（终端窗口用）。
     * 结束后无论成败都释放端上护栏持有的 ~460MB 权重，否则下一轮会再加载一份 → OOM。
     */
    suspend fun run(
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ): Report {
        // 新一轮开跑：清掉上一轮的 token 计数（端上服务与后端委托两条入口都经过这里）
        TokenMeter.reset()
        val targetClient = OpenAiCompatibleClient(
            target.baseUrl, target.apiKey, target.model, TokenMeter.Role.TARGET
        )

        val judgeEp = resolveEndpoint()
        val llmJudge = judgeEp.let { ep ->
            val c = OpenAiCompatibleClient(ep.baseUrl, ep.apiKey, ep.model, TokenMeter.Role.JUDGE)
            LlmJudge(chat = { msgs -> c.chat(msgs, temperature = 0.0) })
        }
        val ruleJudge = RuleJudge(llmJudge = llmJudge)

        safetyGuard = buildGuard()
        val judge: Judge = if (guardProfile != null && safetyGuard != null) {
            GuardJudge(
                guard = safetyGuard!!,
                rules = ruleJudge,
                threshold = guardProfile.threshold,
                reviewBand = guardProfile.reviewBand
            )
        } else {
            ruleJudge
        }

        val attackerEp = resolveEndpoint()
        val attackerChat: (suspend (List<ChatMessage>) -> String)? = attackerEp.let { ep ->
            val c = OpenAiCompatibleClient(ep.baseUrl, ep.apiKey, ep.model, TokenMeter.Role.ATTACKER)
            val fn: suspend (List<ChatMessage>) -> String = { msgs -> c.chat(msgs, temperature = 1.0) }
            fn
        }

        val modules: List<AttackModule> = listOf(
            SecurityModule(), ConsistencyModule(), FactualityModule(), RobustnessModule()
        ).filter { it.dimension in dimensions }

        val runner = TestRunner(targetClient, judge, target)
        try {
            return runner.run(
                modules = modules,
                useRecursive = useRecursive,
                attackerChat = attackerChat,
                onProgress = onProgress,
                onLog = onLog
            )
        } finally {
            (safetyGuard as? LlamaCppSafetyGuard)?.close()
        }
    }
}
