package com.xieguojian.aiseclab.core.api

import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 后端代理客户端：把一轮完整测试委托给 AISecLab 后端（FastAPI），
 * 经 SSE 进度流订阅进度，结束后拉取完整报告回端侧渲染。
 *
 * 与端上 [com.xieguojian.aiseclab.core.TestSession] 同接口语义——都产出 [Report]，
 * 前台服务（[com.xieguojian.aiseclab.ui.TestRunService]）按"是否配置后端地址"二选一。
 *
 * 协议（与 docs/端后端架构方案.md §3 及 backend/app/main.py 一致）：
 * - POST /v1/sessions              创建任务，立即返回 { sessionId }
 * - GET  /v1/sessions/{id}/stream   SSE：每行 `data: {json}`，event = progress | done | error
 * - GET  /v1/reports/{id}           拉完整报告 JSON（直接反序列化为端侧 [Report]）
 *
 * 裁判模型：随请求体由客户端带来（BYOK）——比赛/共享服务器通常不预置裁判模型。
 * 被测模型 Key 与裁判模型 Key 都只随请求发往后端，仅后端任务周期内存留存，本端不持久化。
 *
 * 安全：
 * - 被测/裁判模型 Key 随请求体发往后端，仅后端任务周期内存留存（后端 docs 方案 §4 方案2）；本端不持久化该 Key。
 * - 后端若设了 API_TOKEN，则所有接口带 `Authorization: Bearer <token>`（原生 App 无 CORS 问题）。
 */
class BackendClient {

    private val json = Json { ignoreUnknownKeys = true }

    // SSE 可能跑很久：关掉读超时（连接超时保留），靠流结束/异常释放。
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class SessionRequest(
        val target: ModelProfile,
        val aux: AuxModelProfile? = null,
        val judge: ModelProfile,
        val guardMode: String = "none",
        val dimensions: List<Dimension>,
        val recursive: Boolean = false,
        val attackBudget: Int = 6,
        val useStaticCases: Boolean = true,
        // 并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略）。
        // 字段名必须与后端 `SessionRequest.useJailbreakVariants` 逐字一致 ——
        // 后端忽略未知键，名字对不上是**静默丢弃**（变体集永远不跑，且不报错）。
        val useJailbreakVariants: Boolean = false,
        // ── 断点续测 ──
        // 传上次中断的 sessionId 就从那个检查点接着跑：后端把**已拿到结果的用例直接复用、
        // 不重跑**，只补跑剩下的，合成一份完整报告。
        // ⚠️ 必须同时带完整 request（target / judge / aux…）：检查点里不存 API key，
        // 续跑要用的凭证只能由端上重新带一次（见 backend/app/checkpoint.py 的 docstring）。
        // 字段名与后端 `SessionRequest.resumeFrom` 逐字一致，对不上是静默丢弃（永远从头跑）。
        val resumeFrom: String? = null
    )

    /**
     * 「可续跑的会话」摘要（`GET /v1/sessions/incomplete`）。
     *
     * 只是摘要、**不含结果本体** —— 50 条结果有几百 KB，列表接口不该驮着它。
     * 点「继续」时把 [sessionId] 填进下次请求的 `resumeFrom` 就够了。
     */
    @Serializable
    data class IncompleteSession(
        val sessionId: String,
        val state: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
        /** 上次是怎么断的（后端留下的一句话）；null = 没记录。 */
        val error: String? = null
    )

    @Serializable
    private data class IncompleteList(val sessions: List<IncompleteSession> = emptyList())

    @Serializable
    private data class SessionCreated(val sessionId: String)

    /**
     * 后端累计的 token 用量（每次都发**累计值**，不是增量）。
     * `internal`：被同样放开为 internal 的 [SseEvent] 引用，不能更窄。
     */
    @Serializable
    internal data class SseTokens(
        val total: Long = 0,
        val prompt: Long = 0,
        val completion: Long = 0,
        val calls: Int = 0,
        val byRole: Map<String, Long> = emptyMap()
    )

