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
        val useStaticCases: Boolean = true
    )

    @Serializable
    private data class SessionCreated(val sessionId: String)

    /**
     * SSE 事件。后端字段全小写驼峰，与 data class 字段名一一对应；
     * 多数字段有默认值，未知字段被 json(ignoreUnknownKeys) 容忍。
     */
    @Serializable
    private data class SseEvent(
        val event: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val dimension: String? = null,
        val reportId: String? = null,
        val message: String? = null
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
        token: String?,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ): Report = withContext(Dispatchers.IO) {
        val sid = createSession(baseUrl, target, aux, judge, dimensions, recursive, token)
        onLog?.invoke("▶ 已创建后端会话（sessionId=$sid）")
        subscribeStream(baseUrl, sid, token, onProgress, onLog)
        getReport(baseUrl, sid, token)
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
        token: String?
    ): String {
        val body = json.encodeToString(
            SessionRequest.serializer(),
            SessionRequest(
                target = target,
                aux = aux,
                judge = judge,
                dimensions = dimensions.toList(),
                recursive = recursive
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
                        onProgress(ev.completed, ev.total)
                        // 后端 SSE 已带维度与可选 message：转成终端日志行
                        val dim = ev.dimension?.let { "[$it] " } ?: ""
                        onLog?.invoke("● ${dim}进度 ${ev.completed}/${ev.total}")
                        ev.message?.takeIf { it.isNotBlank() }?.let { onLog?.invoke("  $it") }
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
        val b = base.trim().trimEnd('/')
        return "$b$path"
    }
}
