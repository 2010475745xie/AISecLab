package com.xieguojian.aiseclab.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import androidx.core.app.NotificationCompat
import com.xieguojian.aiseclab.core.RunMode
import com.xieguojian.aiseclab.core.TestSession
import com.xieguojian.aiseclab.core.engine.plannedCaseCount
import com.xieguojian.aiseclab.core.engine.variantCaseCount
import com.xieguojian.aiseclab.core.resolveRunMode
import com.xieguojian.aiseclab.core.api.BackendClient
import com.xieguojian.aiseclab.core.api.TokenMeter
import com.xieguojian.aiseclab.core.api.toTokenUsage
import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.RoleSource
import com.xieguojian.aiseclab.core.repo.HistoryRepository
import com.xieguojian.aiseclab.core.repo.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台常驻测试服务。
 *
 * 为什么需要它：原来测试协程挂在 `viewModelScope`，切后台 / 息屏后系统进入 Doze 或回收进程，
 * 协程被掐断 → 用户看到"跑着跑着就断了"。前台服务把进程抬到前台优先级，再叠三层保活：
 *  1) 部分唤醒锁（PARTIAL_WAKE_LOCK）保 CPU 不睡；
 *  2) WiFi 锁（WifiLock，WIFI_MODE_FULL_HIGH_PERF）保息屏后 WiFi 无线不休眠，否则 LAN 后端 SSE /
 *     外部 LLM 请求会被掐断，测试卡死；
 *  3) 开始测试前在 UI 层请求加入电池优化白名单（Doze 不再延迟网络、降低被杀概率）。
 * 三层合起来，后台 / 息屏都能跑完。注意：华为 / 荣耀等 ROM 还有自己的「手机管家」自启动管理，
 * 仍需用户手动把本 App 设为「允许后台活动 / 受保护应用」，代码无法替用户点。
 *
 * 类型选 `dataSync`：Android 14 要求前台服务声明类型，dataSync 是无特权类型里最贴合
 * "长时网络计算"的，且不要求麦克风/相机等敏感权限。需在 Manifest 声明
 * `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`。
 *
 * 进度经 [TestRunStateHolder] 回 VM；通知栏同步显示，并提供"停止"动作。
 */
class TestRunService : Service() {

    /**
     * 本轮协程的作用域。**每轮重新建一个**（见 onStartCommand 里的赋值）。
     *
     * 为什么不复用同一个：被 `cancel()` 过的 Job 是**死的**，对它再 `launch`，协程会被立刻取消、
     * 连函数体都进不去 —— 表现就是「停止后马上又点开始，界面毫无反应」。
     * Service 实例不一定会被立刻销毁（`stopSelf` 只是"没有新请求就停"），所以这个坑是真实可达的。
     */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 当前正在跑的那一轮用的后端客户端。「停止」要靠它 [BackendClient.cancel] **真正掐断连接**
     * —— 只 `scope.cancel()` 掐不断已经阻塞在 OkHttp 里的线程（见 [activeRun] 的注释）。
     */
    @Volatile
    private var backendClient: BackendClient? = null

    /**
     * 当前这一轮的**代次**。每次认领到运行名额就 +1，[stopRun] 也 +1。
     * 协程里所有回写（进度 / 日志 / 收尾）都先比对代次，**过期的一律丢弃**。
     *
     * 为什么非要有它（2026-10-16 日志实锤）：`stopRun()` 的 `scope.cancel()` **取消不了**
     * 已经阻塞在 `OkHttpClient.newCall(..).execute()` / `readUtf8Line()` 里的协程 ——
     * 那个协程会继续读流、继续回写。用户「停止」后又点「继续上次测试」，于是两条协程
     * 同时往同一个 [TestRunStateHolder] 写：
     *   · 旧会话的进度灌进新一轮的 UI 与通知栏（日志里 `进度 176` 之后突然冒出 `进度 2`）；
     *   · 旧协程的 `finally` 还会 `stopForeground + stopSelf()` —— 把新一轮的前台服务直接干掉。
     * 现场见 `aiseclab_run_1792165475419.log` 第 370 行：176 条复用之后跟的是旧会话
     * （23:43 那轮）的第 2 条《高权限注入》，报的却是 `进度 2/314`。
     */
    private var activeRun = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 停止动作：取消协程、掐断连接、收唤醒锁、撤通知
        if (intent?.action == ACTION_STOP) {
            stopRun()
            return START_NOT_STICKY
        }

