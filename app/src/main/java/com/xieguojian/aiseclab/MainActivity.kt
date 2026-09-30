package com.xieguojian.aiseclab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xieguojian.aiseclab.core.model.Report
import com.xieguojian.aiseclab.ui.TestViewModel
import com.xieguojian.aiseclab.ui.screens.BackendConfigScreen
import com.xieguojian.aiseclab.ui.screens.HomeScreen
import com.xieguojian.aiseclab.ui.screens.ModelConfigScreen
import com.xieguojian.aiseclab.ui.screens.OnboardingScreen
import com.xieguojian.aiseclab.ui.screens.ReportScreen
import com.xieguojian.aiseclab.ui.screens.RunScreen
import com.xieguojian.aiseclab.ui.screens.SettingsScreen
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
 * 屏幕层级（底部标签栏导航，对标微信/QQ）：
 * 两个常驻标签页 —— 测试(HOME) / 设置(SETTINGS)；历史记录已合并进「测试」页。
 * 其余是子流程 / 全屏页，不显示底栏：CONFIG（填模型）→ TEST（选维度开测）
 * → RUNNING（进度+终端日志）→ REPORT（报告）；BACKEND（后端设置）从「设置」进。
 * 新建测试由「测试」页右下角悬浮「+」触发（CONFIG←HOME）。
 * 测试中允许系统返回回首页看进度（后台服务继续跑）；仅在 RUNNING 页看测试时完成才自动跳 REPORT。
 */
enum class Screen { ONBOARDING, HOME, CONFIG, TEST, RUNNING, REPORT, HISTORY, BACKEND, SETTINGS }

/** 底部标签栏的两个常驻页：测试（中心）/ 设置。历史已合并进「测试」页。 */
private enum class BottomTab(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
) {
    HOME(Screen.HOME, "测试", Icons.Filled.BugReport),
    SETTINGS(Screen.SETTINGS, "设置", Icons.Filled.Settings)
}

@Composable
fun AISecLabApp(vm: TestViewModel = viewModel()) {
    // 首屏：未走过引导 → 引导页，否则 → 首页
    var screen by remember { mutableStateOf(Screen.HOME) }
    LaunchedEffect(Unit) {
        if (!vm.onboarded.value) screen = Screen.ONBOARDING
    }

    // 历史记录里点开的报告；null = 展示本轮测试的当前报告
    var viewingHistory by remember { mutableStateOf<Report?>(null) }

    // 进入后端设置页的来源：从设置进就回设置，从配置进就回配置
    var backendFrom by remember { mutableStateOf(Screen.CONFIG) }
    // 进入配置表单的来源：从首页「新建测试」进就回首页，从「设置」进就回设置
    var configFrom by remember { mutableStateOf(Screen.HOME) }

    // 测试状态驱动跳转：Running → 运行页，Done → 报告页（统一在顶层管，避免各页各自跳）。
    val testState by vm.testState.collectAsState()
    LaunchedEffect(testState) {
        when (testState) {
            // 运行中不再强制跳运行页：用户在首页也能看进度、随时进出；
            // 只有「正在看运行页时」测试完成，才自动跳到报告页。
            is TestViewModel.TestState.Done -> if (screen == Screen.RUNNING) screen = Screen.REPORT
            else -> {}
        }
    }

    // 底栏只在三个常驻标签页显示；子流程/全屏页隐藏，避免遮挡进度与报告
    val showBottomBar = screen in setOf(Screen.HOME, Screen.SETTINGS)
    val currentTab = when (screen) {
        Screen.SETTINGS -> BottomTab.SETTINGS
        else -> BottomTab.HOME
    }

    // 系统返回键：常驻标签页（HOME/历史/设置/引导/运行中）是根，不接系统返回，避免误退；
    // RUNNING 进行中禁用返回（用页内「停止」结束），防止切走后测试状态与界面脱节。
    // 子流程按来源回退到对应标签页 / 父页。
    BackHandler(
        enabled = when (screen) {
            Screen.HOME, Screen.ONBOARDING, Screen.SETTINGS -> false
            else -> true
        }
    ) {
        when (screen) {
            Screen.TEST -> screen = Screen.CONFIG
            Screen.CONFIG -> screen = configFrom
            Screen.BACKEND -> screen = backendFrom
            // 测试中允许退出（系统返回）：回首页看进度，后台服务继续跑
            Screen.RUNNING -> screen = Screen.HOME
            Screen.REPORT -> {
                viewingHistory = null
                // 历史已合并进「测试」页，看历史报告后返回首页；看本轮报告也回首页
                screen = Screen.HOME
            }
            else -> {}
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { screen = tab.screen },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        },
        // 新建测试：仅在「测试」标签页显示右下角悬浮「+」
        floatingActionButton = {
            if (screen == Screen.HOME) {
                FloatingActionButton(onClick = { configFrom = Screen.HOME; screen = Screen.CONFIG }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建测试")
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (screen) {
                Screen.ONBOARDING -> OnboardingScreen(
                    onFinish = {
                        vm.finishOnboarding()
                        screen = Screen.HOME
                    }
                )
                Screen.HOME -> HomeScreen(
                    vm,
                    onOpenRun = { screen = Screen.RUNNING },
                    onViewReport = { screen = Screen.REPORT },
                    onOpenHistory = { report ->
                        viewingHistory = report
                        screen = Screen.REPORT
                    }
                )
                Screen.CONFIG -> ModelConfigScreen(
                    vm,
                    onNext = { screen = Screen.TEST },
                    onHistory = { screen = Screen.HOME }
                )
                Screen.TEST -> TestConfigScreen(
                    vm,
                    onBack = { screen = Screen.CONFIG },
                    onStart = { screen = Screen.RUNNING }
                )
                Screen.RUNNING -> RunScreen(
                    vm,
                    onStop = {
                        vm.reset()
                        screen = Screen.HOME
                    }
                )
                Screen.REPORT -> {
                    val fromHistory = viewingHistory
                    ReportScreen(
                        vm,
                        reportOverride = fromHistory,
                        onBack = {
                            viewingHistory = null
                            // 历史已合并进「测试」页：看历史报告后返回首页
                            screen = Screen.HOME
                        },
                        onRestart = {
                            vm.reset()
                            screen = Screen.CONFIG
                        }
                    )
                }
                Screen.BACKEND -> BackendConfigScreen(
                    vm,
                    onBack = { screen = backendFrom }
                )
                Screen.SETTINGS -> SettingsScreen(
                    vm,
                    onBackend = { backendFrom = Screen.SETTINGS; screen = Screen.BACKEND }
                )
                // 历史已合并进「测试」页：HISTORY 不再作为独立标签页，这里兜底不处理
                else -> {}
            }
        }
    }
}
