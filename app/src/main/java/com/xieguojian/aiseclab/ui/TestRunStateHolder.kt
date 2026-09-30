package com.xieguojian.aiseclab.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun appendLog(line: String) {
        val next = _log.value.toMutableList().apply {
            add(line)
            while (size > MAX_LOG_LINES) removeAt(0)
        }
        _log.value = next
    }

    fun update(s: TestViewModel.TestState) {
        // 新一轮测试以 Running(0,0) 起始：清空上一轮残留日志，避免和新日志串在一起。
        if (s is TestViewModel.TestState.Running && s.completed == 0 && s.total == 0) {
            _log.value = emptyList()
        }
        _state.value = s
    }

    fun reset() {
        _state.value = TestViewModel.TestState.Idle
        _log.value = emptyList()
    }

    val isRunning: Boolean
        get() = _state.value is TestViewModel.TestState.Running
}
