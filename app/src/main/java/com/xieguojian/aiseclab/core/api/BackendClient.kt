package com.xieguojian.aiseclab.core.api

import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * SSE 空闲读超时（秒）。见 [BackendClient] 里 `http` 的注释：
 * 后端每 15s 发一个 `: keepalive` 心跳，所以"60 秒一个字节都没有"只可能是连接真的断了。
 */
private const val SSE_IDLE_TIMEOUT_SECONDS = 60L

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

    /**
     * SSE 的**空闲读超时**：多久收不到任何字节（含后端每 15s 一次的 `: keepalive` 心跳）就判连接已死。
     *
     * 这里以前是 `0`（永不过时），注释理由是"SSE 可能跑很久"。代价是**永远发现不了断线**：
     * 后端被杀 / 手机切网 / NAT 悄悄回收连接时，端上一直阻塞在 `readUtf8Line()`，
     * 不报错、不重试、不提示 —— 界面就那样停在某个数字上。2026-10-16 实测：
     * 22:42 之后整整 25 分钟零输出，UI 卡在 111/314，用户唯一能做的是手动点停止。
     *
     * 取 60s = 心跳间隔（后端 `main.HEARTBEAT_SECONDS = 15`）的 4 倍：
     * 单条用例跑一两分钟不出事件是完全正常的（等被测模型出完整回答 + 裁判裁决），
     * 但**心跳不会停**，所以正常长跑不会误触发；真断了最多 1 分钟就报错。
     * 注意 OkHttp 的 readTimeout 是"两次读取之间的间隔上限"，不是整轮总时长上限 ——
     * 只要心跳还在，跑两小时也不会超时。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(SSE_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 当前正在进行的调用（进度流订阅）。
     *
     * 用来让「停止」**真正生效**：`TestRunService.stopRun()` 只做 `scope.cancel()`，
     * 而协程取消**掐不断已经阻塞在 OkHttp `execute()` / `readUtf8Line()` 里的线程**
     * —— 那个协程会继续读流、继续回写进度和日志。2026-10-16 的日志实锤了这个后果：
     * 用户停止后又开了一轮，旧协程把旧会话的进度写进了**新一轮的日志文件**，
     * 界面上进度从 176 掉回 2。有了这个字段，[cancel] 就能立刻断掉真正的那根连接。
     */
    @Volatile
    private var activeCall: Call? = null

    /**
     * 当前这一轮在后端的 sessionId（`POST /v1/sessions` 返回的那个）。
     *
     * 存在的唯一目的：让「停止」能**通知后端真的停**。只有 [cancel]（断连接）是不够的 ——
     * 后端 `_run` 对端上断开毫不知情，会继续对着上游烧 token 烧一两个小时，
     * 期间 `running` 恒为 True ⇒ 续跑和丢弃断点全被 409 挡住。
     * 2026-10-18 用户实测原话：「已经停止并废弃了，断点续测依然正在运行而且无法丢弃断点」。
     */
    @Volatile
    var activeSessionId: String? = null
        private set

    /**
     * 收敛当前正在进行的进度流订阅（用户点「停止」时调）。
     *
     * 只取消本端这条 HTTP 连接。**光靠它不够** —— 后端那一轮会继续跑完。
     * 要真停请用 [cancelSession]（`POST /v1/sessions/{sid}/cancel`）。
     *
     * 注意：真停之后**续跑能力不受影响** —— 已完成的结果早就逐条落进检查点了，
     * 状态被标成 `interrupted`，用户照样能在配置页点「继续上次测试」接着跑剩下的。
     * 所以「停止就真停」不损失任何东西，只是不再白烧 token。
     */
    fun cancel() {
        runCatching { activeCall?.cancel() }
    }

    /**
     * 让**后端**真正停下这一轮（`POST /v1/sessions/{sid}/cancel`）。
     *
     * 失败静默返回 false：停止是"尽力而为"的操作，报错弹窗只会盖住用户正在做的事。
     * 后端那边即使没收到，重启服务时 `sweep_running()` 也会把遗留的 running 清成 interrupted。
     */
    suspend fun cancelSession(baseUrl: String, sid: String, token: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || sid.isBlank()) return@withContext false
            val req = Request.Builder()
                .url(join(baseUrl, "/v1/sessions/$sid/cancel"))
                .post(ByteArray(0).toRequestBody(null))
                .apply { auth(this, token) }
                .build()
            runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

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
        val tokens: SseTokens? = null,
        /**
         * 累计「复用（未重跑、不计费）」条数，缺省 0 = 全新一轮。
         * 界面凭它把进度说成「x/y（复用 a / 新跑 b）」，否则续跑时用户分不清
         * 是从断点接着跑、还是又从头烧了一遍 token（2026-10-16 加，后端同名下发）。
         */
        val resumed: Int = 0
    )

    /**
     * 把整轮测试交给后端跑，期间经 [onProgress] 回传进度（完成数 / 总数 / **其中复用数**）、
     * [onLog] 回传进程日志（终端窗口用），结束返回完整 [Report]。
     * 失败（连接/非 2xx/后端 error 事件/流中途断掉）抛 [ApiException]，由调用方转成 Error 状态。
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
        onProgress: suspend (completed: Int, total: Int, resumed: Int) -> Unit,
        onLog: ((String) -> Unit)? = null,
        resumeFrom: String? = null
    ): Report = withContext(Dispatchers.IO) {
        val sid = createSession(baseUrl, target, aux, judge, dimensions, recursive, variants,
            token, resumeFrom)
        // 记下来给「停止」用：要通知后端掐掉这一轮，就必须知道 sid。
        // finally 里清空 —— 跑完了就没有"正在跑的会话"可停了。
        activeSessionId = sid
        try {
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
        } finally {
            activeSessionId = null
        }
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
        onProgress: suspend (completed: Int, total: Int, resumed: Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ) {
        val req = Request.Builder()
            .url(join(baseUrl, "/v1/sessions/$sid/stream"))
            .get()
            .apply { auth(this, token) }
            .build()

        // 记下 call 供 [cancel] 用（「停止」要能真正掐断这根连接，见 activeCall 的注释）。
        // ⚠️ 必须 try/finally 清空：否则下一轮开始时会去 cancel 一根早就结束的 call。
        val call = http.newCall(req)
        activeCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string() ?: ""
                    throw ApiException("订阅进度流失败 HTTP ${resp.code}: ${text.take(300)}", resp.code)
                }
                val source = resp.body?.source()
                    ?: throw ApiException("空响应体", resp.code)

                // 是否读到过后端的 `done`。没读到就结束 = 流半路断了（不是正常收尾），
                // 必须报错而不是硬着头皮去拉报告 —— 拉报告只会拿到一个让人摸不着头脑的
                // 「report not ready 404」，用户根本不知道发生了什么（2026-10-16 前就是这样）。
                var sawDone = false
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: InterruptedIOException) {
                        // OkHttp 的读超时（SocketTimeoutException 是它的子类）走这里。
                        // 见 [SSE_IDLE_TIMEOUT_SECONDS]：正常长跑有 15s 心跳，绝不会触发。
                        throw ApiException(idleTimeoutMessage(e), null, e)
                    } ?: break // 对端关闭 = 流结束（走下面的 sawDone 判定）
                    if (!line.startsWith("data:")) continue    // 注释行 / 心跳 `: keepalive` / 帧间空行：跳过
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
                            onProgress(ev.completed, ev.total, ev.resumed)
                            // 计数行 + 后端随帧带来的「该条用例摘要」（判定 + 输入/回答/依据片段），
                            // 与本机跑时 AttackEngine 打的日志同构。构造逻辑收敛在
                            // SseLogFormat.progressLogLines（纯函数，有单测；**按行拆开**逐条入日志，
                            // 整段塞成一个日志项会让终端里一条用例占掉一大块，见 TestRunStateHolder.appendLog）。
                            progressLogLines(ev).forEach { onLog?.invoke(it) }
                        }
                        "done" -> {
                            sawDone = true
                            onLog?.invoke("✓ 后端已完成本轮测试，正在拉取报告…")
                            break
                        }
                        "error" -> throw ApiException(ev.message ?: "后端报告任务错误", null)
                        else -> { /* 未知事件忽略 */ }
                    }
                }
                if (!sawDone) throw ApiException(brokenStreamMessage(), null)
            }
        } catch (e: IOException) {
            // 连接被重置 / DNS 失败 / 中途 EOF 等。统一转成带「可续跑」提示的 ApiException，
            // 否则用户只会看到一句 Java 异常类名（`java.net.SocketException: ...`）。
            // ⚠️ ApiException 不是 IOException，所以上面抛的两种不会被这里二次包装。
            throw ApiException(brokenStreamMessage(e), null, e)
        } finally {
            // 只有"还是我这一根"时才清空：极端情况下（旧 call 刚结束、新 call 已装上）
            // 无脑清空会把新 call 的引用抹掉，于是「停止」再也取消不到任何东西。
            if (activeCall === call) activeCall = null
        }
    }

    /** 空闲超时的用户可读文案。带上底层异常名（排查时能区分读超时 / 连接重置 / 主动取消）。 */
    private fun idleTimeoutMessage(e: Throwable): String =
        "后端进度流已 $SSE_IDLE_TIMEOUT_SECONDS 秒收不到任何数据（含心跳），连接已断开" +
            "（${e.javaClass.simpleName}）。上一轮任务在后端通常仍在继续跑，已完成的用例不会白跑 —— " +
            "请稍后在测试配置页点「继续上次测试」续跑。"

    /** 流中途断掉（读超时之外的 IO 异常 / 没等到 done 就结束）的用户可读文案。 */
    private fun brokenStreamMessage(e: Throwable? = null): String =
        buildString {
            append("与后端的进度流中断了")
            e?.message?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            append("。若后端进程还在，那一轮任务通常仍在继续跑，已完成的结果都留在检查点里 —— ")
            append("可稍后在测试配置页点「继续上次测试」续跑，已完成的用例不重跑、不计费。")
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
