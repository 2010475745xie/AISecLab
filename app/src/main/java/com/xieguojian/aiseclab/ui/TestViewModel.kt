package com.xieguojian.aiseclab.ui

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xieguojian.aiseclab.core.api.BackendClient
import com.xieguojian.aiseclab.core.judge.guard.GuardModelDownloader
import com.xieguojian.aiseclab.core.judge.guard.OnDeviceGuardModel
import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.GuardProfile
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.RoleSource
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.core.repo.HistoryRepository
import com.xieguojian.aiseclab.core.repo.ProfileRepository
import com.xieguojian.aiseclab.core.report.ForensicsJson
import com.xieguojian.aiseclab.core.report.ReportDiff
import com.xieguojian.aiseclab.core.report.ReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TestViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(app)

    private val _target = MutableStateFlow(repo.loadTarget())
    val target: StateFlow<ModelProfile?> = _target.asStateFlow()

    private val _aux = MutableStateFlow(repo.loadAux())
    val aux: StateFlow<AuxModelProfile?> = _aux.asStateFlow()

    private val _guard = MutableStateFlow(repo.loadGuard())
    val guard: StateFlow<GuardProfile?> = _guard.asStateFlow()

    private val _selectedDimensions = MutableStateFlow<Set<Dimension>>(Dimension.values().toSet())
    val selectedDimensions: StateFlow<Set<Dimension>> = _selectedDimensions.asStateFlow()

    private val _useRecursive = MutableStateFlow(false)
    val useRecursive: StateFlow<Boolean> = _useRecursive.asStateFlow()

    /**
     * 是否并入「越狱变体集」（264 条：24 个有害目标 × 11 类策略）。
     *
     * 默认关。打开后安全维度从 18 条涨到 282 条 —— 与递归对抗叠加时是最贵的一档
     * （282 条安全用例 × 每例最多 10 轮），UI 上要把这个代价说清楚。
     * 与 [useRecursive] 一样只在内存里，不持久化（每轮显式选择，避免上次开过、这次忘了）。
     */
    private val _useVariants = MutableStateFlow(false)
    val useVariants: StateFlow<Boolean> = _useVariants.asStateFlow()

    private val _roleSource = MutableStateFlow(repo.loadRoleSource())
    val roleSource: StateFlow<RoleSource> = _roleSource.asStateFlow()

    fun setRoleSource(src: RoleSource) {
        repo.saveRoleSource(src)
        _roleSource.value = src
    }

    // 测试后端地址（可选）：空 = 端上本地跑；非空 = 委托后端（见 TestRunService 分支）。
    private val _backendUrl = MutableStateFlow(repo.loadBackendUrl())
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _backendToken = MutableStateFlow(repo.loadBackendToken())
    val backendToken: StateFlow<String> = _backendToken.asStateFlow()

    fun saveBackendUrl(url: String) {
        val u = url.trim()
        repo.saveBackendUrl(u)
        _backendUrl.value = u
    }

    fun saveBackendToken(token: String) {
        val t = token.trim()
        repo.saveBackendToken(t)
        _backendToken.value = t
    }

    // 「本机运行测试」总开关（默认关闭）：关闭时未配后端就不允许开测，
    // 因为端上跑缺少后端的多次采样/稳定性保护，单次采样定论方差无人兜底。
    private val _localRunEnabled = MutableStateFlow(repo.loadLocalRunEnabled())
    val localRunEnabled: StateFlow<Boolean> = _localRunEnabled.asStateFlow()

    fun setLocalRunEnabled(enabled: Boolean) {
        repo.saveLocalRunEnabled(enabled)
        _localRunEnabled.value = enabled
    }

    sealed class TestState {
        object Idle : TestState()

        /**
         * 进行中。[resumed] = 这 [completed] 条里有多少是**从断点复用**的（未重跑、不计费）。
         *
         * 为什么要分开记：续跑时进度会"唰"地跳到一大截（复用条瞬间完成），只显示总数
         * 用户会以为程序跳号、或者以为 token 白烧了一遍（2026-10-16 用户正是在这里困惑）。
         * 新跑条数 = `completed - resumed`（`resumed` 是累计值，与后端 `progress` 事件同源）。
         * 缺省 0 = 全新一轮（本机跑也永远是 0，端上不落盘、没有断点）。
         */
        data class Running(val completed: Int, val total: Int, val resumed: Int = 0) : TestState()

        data class Done(val report: Report) : TestState()
        data class Error(val message: String) : TestState()
    }

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    /** 测试进程日志（RunScreen 终端窗口内容），镜像自 [TestRunStateHolder.log]。 */
    private val _testLog = MutableStateFlow<List<String>>(emptyList())
    val testLog: StateFlow<List<String>> = _testLog.asStateFlow()

    /**
     * 首次启动引导页标记（读自 [ProfileRepository]，持久化）。
     * `MainActivity` 用它决定首屏是引导页还是首页。
     */
    private val _onboarded = MutableStateFlow(repo.loadOnboarded())
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** 走完引导页调用：落盘 + 翻内存标志。 */
    fun finishOnboarding() {
        repo.saveOnboarded()
        _onboarded.value = true
    }

    // ------------------------------------------------------------------
    // 历史报告（服务跑完时由 TestRunService 落盘，这里只读/删）
    // ------------------------------------------------------------------

    private val historyRepo by lazy { HistoryRepository(getApplication()) }

    private val _history = MutableStateFlow<List<Report>>(emptyList())
    val history: StateFlow<List<Report>> = _history.asStateFlow()

    /**
     * 历史列表是否**已经读完过至少一次**。false = 还在读盘。
     *
     * 为什么必须有这一位：[_history] 的初值是空表，而读盘在 [Dispatchers.IO] 上 ——
     * 只看 `history.isEmpty()` 的话，「还没读完」和「确实没有」是同一种界面，
     * 冷启动时会先对用户说一句「还没有历史报告」再改口。2026-10-14 装机冷启动实测：
     * 空态被渲染了约 0.5~1 秒（13 份报告、每份 ~85 KB JSON）才切成列表。
     * 与判定链那条铁律同源：**「没采到」不能当成「没有」**。
     */
    private val _historyLoaded = MutableStateFlow(false)
    val historyLoaded: StateFlow<Boolean> = _historyLoaded.asStateFlow()

    /** 基线对比：选定的历史报告；null = 不做对比。 */
    private val _baseline = MutableStateFlow<Report?>(null)
    val baseline: StateFlow<Report?> = _baseline.asStateFlow()

    /**
     * 重读历史列表。冷启动（init）、进历史页、或收到 Done（新报告落盘后）时调用。
     *
     * 冷启动必须调：[_history] 初值是空表，若只在 Done 时刷新，
     * 就会出现「退出重进后历史列表空着，非得跑完一轮才出现」。
     */
    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _history.value = historyRepo.list()
            } finally {
                // 即使读取抛错也要落下这一位：否则界面永远卡在「正在读取历史报告…」，
                // 用户连「确实没有」都无法确认，比看到一份空列表更糟。
                _historyLoaded.value = true
            }
        }
    }

    /**
     * 删掉历史时如果删的正是基线，清空基线 —— 否则下次进报告页会拿到一份
     * 已经被删掉的报告做 diff，显示一个用户以为还在的「基线」。
     */
    fun deleteHistory(timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.delete(timestamp)
            _history.value = historyRepo.list()
            if (_baseline.value?.timestamp == timestamp) _baseline.value = null
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.clear()
            _history.value = emptyList()
            // 基线可能正指向一份被删掉的报告，清掉避免下次 diff 拿到陈旧数据
            _baseline.value = null
        }
    }

    // ------------------------------------------------------------------
    // 基线对比：选定一份历史报告作基线，报告页据此展示 delta
    // ------------------------------------------------------------------

    /** 选/取消基线。点同一个 = 取消。 */
    fun toggleBaseline(report: Report) {
        val cur = _baseline.value
        _baseline.value = if (cur?.timestamp == report.timestamp) null else report
    }

    fun saveTarget(name: String, baseUrl: String, apiKey: String, model: String) {
        val p = ModelProfile(id = "target", name = name, baseUrl = baseUrl, apiKey = apiKey, model = model)
        repo.saveTarget(p)
        _target.value = p
    }

    fun saveAux(baseUrl: String?, apiKey: String?, model: String?) {
        val aux = if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || model.isNullOrBlank()) null
        else AuxModelProfile(baseUrl.trim(), apiKey.trim(), model.trim())
        repo.saveAux(aux)
        _aux.value = aux
    }

    /**
     * 保存护栏配置。传 null = 关闭护栏（退化为纯规则判定）。
     *
     * 与 [saveAux] 的差别：**apiKey 可以为空** —— 本地 llama-server 默认不校验；
     * 而且 `OpenAiCompatibleClient` 只在 apiKey 非空时才加 Authorization，
     * 不会发出 `Bearer ` 这种空令牌把本地服务打成 401。
     */
    fun saveGuard(profile: GuardProfile?) {
        val g = profile?.let {
            it.copy(
                baseUrl = it.baseUrl.trim().trimEnd('/'),
                apiKey = it.apiKey.trim(),
                model = it.model.trim()
            )
        }
        repo.saveGuard(g)
        _guard.value = g
    }

    // ------------------------------------------------------------------
    // 端上模型下载
    // ------------------------------------------------------------------

    private val _guardDownload =
        MutableStateFlow<GuardModelDownloader.State>(GuardModelDownloader.State.Idle)

    /** 端上模型下载进度。UI 直接订阅；换档位会重置。 */
    val guardDownload: StateFlow<GuardModelDownloader.State> = _guardDownload.asStateFlow()

    /**
     * 已就绪（存在 + 大小与官方一致）的档位 label 集合。
     *
     * 用集合而不是"当前档位是否就绪"：用户可能在几个档位间来回试，
     * 每次都去 stat 一次文件没必要，而且 `isReady` 要读 filesDir（IO）。
     */
    private val _readyTiers = MutableStateFlow<Set<String>>(emptySet())
    val readyTiers: StateFlow<Set<String>> = _readyTiers.asStateFlow()

    // ------------------------------------------------------------------
    // 断点续测：后端上还没跑完的会话
    //
    // ⚠️ 这两个属性**必须声明在 `init` 之前**。Kotlin 属性按源码顺序初始化，
    //    而 `init` 里就调了 refreshResumable()；它内部是
    //    `viewModelScope.launch(Dispatchers.IO) { _resumable.value = ... }`，
    //    IO 线程完全可能抢在主线程完成属性初始化之前跑到 lambda 里
    //    ⇒ 打在 null 上抛 NPE，前台 Service 挂掉、App 冷启动即崩。
    //    （实测：连崩 4 次，堆栈全在 TestViewModel.kt:335。曾经加在文件底部，踩过。）
    // ------------------------------------------------------------------
    private val _resumable = MutableStateFlow<List<BackendClient.IncompleteSession>>(emptyList())
    val resumable: StateFlow<List<BackendClient.IncompleteSession>> = _resumable.asStateFlow()

    init {
        refreshReadyTiers()
        // 冷启动主动读一次磁盘历史，别让列表空着等第一次 Done
        refreshHistory()
        // 断点续测：冷启动也拉一次"后端上还没跑完的会话"，否则上次被中断的那轮
        // 要等用户自己去别处晃一圈才出现（入口在测试配置页）。
        refreshResumable()
        // 测试协程现在跑在前台服务进程里，进度经 TestRunStateHolder 中转，这里镜像到 UI 订阅的 _testState
        viewModelScope.launch {
            TestRunStateHolder.state.collect {
                _testState.value = it
                // 服务侧把新报告写进历史了，收到 Done 时顺手刷新列表
                if (it is TestState.Done) refreshHistory()
                // 断点续测：一轮结束（无论成败）都会改变"哪些会话还没跑完"，重拉一次
                if (it is TestState.Done || it is TestState.Error) refreshResumable()
            }
        }
        viewModelScope.launch {
            TestRunStateHolder.log.collect { _testLog.value = it }
        }
    }

    /** 重扫模型目录。下载完成、删除后调用。 */
    fun refreshReadyTiers() {
        val app = getApplication<Application>()
        _readyTiers.value = OnDeviceGuardModel.TIERS
            .filter { OnDeviceGuardModel.isReady(app, it) }
            .map { it.label }
            .toSet()
    }

    /**
     * 下载端上护栏模型。可重复调用（续传）。已在下就会直接返回，避免并发写同一个文件。
     */
    fun downloadGuardModel(tier: OnDeviceGuardModel.Tier) {
        // 已经在下载中就不再起第二条流：两个流写同一个 RandomAccessFile 会写出损坏文件
        if (_guardDownload.value is GuardModelDownloader.State.Progress) return
        if (_guardDownload.value is GuardModelDownloader.State.Verifying) return

        viewModelScope.launch {
            GuardModelDownloader(getApplication()).download(tier).collect { st ->
                _guardDownload.value = st
                if (st is GuardModelDownloader.State.Done) refreshReadyTiers()
            }
        }
    }

    /** 删除某个档位的模型文件，腾出空间。 */
    fun deleteGuardModel(tier: OnDeviceGuardModel.Tier) {
        if (_guardDownload.value is GuardModelDownloader.State.Progress) return
        OnDeviceGuardModel.file(getApplication(), tier).delete()
        _guardDownload.value = GuardModelDownloader.State.Idle
        refreshReadyTiers()
    }

    fun toggleDimension(dim: Dimension, enabled: Boolean) {
        _selectedDimensions.value =
            if (enabled) _selectedDimensions.value + dim else _selectedDimensions.value - dim
    }

    fun setUseRecursive(enabled: Boolean) {
        _useRecursive.value = enabled
    }

    fun setUseVariants(enabled: Boolean) {
        _useVariants.value = enabled
    }

    /**
     * 拉后端上「还没跑完」的会话（断点续测入口）。
     *
     * 后端没配 / 连不上 → 空表，**不报错**：这只是配置页的一张提示卡，
     * 用户此刻可能正要去改后端地址，没必要弹一个错误盖住他的操作。
     */
    fun refreshResumable() {
        val url = _backendUrl.value
        if (url.isBlank()) {
            _resumable.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _resumable.value = BackendClient().listIncomplete(url, _backendToken.value)
        }
    }

    /** 丢掉某个会话的断点（用户放弃续跑），随后重拉列表。 */
    fun dropResumable(sid: String) {
        val url = _backendUrl.value
        if (url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val client = BackendClient()
            client.dropCheckpoint(url, sid, _backendToken.value)
            _resumable.value = client.listIncomplete(url, _backendToken.value)
        }
    }

    /**
     * 启动一轮测试。
     *
     * 不再在 viewModelScope 里直接跑协程——那样切后台 / 息屏会被系统回收，用户看到的就是
     * "跑着跑着断了"。改为起一个前台服务（[TestRunService]），服务进程 + 部分唤醒锁保活，
     * 进度/结果经 [TestRunStateHolder] 回写，本 VM 在 init 里 collect 后镜像给 UI。
     *
     * 配置传递：target/aux/guard/roleSource 都在 EncryptedSharedPreferences 里（持久化），
     * 服务自己读；唯独 dimensions 和 recursive 只在 VM 内存，从 Intent 带过去。
     *
     * [resumeFrom] 非空 = **断点续测**：接着那个后端会话的检查点跑（已完成的用例直接复用、
     * 不重跑）。只在后端模式有意义——本机跑不落盘，没有断点。
     */
    fun startTest(resumeFrom: String? = null) {
        if (_target.value == null) return
        if (TestRunStateHolder.isRunning) return
        val intent = Intent(getApplication(), TestRunService::class.java).apply {
            putIntegerArrayListExtra(
                TestRunService.EXTRA_DIMENSIONS,
                ArrayList(_selectedDimensions.value.map { it.ordinal })
            )
            putExtra(TestRunService.EXTRA_RECURSIVE, _useRecursive.value)
            putExtra(TestRunService.EXTRA_VARIANTS, _useVariants.value)
            putExtra(TestRunService.EXTRA_RESUME_FROM, resumeFrom)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    /**
     * 导出 HTML。传 [report] 则导出指定报告（历史记录回看），缺省导出本轮当前报告。
     * 已选基线时会把对比章节一起写进导出。
     */
    fun exportHtml(report: Report? = null): File? {
        val r = report ?: (_testState.value as? TestState.Done)?.report ?: return null
        val d = diffOf(r)
        val html = ReportGenerator.toHtml(r, d)
        // 写到应用外部 files/Download —— 用户能在「文件管理」里直接找到，无需 root。
        // getExternalFilesDir 不需要任何存储权限（App 专属外部目录），downloadDir 为 null 时回退 cacheDir。
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getApplication<Application>().cacheDir
        val file = File(dir, "aiseclab_report_${r.timestamp}.html")
        file.writeText(html)
        return file
    }

    /** 导出 Markdown。传 [report] 则导出指定报告（历史记录回看），缺省导出本轮当前报告。 */
    fun exportMarkdown(report: Report? = null): File? {
        val r = report ?: (_testState.value as? TestState.Done)?.report ?: return null
        val d = diffOf(r)
        val md = ReportGenerator.toMarkdown(r, d)
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getApplication<Application>().cacheDir
        val file = File(dir, "aiseclab_report_${r.timestamp}.md")
        file.writeText(md)
        return file
    }

    /**
     * 导出取证 JSON（方案 §3.6）。传 [report] 则导出指定报告，缺省导出本轮当前报告。
     *
     * 与 HTML / Markdown 两个出口的分工：那两个是**给人读**的排版件，这一份是**给程序读**的
     * —— 报告落盘归档、服务端汇总、将来多模型横向对比（4.4）都靠它。
     * 所以刻意**不带**基线对比（那是展示层的两两比较，属于阅读上下文、不是报告本体），
     * 但带全三个出口里口径最完整的一组原始数（策略族分母 / 剔除量 / 不可归因条数 / 加固建议）。
     */
    fun exportJson(report: Report? = null): File? {
        val r = report ?: (_testState.value as? TestState.Done)?.report ?: return null
        val json = ForensicsJson.encode(r)
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getApplication<Application>().cacheDir
        val file = File(dir, "aiseclab_report_${r.timestamp}.json")
        file.writeText(json)
        return file
    }

    /**
     * 基线对比：只在"基线比这份报告早"时才比。
     * 拿一份更新的报告当基线去比旧报告语义不对（那是"倒退"），UI 已经过滤，这里兜底。
     */
    private fun diffOf(report: Report): ReportDiff.Result? {
        val b = _baseline.value ?: return null
        if (b.timestamp >= report.timestamp) return null
        return ReportDiff.compare(b, report)
    }

    fun reset() {
        val app = getApplication<Application>()
        // ① 让服务走 **ACTION_STOP**，而不是直接 stopService。
        //    只有这样才会执行 TestRunService.stopRun() —— 它会 cancel 那根 SSE 连接。
        //    `stopService → onDestroy` 只 `scope.cancel()`，而协程取消**掐不断已经阻塞在
        //    OkHttp `execute()/readUtf8Line()` 里的线程**：旧协程会继续读流、继续把旧会话的
        //    进度写进界面与日志。2026-10-16 日志里 `进度 176` 之后突然冒出 `进度 2`，
        //    就是「停止」后旧协程没死、与新一轮抢同一个终端造成的（详见 TestRunService.activeRun）。
        //
        //    ⚠️ 这里**不**顺手 `TestRunStateHolder.reset()`：那会提前把运行名额交还出去，
        //    万一紧接着有人开了新一轮，稍后到达的 ACTION_STOP 会把**新一轮**一起停掉。
        //    清理交给服务自己的 stopRun()，它是串行的、不会打岔。
        runCatching {
            app.startService(
                Intent(app, TestRunService::class.java).apply {
                    action = TestRunService.ACTION_STOP
                }
            )
        }
        // ② 本 VM 自己那份镜像立刻置空（服务没起、或 startService 被系统拦下时界面也要先复位）
        _testState.value = TestState.Idle
    }
}
