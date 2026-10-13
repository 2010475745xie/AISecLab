package com.xieguojian.aiseclab.core

/**
 * 一轮测试的执行位置。
 *
 * 由 [resolveRunMode] 从「后端地址 + 本机运行测试开关」解析出来，
 * **是全 App 唯一的判定口径** —— UI（按钮可用性、提示文案）与服务（分支选择）
 * 都必须调它，不许各自再写一遍 `backendUrl.isNotBlank()`。
 * （同一个判据写两遍，某个数据形态下必然分叉，编译和单测都拦不住。）
 */
enum class RunMode(val label: String) {
    LOCAL("本机运行"),
    BACKEND("委托后端"),
}

/**
 * 解析本轮该在哪跑。
 *
 * 口径（2026-10-13 定稿）：
 * 1. **开关打开 → 本机跑，忽略后端地址。** 开关的字面意思就是「我要在本机跑」。
 *    旧口径是「开关只在后端地址为空时才起作用」，那有个死结：地址只要填过一次，
 *    想回本机跑就必须先去设置里把地址清空 —— 实测用户就是这么卡住的
 *    （开着开关、地址还留着，一开测照样走后端，后端不通就只能干瞪眼）。
 * 2. 开关关闭 + 地址非空 → 委托后端（默认路径）。
 * 3. 开关关闭 + 地址为空 → `null`：两者都没配，不允许开测。
 *
 * @param backendUrl 设置里填的测试后端地址（空白视为没填）。
 * @param localRunEnabled 设置页「本机运行测试」开关。
 * @return 本轮的执行位置；`null` 表示当前配置下不能开测。
 */
fun resolveRunMode(backendUrl: String, localRunEnabled: Boolean): RunMode? = when {
    localRunEnabled -> RunMode.LOCAL
    backendUrl.isNotBlank() -> RunMode.BACKEND
    else -> null
}
