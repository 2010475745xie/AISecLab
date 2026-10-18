package com.xieguojian.aiseclab.core.engine

/**
 * 进度展示的拼装逻辑 —— 抽成纯函数是为了能测（2026-10-17）。
 *
 * ## 起因：用户报「本机跑全部勾上也只有 50 条」
 *
 * 翻设备日志后，真正的症状是这两行挨在一起：
 * ```
 * ▶ 开始测试：共 314 条用例，维度 [安全性/逻辑一致性/事实性/鲁棒性/安全性]
 * ✓ [逻辑一致性] #1/32 《概率陷阱》 → 通过
 * ```
 * 预告 **314**、进度分母 **32**，当着用户的面互相打脸 —— 他看到的"条数不对"就是这个。
 *
 * 根因不是算术，是**递归对抗分支把子批次的条数当成了全局条数**：
 * - 递归开启时用例被拆成两批跑：非安全维度（11+10+11=32）走 `AttackEngine` 并发，
 *   安全维度（18+264=282）走 `RecursiveAdversarialAttack` 串行；
 * - 第一批调用时把外层的 `onProgress` **原样透传**进 `AttackEngine.run`，
 *   而它只知道手里这批有多少条 ⇒ 分母变 32；进度条在 32/32 就"满了"，
 *   紧接着安全维度又用 `onProgress(done, 314)` 把它拽回来，一晚上跳两次量级。
 *
 * 所以修法不是在回调里补救，而是**把"显示用的总条数"一路传下去**
 * （见 [AttackEngine.run] 的 `displayTotal`），并且维度串**必须去重**
 * （变体集模块的 dimension 也是 SECURITY，不去重就打成「安全性/…/安全性」）。
 */

/**
 * 本轮维度的展示串（日志/终端用）。
 *
 * [modules] 里可能有**两个 SECURITY 模块**（`SecurityModule` + `JailbreakVariantsModule`），
 * 直接 `map { it.dimension.label }` 会把「安全性」打两遍，看起来像重复跑了安全维度。
 */
internal fun dimensionLabels(modules: List<AttackModule>): String =
    modules.map { it.dimension.label }.distinct().joinToString("/")
