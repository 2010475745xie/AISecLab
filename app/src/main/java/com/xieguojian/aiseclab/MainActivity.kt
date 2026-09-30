package com.xieguojian.aiseclab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.ui.TestViewModel
import com.xieguojian.aiseclab.ui.screens.BackendConfigScreen
import com.xieguojian.aiseclab.ui.screens.HistoryScreen
import com.xieguojian.aiseclab.ui.screens.HomeScreen
import com.xieguojian.aiseclab.ui.screens.ModelConfigScreen
import com.xieguojian.aiseclab.ui.screens.OnboardingScreen
import com.xieguojian.aiseclab.ui.screens.ReportScreen
import com.xieguojian.aiseclab.ui.screens.RunScreen
import com.xieguojian.aiseclab.ui.screens.TestConfigScreen
import com.xieguojian.aiseclab.ui.theme.AISecLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AISecLabTheme {
                AISecLabApp()
            }
        }
    }
}

/**
 * 屏幕层级：
 * ONBOARDING（首启引导）→ HOME（首页/新建测试入口）→ CONFIG（填模型）→ TEST（选维度开测）
 * → RUNNING（进度条+终端日志）→ REPORT（报告）；HISTORY 可从 HOME/CONFIG 进入。
 * BACKEND（测试后端设置）是全局设置页，可从 HOME 或 CONFIG 直达，返回回到来源页。
 */
enum class Screen { ONBOARDING, HOME, CONFIG, TEST, RUNNING, REPORT, HISTORY, BACKEND }

@Composable
fun AISecLabApp(vm: TestViewModel = viewModel()) {
    // 首屏：未走过引导 → 引导页，否则 → 首页
    var screen by remember { mutableStateOf(Screen.HOME) }
    LaunchedEffect(Unit) {
        if (!vm.onboarded.value) screen = Screen.ONBOARDING
    }

    // 历史记录里点开的报告；null = 展示本轮测试的当前报告
    var viewingHistory by remember { mutableStateOf<Report?>(null) }

    // 进入后端设置页的来源：从首页进就回首页，从配置页进就回配置页
    var backendFrom by remember { mutableStateOf(Screen.CONFIG) }

    // 测试状态驱动跳转：Running → 运行页，Done → 报告页（统一在顶层管，避免各页各自跳）。
    val testState by vm.testState.collectAsState()
    LaunchedEffect(testState) {
        when (testState) {
            is TestViewModel.TestState.Running -> if (screen != Screen.RUNNING) screen = Screen.RUNNING
            is TestViewModel.TestState.Done -> if (screen != Screen.REPORT) screen = Screen.REPORT
            else -> {}
        }
    }

    // 系统返回键逐级回退。HOME / ONBOARDING 是根，不接系统返回（避免误退）；
    // RUNNING 进行中禁用返回（用页内「停止」结束），防止切走后测试状态与界面脱节。
    BackHandler(
        enabled = when (screen) {
            Screen.HOME, Screen.ONBOARDING, Screen.RUNNING -> false
            else -> true
        }
    ) {
        when (screen) {
            Screen.TEST -> screen = Screen.CONFIG
            Screen.CONFIG -> screen = Screen.HOME
            Screen.HISTORY -> screen = Screen.HOME
            Screen.BACKEND -> screen = backendFrom
            Screen.REPORT -> {
                val fromHistory = viewingHistory != null
                viewingHistory = null
                screen = if (fromHistory) Screen.HISTORY else Screen.TEST
            }
            else -> {}
        }
    }

    when (screen) {
        Screen.ONBOARDING -> OnboardingScreen(
            onFinish = {
                vm.finishOnboarding()
                screen = Screen.HOME
            }
        )
        Screen.HOME -> HomeScreen(
            vm,
            onNewTest = { screen = Screen.CONFIG },
            onHistory = { screen = Screen.HISTORY },
            onBackend = { backendFrom = Screen.HOME; screen = Screen.BACKEND }
        )
        Screen.CONFIG -> ModelConfigScreen(
            vm,
            onNext = { screen = Screen.TEST },
            onHistory = { screen = Screen.HISTORY },
            onBackend = { backendFrom = Screen.CONFIG; screen = Screen.BACKEND }
        )
        Screen.TEST -> TestConfigScreen(
            vm,
            onBack = { screen = Screen.CONFIG }
        )
        Screen.RUNNING -> RunScreen(
            vm,
            onStop = {
                vm.reset()
                screen = Screen.CONFIG
            }
        )
        Screen.REPORT -> {
            val fromHistory = viewingHistory
            ReportScreen(
                vm,
                reportOverride = fromHistory,
                onBack = {
                    viewingHistory = null
                    screen = if (fromHistory != null) Screen.HISTORY else Screen.TEST
                },
                onRestart = {
                    vm.reset()
                    screen = Screen.CONFIG
                }
            )
        }
        Screen.HISTORY -> HistoryScreen(
            vm,
            onBack = { screen = Screen.HOME },
            onOpen = { report ->
                viewingHistory = report
                screen = Screen.REPORT
            }
        )
        Screen.BACKEND -> BackendConfigScreen(
            vm,
            onBack = { screen = backendFrom }
        )
    }
}
