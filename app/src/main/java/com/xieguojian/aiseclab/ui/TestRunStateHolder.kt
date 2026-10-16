package com.xieguojian.aiseclab.ui

import android.content.Context
import android.os.SystemClock
import com.xieguojian.aiseclab.core.model.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务 ↔ ViewModel 之间的测试状态桥。
 *
 * 测试协程现在跑在 [TestRunService] 的进程作用域里（不在 viewModelScope），
 * 所以进度/结果不能直接写 VM 的私有 StateFlow。用一个进程级单例 StateFlow 中转：
 * 服务写、VM 在 init 里 collect 后镜像到 `_testState`，UI 照常订阅 `vm.testState`。
 *
 * 这样切后台 / 息屏时服务继续推进，回到前台 VM 重新 attach 也能立刻拿到最新状态。
 */
object TestRunStateHolder {
    private val _state = MutableStateFlow<TestViewModel.TestState>(TestViewModel.TestState.Idle)
    val state: StateFlow<TestViewModel.TestState> = _state.asStateFlow()

    /**
     * 测试进程日志（终端窗口内容）。与 [state] 分开存：
     * - 进度每次 tick 都会更新 [state]，但日志只在关键节点 append，不会每 tick 把整份日志拷进 [TestViewModel.TestState.Running]；
     * - UI（RunScreen）同时订阅 [state]（进度条）和 [log]（终端），互不影响。
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** 单轮日志上限，超过从头部丢弃最旧行（防止极端长跑把内存撑爆）。 */
    private const val MAX_LOG_LINES = 2000

    /**
     * 本轮的**起点**：单调时钟毫秒（`SystemClock.elapsedRealtime()`）。null = 当前没在跑。
     *
     * 为什么起点记在这里、而不是测试页和报告各记一个：两边必须是同一个数。
     * 测试页每秒刷新的实时耗时和报告里那个最终数字若各自起算，结果必然差一截
     * （准备阶段算不算、谁先谁后），用户看到"跑的时候 42 秒、报告里 39 秒"只会怀疑程序。
     *
     * 用 `elapsedRealtime` 而不是 `currentTimeMillis`：后者是壁钟，会被系统对时 / 用户改时间
     * 跳变 —— 算出一个负的或者几小时的"用时"比不显示更糟。
     *
     * `@Volatile`：起点在服务主线程（`onStartCommand` → [beginRun]）落，用时在 IO 线程
     * （测试协程里）读。
     */
    @Volatile
    private var startedAtMs: Long? = null

    /** 本轮起点（单调时钟毫秒）。测试页据此算实时耗时；还没开始 → null。 */
    val startedAtElapsedMs: Long?
        get() = startedAtMs

    /**
     * 本轮**已用时**（毫秒）。还没开始 → **null，不是 0**
     * （「没开始」和「一毫秒就跑完了」是两回事 —— 与本项目「没采集 ≠ 很差」同源）。
     */
    fun elapsedMs(): Long? = startedAtMs?.let { SystemClock.elapsedRealtime() - it }

    // ── 文件日志：把每轮测试的终端日志 + 报告 JSON 落盘到应用外部文件夹（无需任何权限）──
    // 路径：/storage/emulated/0/Android/data/com.xieguojian.aiseclab/files/logs/aiseclab_run_<ts>.log
    // 用户可在「文件管理」直接访问，便于事后审计 / 排查。所有 IO 全部 runCatching 吞异常，
    // 落盘失败绝不影响主测试流程。
    private var runLogWriter: BufferedWriter? = null
    private var runLogFile: File? = null
    private val logTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val reportJson = Json { ignoreUnknownKeys = true }

    /** 当前轮日志文件路径（仅用于终端提示，文件关闭后仍为最后一轮路径）。 */
    val currentRunLogPath: String?
        get() = runLogFile?.absolutePath

    /**
     * 新一轮测试开始时调用：在应用外部文件夹建 `logs/` 目录并新建一个 `.log` 文件，写入头部。
     * [header] 由调用方填上模式 / 模型 / 维度等关键元信息。
     */
    fun beginRun(context: Context, header: String) {
        // 起点**先落**，再去做建文件这种会失败的活：计时不该因为日志文件建不出来就整个丢掉。
        startedAtMs = SystemClock.elapsedRealtime()
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { if (!exists()) mkdirs() }
            val ts = System.currentTimeMillis()
            val file = File(dir, "aiseclab_run_$ts.log")
            runLogFile = file
            runLogWriter = BufferedWriter(FileWriter(file, true)).also { w ->
                w.write("==== AISecLab 测试运行日志 ====\n")
                w.write("时间：${logTsFmt.format(Date(ts))}\n")
                if (header.isNotBlank()) w.write(header + "\n")
                w.write("文件：${file.absolutePath}\n")
                w.write("===========================\n")
                w.flush()
            }
        }.onFailure { runLogFile = null }
    }

    fun appendLog(line: String) {
        val next = _log.value.toMutableList().apply {
            add(line)
            while (size > MAX_LOG_LINES) removeAt(0)
        }
        _log.value = next
        // 同步落盘：带时间戳，便于事后按时间线复盘
        runCatching {
            runLogWriter?.let { w ->
                w.write("[${logTsFmt.format(Date())}] $line\n")
                w.flush()
            }
        }
    }

    fun update(s: TestViewModel.TestState) {
        // 新一轮测试以 Running(0,0) 起始：清空上一轮残留日志，避免和新日志串在一起。
        if (s is TestViewModel.TestState.Running && s.completed == 0 && s.total == 0) {
            _log.value = emptyList()
        }
        _state.value = s
    }

    /**
     * 测试结束（完成 / 出错）时调用：把报告 JSON 追加进日志，再关闭文件。
     * [report] 为 null 表示出错没有报告，只收尾不写 JSON。apiKey 同历史存档一样就地抹零。
     */
    fun finalizeRun(report: Report?) {
        runCatching {
            runLogWriter?.let { w ->
                w.write("\n==== 测试报告（JSON） ====\n")
                if (report != null) {
                    val sanitized = report.copy(modelProfile = report.modelProfile.copy(apiKey = ""))
                    w.write(reportJson.encodeToString(Report.serializer(), sanitized) + "\n")
                } else {
                    w.write("(无报告)\n")
                }
                w.flush()
            }
        }
        closeRunLog()
    }

    fun reset() {
        // 用户中途点「停止」：落盘一条中断标记再收尾
        runCatching {
            runLogWriter?.let { w ->
                w.write("\n[${logTsFmt.format(Date())}] 运行已被用户中断\n")
                w.flush()
            }
        }
        closeRunLog()
        // 起点也清掉：中断后没有"本轮已用时"这个概念了，留着会让下一次渲染读到一个陈旧值。
        // （正常跑完**不清** —— 测试页在 Done 态还要用兜底显示，直到下一轮 beginRun 覆盖。）
        startedAtMs = null
        _state.value = TestViewModel.TestState.Idle
        _log.value = emptyList()
    }

    private fun closeRunLog() {
        runCatching { runLogWriter?.close() }
        runLogWriter = null
    }

    val isRunning: Boolean
        get() = _state.value is TestViewModel.TestState.Running
}
