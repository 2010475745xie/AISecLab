package com.xieguojian.aiseclab.ui

/**
 * 通知栏进度文案（纯函数，可单测）。
 *
 * 为什么要把「复用 / 新跑」写进通知里：断点续测时进度会**"唰"地跳到一大截**
 * —— 复用条一个模型调用都不发、瞬间完成。只写 `180 / 314` 会让人以为是跳号，
 * 或者以为 token 又从头烧了一遍（2026-10-16 用户正是在这里困惑过）。
 *
 * 与 [com.xieguojian.aiseclab.ui.screens.RunScreen] 顶部那两行是**同一个口径**
 * （都来自后端 `progress` 事件的 `completed` / `resumed`，见 `BackendClient.SseEvent`）：
 * `新跑 = completed - resumed`。别在别处另算一遍。
 *
 * [resumed] ≤ 0（全新一轮 / 本机跑）时保持老文案，不加任何后缀 —— 免得给"本来就没有复用"
 * 的场景平添噪音。
 */
internal fun progressText(completed: Int, total: Int, resumed: Int): String {
    val base = "进行中 $completed / $total"
    if (resumed <= 0) return base
    // 后端下发的 resumed 理论上不会超过 completed，但把兜底算清楚：
    // 万一上游口径变了，宁可显示"新跑 0"也不要出现负数。
    return "$base（复用 $resumed · 新跑 ${(completed - resumed).coerceAtLeast(0)}）"
}
