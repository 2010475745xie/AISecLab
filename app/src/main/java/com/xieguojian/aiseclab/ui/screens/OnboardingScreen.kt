package com.xieguojian.aiseclab.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 首次启动引导页：左右滑动的 [HorizontalPager]，最后一页点「开始」进入 App。
 *
 * 是否展示由 [com.xieguojian.aiseclab.ui.TestViewModel.onboarded] 决定（[MainActivity] 控制）。
 * 走完后 [onFinish] 负责落盘标记并跳首页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = listOf(
        OnboardPage(
            icon = "🛡️",
            title = "欢迎使用 AISecLab",
            body = "给你的 AI 模型做一次红队测评：在越狱、一致性、事实性、鲁棒性等维度上，主动发现它会在哪些地方「失守」。"
        ),
        OnboardPage(
            icon = "🔑",
            title = "自带密钥，安全可控",
            body = "你自己的 API Key 只存在本机加密存储，绝不落明文。可完全在本机跑，也能委托你信任的后端服务器，被测 Key 仅任务周期内留存在服务器内存。"
        ),
        OnboardPage(
            icon = "🚀",
            title = "三步跑完一轮",
            body = "① 配置模型 → ② 选择维度并开测 → ③ 查看可视化报告。现在就开始给你的模型做一次测评吧。"
        )
    )

    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：跳过（非末页可见）
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (pagerState.currentPage < pages.lastIndex) {
                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { Text("跳过") }
            }
        }

        // 滑动内容区
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            val p = pages[page]
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(p.icon, style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(24.dp))
                Text(
                    p.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    p.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 底部：页码圆点 + 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 圆点指示器
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pages.forEachIndexed { i, _ ->
                    val selected = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }

            if (pagerState.currentPage < pages.lastIndex) {
                Button(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) { Text("下一步") }
            } else {
                Button(onClick = onFinish) { Text("开始") }
            }
        }
    }
}

private data class OnboardPage(
    val icon: String,
    val title: String,
    val body: String
)