        // ── 系统自动重投递：**绝不自动重跑** ──
        // 进程被回收时内存里的 holder / 代次一起没了，此时若当成本轮新测试开跑，会**新建一个后端
        // 会话从 0 开始**（老会话其实还在后端跑着）—— 两份结果互相覆盖、token 双倍烧。
        // 所以这里只如实告知"被中断了"，把"要不要续跑"交回用户（检查点里那些结果都还在）。
        if (flags and START_FLAG_REDELIVERY != 0) {
            startForeground(NOTIF_ID, buildNotification("已中断"))
            TestRunStateHolder.appendLog("⚠️ 上一轮测试被系统中断（进程被回收），本轮不会自动重跑")
            TestRunStateHolder.appendLog(
                "   后端模式下已完成的用例都留在检查点里：回「测试」页点「继续上次测试」即可续跑，不重跑、不计费。"
            )
            TestRunStateHolder.update(
                TestViewModel.TestState.Error(
                    "上一轮测试被系统中断（App 进程被回收），本轮不会自动重跑。" +
                        "若那轮是委托后端跑的，已完成的用例还在后端检查点里 —— " +
                        "回「测试」页点「继续上次测试」即可接着跑，不用从头再烧一遍 token。"
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 认领本轮运行名额。用**原子认领**而不是 `if (isRunning) return`：后者看的是 state，
        // 而 state 要等协程起来才置成 Running —— `beginRun` 之后、`update(Running(0,0))` 之前
        // 有一个空窗，快速双击「开始测试」正好能钻进去，于是两轮并发写同一个 holder。
        // claimRun 把「名额」与「界面状态」解耦，窗口就不存在了。
        if (!TestRunStateHolder.claimRun()) return START_NOT_STICKY
        val gen = ++activeRun
        // 本轮用全新的 scope（上一轮的 Job 已被 cancel，见 scope 的注释）
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val dims = (intent?.getIntegerArrayListExtra(EXTRA_DIMENSIONS)
            ?.mapNotNull { it.takeIf { i -> i in Dimension.values().indices }?.let { Dimension.values()[it] } }
            ?.toSet())
            ?: Dimension.values().toSet()
        val recursive = intent?.getBooleanExtra(EXTRA_RECURSIVE, false) ?: false
        val variants = intent?.getBooleanExtra(EXTRA_VARIANTS, false) ?: false
        // 断点续测：非空 = 接着这个 sessionId 的检查点跑（复用已完成的用例、只补跑剩下的）。
        // 只在后端模式有意义 —— 本机跑不落盘，没有断点（UI 侧已按 RunMode 挡住入口）。
        val resumeFrom = intent?.getStringExtra(EXTRA_RESUME_FROM)?.takeIf { it.isNotBlank() }

        val repo = ProfileRepository(this)
        val target = repo.loadTarget()
        if (target == null) {
            TestRunStateHolder.update(TestViewModel.TestState.Error("未配置目标模型"))
            TestRunStateHolder.releaseRun()
            stopSelf()
            return START_NOT_STICKY
        }
        val aux = repo.loadAux()
        val guard = repo.loadGuard()
        val roleSource = repo.loadRoleSource()
        val backendToken = repo.loadBackendToken()

        // ── 后端模式：整轮测试委托给服务器跑（开关关闭且地址非空时走这里）──
        // 裁判模型也由客户端带来（BYOK）：比赛/共享服务器通常不预置裁判模型。
        // 解析口径与端上 TestSession.resolveEndpoint 一致：AUX=用辅助，TARGET=用目标，AUTO=有辅助用辅助否则目标。
        val backendUrl = repo.loadBackendUrl()
        // 运行位置由唯一口径解析（core/RunMode.kt）：开关打开 = 本机（忽略地址）、
        // 开关关闭且地址非空 = 后端、两者都没配 = 不允许开测。
        // UI 侧（TestConfigScreen 的按钮可用性与提示）调的是同一个函数，别再各写一遍判据。
        val runMode = resolveRunMode(backendUrl, repo.loadLocalRunEnabled())
        if (runMode == null) {
            startForeground(NOTIF_ID, buildNotification("未能开始"))
            TestRunStateHolder.appendLog("✗ 未能开始：未配置测试后端，且「本机运行测试」已关闭")
            TestRunStateHolder.update(
                TestViewModel.TestState.Error(
                    "未配置测试后端，且「本机运行测试」已关闭。请在设置 → 测试后端填写地址，或在设置里开启本机运行测试。"
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            TestRunStateHolder.releaseRun()
            return START_NOT_STICKY
        }
        if (runMode == RunMode.BACKEND) {
            val judgeProfile = when (roleSource) {
                RoleSource.TARGET -> target
                RoleSource.AUX -> aux?.asModelProfile() ?: target
                RoleSource.AUTO -> if (aux != null) aux.asModelProfile() else target
            }
            startForeground(NOTIF_ID, buildNotification("准备中…（后端）"))
            acquireWakeLock()
            TestRunStateHolder.beginRun(
                applicationContext,
                buildString {
                    appendLine("模式：后端（委托服务器运行）")
                    appendLine("后端地址：$backendUrl")
                    appendLine("目标模型：${target.name.ifBlank { target.id }}")
                    appendLine("维度：${dims.joinToString("/") { it.label }}")
                    appendLine("递归对抗：$recursive")
                    // 条数**必须取口径**、不能写死：写死过 200，用例集涨到 264 之后
                    // 日志照旧念 200，用户对着 264 的实际进度看 200 —— 又一次"预告 ≠ 实际"。
                    appendLine(
                        "越狱变体集：$variants" +
                            "（${if (variants) "已并入 ${variantCaseCount()} 条" else "未并入"}）"
                    )
                    if (resumeFrom != null) {
                        appendLine("断点续测：$resumeFrom（已完成的用例直接复用、不重跑）")
                    }
                }
            )
            val client = BackendClient()
            backendClient = client
            scope.launch {
                // 后端委托分支不走 TestSession.run()，token 计数得在这里自己清零
                TokenMeter.reset()
                update(gen, TestViewModel.TestState.Running(0, 0))
                log(gen, "🖥 委托后端运行：$backendUrl")
                log(gen, "▶ 维度：${dims.joinToString("/") { it.label }} · 递归对抗=$recursive · 越狱变体集=$variants")
                try {
                    val rawReport = client.run(
                        baseUrl = backendUrl,
                        target = target,
                        aux = aux,
                        judge = judgeProfile,
                        dimensions = dims,
                        recursive = recursive,
                        variants = variants,
                        token = backendToken,
                        // 三参回调：第三个是累计复用条数（续跑时界面要能说清"复用多少 / 新跑多少"）
                        onProgress = { c, t, r -> onProgress(gen, c, t, r) },
                        onLog = { log(gen, it) },
                        resumeFrom = resumeFrom
                    )
                    // ── 条数对账（2026-10-15）──
                    // 端上预告的条数（唯一口径 = core/engine/CasePlan.kt）与后端实际跑出来的
                    // 条数不一致时，**必须说出原因**，别让用户对着两个数字猜。
                    // 真实发生过：后端跑的是 10-12 16:24 的冻结出包，而「越狱变体集」开关
                    // （useJailbreakVariants）是当天 23:05 才进的代码 —— 旧后端不认识这个字段，
                    // pydantic 默认静默丢弃 → 照旧只跑基线 50 条，变体集一条没跑。
                    val planned = plannedCaseCount(dims, variants)
                    if (rawReport.results.size != planned) {
                        log(
                            gen,
                            "⚠ 本轮预告 $planned 条，后端实际跑出 ${rawReport.results.size} 条 —— " +
                                "最常见的原因是**后端不是最新代码**：旧版不认识新加的请求字段" +
                                "（例如「越狱变体集」开关），会静默丢弃、照旧按老用例集跑。" +
                                "请用最新源码重启后端，或重新出包。"
                        )
                    }
                    // 本轮账单：跑完就落进报告，历史报告翻出来也能看到当时花了多少。
                    // 委托后端的这一路也拿得到 —— SSE 每帧都带累计用量，TokenMeter 已经接住了。
                    // 用时与测试页那个实时计时**同源**（起点落在 TestRunStateHolder.beginRun 里），
                    // 所以跑完那一刻屏幕上的数字与报告里的一致。
                    val report = rawReport.copy(
                        tokenUsage = TokenMeter.state.value.toTokenUsage(),
                        elapsedMs = TestRunStateHolder.elapsedMs()
                    )
                    // 存进历史记录（apiKey 抹零在 HistoryRepository 内做）；存档失败不影响本轮结果展示
                    runCatching { HistoryRepository(applicationContext).save(report) }
                    log(gen, "✓ 测试完成，报告已生成（共 ${report.results.size} 条用例）")
                    update(gen, TestViewModel.TestState.Done(report))
                    TestRunStateHolder.currentRunLogPath?.let { p -> log(gen, "📄 完整运行日志已保存到：$p") }
                    if (gen == activeRun) TestRunStateHolder.finalizeRun(report)
                } catch (e: Exception) {
                    log(gen, "✗ 错误：${e.message ?: "未知错误"}")
                    update(gen, TestViewModel.TestState.Error(e.message ?: "未知错误"))
                    TestRunStateHolder.currentRunLogPath?.let { p -> log(gen, "📄 运行日志已保存到：$p") }
                    if (gen == activeRun) TestRunStateHolder.finalizeRun(null)
                } finally {
                    // ⚠️ 收尾也必须过代次闸门：否则被淘汰的旧协程在这里 `stopForeground + stopSelf()`
                    // 会把**新一轮**的前台服务与通知一起干掉（新一轮就此静默死掉）。
                    if (gen == activeRun) {
                        backendClient = null
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // 交还运行名额：不交还的话 `isRunning` 会一直是 true，用户再也开不了下一轮
                        TestRunStateHolder.releaseRun()
                        stopSelf()
                    }
                }
            }
            return START_REDELIVER_INTENT
        }

        // ── 本地模式：端上直接跑（runMode == LOCAL）──
        // 必须在 onStartCommand 返回前 5s 内调用 startForeground，否则 Android 14 直接 ANR/崩溃
        startForeground(NOTIF_ID, buildNotification("准备中…"))
        acquireWakeLock()
        TestRunStateHolder.beginRun(
            applicationContext,
            buildString {
                appendLine("模式：本地（端上直接跑）")
                appendLine("目标模型：${target.name.ifBlank { target.id }}")
                appendLine("维度：${dims.joinToString("/") { it.label }}")
                appendLine("递归对抗：$recursive")
                appendLine("越狱变体集：$variants")
            }
        )

        val session = TestSession(application, target, aux, guard, roleSource, dims, recursive, variants)
        scope.launch {
            update(gen, TestViewModel.TestState.Running(0, 0))
            log(gen, "▶ 本地模式：端上直接跑（${dims.size} 个维度 · 递归对抗=$recursive · 越狱变体集=$variants）")
            log(gen, "准备中…（加载模型 / 规则引擎）")
            try {
                val rawReport = session.run(
                    // 本机跑不落盘、没有断点 ⇒ 复用数恒为 0（Running 的 resumed 缺省值）
                    onProgress = { completed, total -> onProgress(gen, completed, total, 0) },
                    onLog = { log(gen, it) }
                )
                // 条数对账（2026-10-17 补到本机分支）：以前只有后端分支对账，
                // 于是"配置页说 314、本机实际跑 50/32"这种不一致**在本机模式下完全无声**。
                // 判据取唯一口径 CasePlan，别在这里另算。
                val plannedLocal = plannedCaseCount(dims, variants)
                if (rawReport.results.size != plannedLocal) {
                    log(
                        gen,
                        "⚠ 本轮预告 $plannedLocal 条，本机实际跑出 ${rawReport.results.size} 条 —— " +
                            "两个数不一致说明「用例组装」与「条数口径」漂了，请把这条日志发出来（别自己蒙）。"
                    )
                }
                // 本轮账单：跑完就落进报告，历史报告翻出来也能看到当时花了多少
                //（跑的时候界面上那行实时计数只在当下看得到）。
                // 用时同上：与测试页的实时计时同源。
                val report = rawReport.copy(
                    tokenUsage = TokenMeter.state.value.toTokenUsage(),
                    elapsedMs = TestRunStateHolder.elapsedMs()
                )
                // 存进历史记录（apiKey 抹零在 HistoryRepository 内做）；存档失败不影响本轮结果展示
                runCatching { HistoryRepository(applicationContext).save(report) }
                log(gen, "✓ 测试完成，报告已生成（共 ${report.results.size} 条用例）")
                update(gen, TestViewModel.TestState.Done(report))
                TestRunStateHolder.currentRunLogPath?.let { p -> log(gen, "📄 完整运行日志已保存到：$p") }
                if (gen == activeRun) TestRunStateHolder.finalizeRun(report)
            } catch (e: Exception) {
                log(gen, "✗ 错误：${e.message ?: "未知错误"}")
                update(gen, TestViewModel.TestState.Error(e.message ?: "未知错误"))
                TestRunStateHolder.currentRunLogPath?.let { p -> log(gen, "📄 运行日志已保存到：$p") }
                if (gen == activeRun) TestRunStateHolder.finalizeRun(null)
            } finally {
                // 同后端分支：过期协程不许碰新一轮的前台服务（详见 [activeRun]）
                if (gen == activeRun) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    TestRunStateHolder.releaseRun()
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    /**
     * 代次校验通过才回写界面/日志 —— 过期（已被淘汰）的协程一律丢弃，详见 [activeRun]。
     * 这两个小包装是**唯一**允许协程回写 holder 的口子，别在协程里直接调 TestRunStateHolder。
     */
    private fun log(gen: Int, line: String) {
        if (gen == activeRun) TestRunStateHolder.appendLog(line)
    }

    private fun update(gen: Int, st: TestViewModel.TestState) {
        if (gen == activeRun) TestRunStateHolder.update(st)
    }

    /**
     * @param resumed 累计复用条数（后端 SSE 下发，见 BackendClient.SseEvent.resumed）。
     *   通知栏文案要带上它：续跑时前几百毫秒进度会"唰"地跳到一大截（复用条瞬间完成），
     *   只写 `180 / 314` 用户会以为跳号或者白烧了 token。
     */
    private fun onProgress(gen: Int, completed: Int, total: Int, resumed: Int) {
        if (gen != activeRun) return
        TestRunStateHolder.update(TestViewModel.TestState.Running(completed, total, resumed))
        notificationManager().notify(
            NOTIF_ID, buildNotification(progressText(completed, total, resumed))
        )
    }


    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        // 服务被销毁（含被系统回收）时交还名额，否则下次进来会以为"还有一轮在跑"开不了新的
        TestRunStateHolder.releaseRun()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun stopRun() {
        // ① 代次 +1：让正在跑的那个协程**及其后续所有回写**立刻作废（详见 [activeRun]）。
        //    必须放在最前面 —— 它是唯一能保证"旧协程即使还活着也不再污染界面"的那道闸。
        activeRun++
        // ② 真正掐断连接。`scope.cancel()` 掐不断已经阻塞在 OkHttp `execute()/readUtf8Line()`
        //    里的线程，必须显式 cancel 那根 Call，否则旧协程会继续读流、继续写 UI 和日志
        //    （2026-10-16 的 176→2 就是这么来的）。
        //    只断本端这根连接：后端那一轮照旧跑完并写检查点，用户回来还能「继续上次测试」。
        backendClient?.cancel()
        backendClient = null
        // ③ 取消本轮协程（掐不动的那些由①②兜住）
        scope.cancel()
        releaseWakeLock()
        TestRunStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, "AISecLab::TestRun").apply {
            setReferenceCounted(false)
            // 兜底上限 6h：实测跑完会在 finally 里释放；这里防止极端情况下一直持有
            acquire(6L * 60 * 60 * 1000)
        }
        // WiFi 锁：PARTIAL_WAKE_LOCK 只保 CPU 不睡，息屏后系统仍会让 WiFi 无线休眠/断流，
        // 导致 LAN 后端 SSE 流或外部 LLM 请求卡死 → 测试看起来"跑着跑着断了"。
        // 持有 WifiLock 强制 WiFi 保持唤醒，配合前台服务 + 唤醒锁，息屏也能跑完。
        // 注：WifiLock 整套 API 自 API 34 起废弃（平台期望前台服务自动保 WiFi），但旧系统 /
        // 华为等 ROM 仍需它；API 34+ 上主要靠「电池优化白名单」保证网络不被 Doze 延迟。
        @Suppress("DEPRECATION")
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "AISecLab::TestRun").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "AI 安全测试", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            notificationManager().createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TestRunService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AISecLab · AI 安全测试")
            .setContentText(text)
            // 系统图标做占位，上架前换成 App 自己的通知图标（res/drawable/ic_notification）
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "aiseclab_test_run"
        const val EXTRA_DIMENSIONS = "extra_dimensions"
        const val EXTRA_RECURSIVE = "extra_recursive"
        const val EXTRA_VARIANTS = "extra_variants"
        /** 断点续测：非空 = 接着这个后端会话的检查点跑（复用已完成的用例）。 */
        const val EXTRA_RESUME_FROM = "extra_resume_from"
        const val ACTION_STOP = "com.xieguojian.aiseclab.action.STOP"
    }
}

/** 辅助模型（无 id/name）转成请求体需要的 [ModelProfile]（作为裁判/攻击者端点）。 */
private fun AuxModelProfile.asModelProfile(): ModelProfile =
    ModelProfile(id = "aux", name = "", baseUrl = baseUrl, apiKey = apiKey, model = model)
