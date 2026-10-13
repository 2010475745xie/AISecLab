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
import com.xieguojian.aiseclab.core.resolveRunMode
import com.xieguojian.aiseclab.core.api.BackendClient
import com.xieguojian.aiseclab.core.api.TokenMeter
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 停止动作：取消协程、收唤醒锁、撤通知
        if (intent?.action == ACTION_STOP) {
            stopRun()
            return START_NOT_STICKY
        }
        // 已经在跑就忽略重复启动（避免两个协程写同一个 holder）
        if (TestRunStateHolder.isRunning) return START_NOT_STICKY

        val dims = (intent?.getIntegerArrayListExtra(EXTRA_DIMENSIONS)
            ?.mapNotNull { it.takeIf { i -> i in Dimension.values().indices }?.let { Dimension.values()[it] } }
            ?.toSet())
            ?: Dimension.values().toSet()
        val recursive = intent?.getBooleanExtra(EXTRA_RECURSIVE, false) ?: false
        val variants = intent?.getBooleanExtra(EXTRA_VARIANTS, false) ?: false

        val repo = ProfileRepository(this)
        val target = repo.loadTarget()
        if (target == null) {
            TestRunStateHolder.update(TestViewModel.TestState.Error("未配置目标模型"))
            stopSelf()
            return START_NOT_STICKY
        }
        val aux = repo.loadAux()
        val guard = repo.loadGuard()
        val roleSource = repo.loadRoleSource()

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
            return START_REDELIVER_INTENT
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
                    appendLine("越狱变体集：$variants（${if (variants) "已并入 100 条" else "未并入"}）")
                }
            )
            val client = BackendClient()
            scope.launch {
                // 后端委托分支不走 TestSession.run()，token 计数得在这里自己清零
                TokenMeter.reset()
                TestRunStateHolder.update(TestViewModel.TestState.Running(0, 0))
                TestRunStateHolder.appendLog("🖥 委托后端运行：$backendUrl")
                TestRunStateHolder.appendLog("▶ 维度：${dims.joinToString("/") { it.label }} · 递归对抗=$recursive · 越狱变体集=$variants")
                try {
                    val report = client.run(
                        baseUrl = backendUrl,
                        target = target,
                        aux = aux,
                        judge = judgeProfile,
                        dimensions = dims,
                        recursive = recursive,
                        variants = variants,
                        token = repo.loadBackendToken(),
                        onProgress = { c, t -> onProgress(c, t) },
                        onLog = { TestRunStateHolder.appendLog(it) }
                    )
                    // 存进历史记录（apiKey 抹零在 HistoryRepository 内做）；存档失败不影响本轮结果展示
                    runCatching { HistoryRepository(applicationContext).save(report) }
                    TestRunStateHolder.appendLog("✓ 测试完成，报告已生成（共 ${report.results.size} 条用例）")
                    TestRunStateHolder.update(TestViewModel.TestState.Done(report))
                    TestRunStateHolder.currentRunLogPath?.let { p ->
                        TestRunStateHolder.appendLog("📄 完整运行日志已保存到：$p")
                    }
                    TestRunStateHolder.finalizeRun(report)
                } catch (e: Exception) {
                    TestRunStateHolder.appendLog("✗ 错误：${e.message ?: "未知错误"}")
                    TestRunStateHolder.update(TestViewModel.TestState.Error(e.message ?: "未知错误"))
                    TestRunStateHolder.currentRunLogPath?.let { p ->
                        TestRunStateHolder.appendLog("📄 运行日志已保存到：$p")
                    }
                    TestRunStateHolder.finalizeRun(null)
                } finally {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
            TestRunStateHolder.update(TestViewModel.TestState.Running(0, 0))
            TestRunStateHolder.appendLog("▶ 本地模式：端上直接跑（${dims.size} 个维度 · 递归对抗=$recursive · 越狱变体集=$variants）")
            TestRunStateHolder.appendLog("准备中…（加载模型 / 规则引擎）")
            try {
                val report = session.run(
                    onProgress = { completed, total -> onProgress(completed, total) },
                    onLog = { TestRunStateHolder.appendLog(it) }
                )
                // 存进历史记录（apiKey 抹零在 HistoryRepository 内做）；存档失败不影响本轮结果展示
                runCatching { HistoryRepository(applicationContext).save(report) }
                TestRunStateHolder.appendLog("✓ 测试完成，报告已生成（共 ${report.results.size} 条用例）")
                TestRunStateHolder.update(TestViewModel.TestState.Done(report))
                TestRunStateHolder.currentRunLogPath?.let { p ->
                    TestRunStateHolder.appendLog("📄 完整运行日志已保存到：$p")
                }
                TestRunStateHolder.finalizeRun(report)
            } catch (e: Exception) {
                TestRunStateHolder.appendLog("✗ 错误：${e.message ?: "未知错误"}")
                TestRunStateHolder.update(TestViewModel.TestState.Error(e.message ?: "未知错误"))
                TestRunStateHolder.currentRunLogPath?.let { p ->
                    TestRunStateHolder.appendLog("📄 运行日志已保存到：$p")
                }
                TestRunStateHolder.finalizeRun(null)
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun onProgress(completed: Int, total: Int) {
        TestRunStateHolder.update(TestViewModel.TestState.Running(completed, total))
        notificationManager().notify(NOTIF_ID, buildNotification("进行中 $completed / $total"))
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun stopRun() {
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
        const val ACTION_STOP = "com.xieguojian.aiseclab.action.STOP"
    }
}

/** 辅助模型（无 id/name）转成请求体需要的 [ModelProfile]（作为裁判/攻击者端点）。 */
private fun AuxModelProfile.asModelProfile(): ModelProfile =
    ModelProfile(id = "aux", name = "", baseUrl = baseUrl, apiKey = apiKey, model = model)