    /**
     * SSE 事件。后端字段全小写驼峰，与 data class 字段名一一对应；
     * 多数字段有默认值，未知字段被 json(ignoreUnknownKeys) 容忍。
     *
     * `internal`（非 `private`）：日志行的构造抽在 [progressLogLines] 纯函数里，
     * 单测需要构造事件来钉住"message 到底有没有被显示出来"这段接线。
     */
    @Serializable
    internal data class SseEvent(
        val event: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val dimension: String? = null,
        val reportId: String? = null,
        val message: String? = null,
        val tokens: SseTokens? = null
    )

    /**
     * 把整轮测试交给后端跑，期间经 [onProgress] 回传进度、[onLog] 回传进程日志（终端窗口用），
     * 结束返回完整 [Report]。
     * 失败（连接/非 2xx/后端 error 事件）抛 [ApiException]，由调用方转成 Error 状态。
     */
    suspend fun run(
        baseUrl: String,
        target: ModelProfile,
        aux: AuxModelProfile?,
        judge: ModelProfile,
        dimensions: Set<Dimension>,
        recursive: Boolean,
        variants: Boolean,
        token: String?,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        onLog: ((String) -> Unit)? = null,
        resumeFrom: String? = null
    ): Report = withContext(Dispatchers.IO) {
        val sid = createSession(baseUrl, target, aux, judge, dimensions, recursive, variants,
            token, resumeFrom)
        if (resumeFrom.isNullOrBlank()) {
            onLog?.invoke("▶ 已委托后端测试：目标 ${target.model} @ $baseUrl")
        } else {
            // 续跑的日志必须**说清复用了上次的会话**，否则用户会以为又是从 0 开始跑
            //（进度会很快跳到 N/50，看起来像跳号）。
            onLog?.invoke("↻ 继续上次未跑完的测试（sessionId = $resumeFrom）")
            onLog?.invoke("  已完成的用例直接复用、不重跑；只补跑剩下的。目标 ${target.model} @ $baseUrl")
        }
        onLog?.invoke("  sessionId = $sid")
        subscribeStream(baseUrl, sid, token, onProgress, onLog)
        getReport(baseUrl, sid, token)
    }

