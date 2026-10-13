package com.xieguojian.aiseclab.core.api

/**
 * 后端 SSE 摘要 → 终端日志行。
 *
 * 委托后端跑时，后端会在每帧 `progress` 里带一段**多行**摘要（判定 + 输入/回答/依据片段，
 * 见后端 `app/engine.py::progress_message`），与本机跑时 `AttackEngine` 打的日志同构。
 *
 * 为什么要拆成多行再入日志，而不是把整段当成一条：
 * - `TestRunStateHolder.appendLog` 是**一条一格**的（列表项），整段塞进去会让终端里
 *   一条用例占掉一大块，和本机跑时的观感完全对不上；
 * - 后续要按行过滤/检索（比如只看失守行）时，一条格没法拆。
 *
 * 抽成独立纯函数是为了能测：这段逻辑一旦写错（比如忘了过滤空行），
 * 界面上只是"多几个空行"，肉眼很难发现是它的问题。
 */
fun sseDetailLines(message: String?): List<String> =
    message?.lines()?.filter { it.isNotBlank() } ?: emptyList()

/**
 * 一帧 `progress` 事件 → 终端日志行（纯函数，无副作用）。
 *
 * 第一行是计数行 `● [后端] [security] 进度 3/18`，其后是该条用例的摘要行（见 [sseDetailLines]）。
 * 抽出来是为了钉住接线本身：之前这段是裸写在 `BackendClient` 的 `when` 分支里，
 * 一旦有人删掉 [sseDetailLines] 那一行、或忘了拼 `dimension`，界面上不会有任何报错，
 * 只是又变回"终端里看不到模型回复"（正是用户 2026-10-13 报的现象）。有单测才拦得住。
 */
internal fun progressLogLines(ev: BackendClient.SseEvent): List<String> {
    val dim = ev.dimension?.let { "[$it] " } ?: ""
    return listOf("● [后端] ${dim}进度 ${ev.completed}/${ev.total}") + sseDetailLines(ev.message)
}