    /**
     * 拉「可续跑的会话」列表（断点续测入口）。
     *
     * 失败时返回空表而不是抛异常：这只是首页的一张提示卡，**后端连不上不该让配置页炸掉**
     * —— 用户此刻可能正要去改后端地址。真正的错误会在点「开始测试」时以明确的方式报出来。
     */
    suspend fun listIncomplete(baseUrl: String, token: String?): List<IncompleteSession> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext emptyList()
            val req = Request.Builder()
                .url(join(baseUrl, "/v1/sessions/incomplete"))
                .get()
                .apply { auth(this, token) }
                .build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<IncompleteSession>()
                    val text = resp.body?.string() ?: ""
                    json.decodeFromString(IncompleteList.serializer(), text).sessions
                }
            }.getOrDefault(emptyList())
        }

    /** 丢掉某个会话的断点（用户放弃续跑）。失败静默返回 false，由调用方决定怎么提示。 */
    suspend fun dropCheckpoint(baseUrl: String, sid: String, token: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || sid.isBlank()) return@withContext false
            val req = Request.Builder()
                .url(join(baseUrl, "/v1/sessions/$sid/checkpoint"))
                .delete()
                .apply { auth(this, token) }
                .build()
            runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    /**
     * 连通性探测：GET {baseUrl}/health。
     * /health 是后端公开存活探针（中间件不校验 token），返回 2xx 即视为后端在线。
     * 供「测试后端」设置页的「测试连通性」按钮使用，让用户填完地址立刻验证能否连上。
     */
    suspend fun ping(baseUrl: String, token: String?): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        val req = Request.Builder()
            .url(join(baseUrl, "/health"))
            .get()
            .apply { auth(this, token) }
            .build()
        runCatching { http.newCall(req).execute().use { it.isSuccessful } }
            .getOrDefault(false)
    }

    private fun auth(builder: Request.Builder, token: String?) {
        if (!token.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $token")
    }

    private fun createSession(
        baseUrl: String,
        target: ModelProfile,
        aux: AuxModelProfile?,
        judge: ModelProfile,
        dimensions: Set<Dimension>,
        recursive: Boolean,
        variants: Boolean,
        token: String?,
        resumeFrom: String? = null
    ): String {
        val body = json.encodeToString(
            SessionRequest.serializer(),
            SessionRequest(
                target = target,
                aux = aux,
                judge = judge,
                dimensions = dimensions.toList(),
                recursive = recursive,
                useJailbreakVariants = variants,
                resumeFrom = resumeFrom?.takeIf { it.isNotBlank() }
            )
        ).toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(join(baseUrl, "/v1/sessions"))
            .post(body)
            .addHeader("Content-Type", "application/json")
            .apply { auth(this, token) }
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ApiException("创建会话失败 HTTP ${resp.code}: ${text.take(300)}", resp.code)
            }
            return json.decodeFromString(SessionCreated.serializer(), text).sessionId
        }
    }

    private suspend fun subscribeStream(
        baseUrl: String,
        sid: String,
        token: String?,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ) {
        val req = Request.Builder()
            .url(join(baseUrl, "/v1/sessions/$sid/stream"))
            .get()
            .apply { auth(this, token) }
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string() ?: ""
                throw ApiException("订阅进度流失败 HTTP ${resp.code}: ${text.take(300)}", resp.code)
            }
            val source = resp.body?.source()
                ?: throw ApiException("空响应体", resp.code)

            while (true) {
                val line = source.readUtf8Line() ?: break // 连接断开 = 流结束
                if (!line.startsWith("data:")) continue    // 注释行 / 帧间空行：跳过
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue

                val ev = runCatching { json.decodeFromString(SseEvent.serializer(), payload) }
                    .getOrNull() ?: continue // 解析不出的行忽略，别因一帧坏数据断整轮
                when (ev.event) {
                    "progress" -> {
                        // 后端每帧都带累计用量，直接覆盖式写入 —— 与端上直连的累加语义不同
                        ev.tokens?.let { t ->
                            TokenMeter.setFromBackend(t.total, t.prompt, t.completion, t.calls, t.byRole)
                        }
                        onProgress(ev.completed, ev.total)
                        // 计数行 + 后端随帧带来的「该条用例摘要」（判定 + 输入/回答/依据片段），
                        // 与本机跑时 AttackEngine 打的日志同构。构造逻辑收敛在
                        // SseLogFormat.progressLogLines（纯函数，有单测；**按行拆开**逐条入日志，
                        // 整段塞成一个日志项会让终端里一条用例占掉一大块，见 TestRunStateHolder.appendLog）。
                        progressLogLines(ev).forEach { onLog?.invoke(it) }
                    }
                    "done" -> {
                        onLog?.invoke("✓ 后端已完成本轮测试，正在拉取报告…")
                        break
                    }
                    "error" -> throw ApiException(ev.message ?: "后端报告任务错误", null)
                    else -> { /* 未知事件忽略 */ }
                }
            }
        }
    }

    private fun getReport(baseUrl: String, sid: String, token: String?): Report {
        val req = Request.Builder()
            .url(join(baseUrl, "/v1/reports/$sid"))
            .get()
            .apply { auth(this, token) }
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ApiException("拉取报告失败 HTTP ${resp.code}: ${text.take(300)}", resp.code)
            }
            // 后端 Report 与端侧 Report 同构（枚举已用 @SerialName 对齐 wire 值），直接反序列化。
            return json.decodeFromString(Report.serializer(), text)
        }
    }

    private fun join(base: String, path: String): String {
        // 优化点①：用户常漏写 http://（如填 192.168.1.10:8000），OkHttp 会因 scheme==null 直接抛错。
        // 这里自动补 scheme，省去用户手动带协议的负担，也消除"填了地址连不上"的头号根因。
        var b = base.trim()
        if (!b.startsWith("http://", ignoreCase = true) && !b.startsWith("https://", ignoreCase = true)) {
            b = "http://$b"
        }
        b = b.trimEnd('/')
        return "$b$path"
    }
}
