#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「用户看得见」的应用重启一遍 —— 用于清掉 App 侧缓存的旧显示配置。

背景：临时用 `wm size/density` 覆盖过显示参数（做宽屏验证），虽然已经
`wm size reset` / `wm density reset` 还原（`dumpsys window displays` 里
`overrideConfig` 全为 `?` = 无覆盖），但**部分 App 自己缓存的旧配置不会自己消失**，
表现为图标变小、界面变糙、留黑边之类。

做法与安全边界：
1. 只处理**有 LAUNCHER 入口**且**当前进程还活着**的应用 —— 也就是用户可能看到、
   且真正需要重新布局的那些；
2. **不碰**系统关键进程（电话/蓝牙/权限控制器/媒体provider/推送服务等），
   杀它们可能导致通话中断、蓝牙掉线、权限弹窗异常，而且它们没有可见 UI，杀了也没意义；
3. 桌面（launcher）单独处理：先杀再立刻用 `am start ... HOME` 拉回来，
   否则会看到黑屏等到系统自己重启桌面。

⚠️ 副作用（必须告诉用户）：被 force-stop 的第三方应用会进入 "stopped" 状态，
**在用户手动打开一次之前不会收到通知**（微信/QQ 的消息提醒会暂停）。这是可逆的。

用法：
    python _shot/restart-stale-apps.py
"""

import re
import subprocess
import sys
import time

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"

LAUNCHER_PKG = "com.hihonor.android.launcher"

# 即使有桌面入口也**不杀**：杀了会带来真实的可用性损失，而它们都不是"样子变了"的嫌疑对象
PROTECTED = {
    "com.android.settings",          # 杀掉本身无害，但用户可能正在看设置
}

# 明确排除的系统进程（部分并不在 LAUNCHER 清单里，列在这里是双保险）
NEVER = {
    "android", "android.process.acore", "android.ext.services",
    "com.android.bluetooth", "com.android.emergency", "com.android.keychain",
    "com.android.permissioncontroller", "com.android.providers.media.module",
    "com.android.adservices.api", "com.android.phone", "com.android.server.telecom",
    "com.hihonor.android.pushagent", "com.hihonor.android.pushagent.HiPushService",
    "com.hihonor.dsdscardmanager", "com.hihonor.trustspace",
}


def adb(*a):
    return subprocess.run([ADB, "-s", DEVICE, *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


def sh(*a):
    r = adb("shell", *a)
    return (r.stdout or r.stderr) or ""


def alive_pkgs():
    out = sh("dumpsys", "activity", "processes")
    pkgs = []
    for line in out.splitlines():
        if line.strip().startswith("*APP*"):
            m = re.search(r"\d+:([A-Za-z0-9_.]+)/", line)
            if m and m.group(1) not in pkgs:
                pkgs.append(m.group(1))
    return pkgs


def launchable_pkgs():
    out = sh("cmd", "package", "query-activities", "--brief",
             "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
    pkgs = set()
    for line in out.splitlines():
        line = line.strip()
        if "/" in line and not line.startswith("Activity") and "=" not in line:
            pkgs.add(line.split("/")[0])
    return pkgs


def foreground_pkg():
    """当前前台应用的包名 —— 绝不动它：用户可能正在看/正在写。"""
    out = sh("dumpsys", "window")
    for line in out.splitlines():
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.]+)/", line)
        if m:
            return m.group(1)
    return None


def main():
    alive = alive_pkgs()
    launch = launchable_pkgs()
    fg = foreground_pkg()
    print(f"当前前台：{fg}（不在重启范围内）\n")

    def skip_reason(p):
        if p == fg:
            return "前台应用，用户可能正在看/正在写"
        if p == LAUNCHER_PKG:
            return "桌面：重启它必须把 Home 拉到前台，会顶掉用户当前正在用的应用"
        if p in PROTECTED:
            return "保护名单（用户可能正在看设置）"
        if p in NEVER:
            return "系统关键进程（通话/蓝牙/权限/provider/推送）"
        if p not in launch:
            return "无桌面入口（后台服务，没有可见 UI，重启无意义）"
        return None

    targets = [p for p in sorted(alive) if skip_reason(p) is None]

    print(f"存活进程 {len(alive)} 个 → 要重启的可见应用 {len(targets)} 个：")
    for p in targets:
        print("   ", p)
    print("\n被跳过的（含原因）：")
    for p in sorted(alive):
        r = skip_reason(p)
        if r:
            print(f"    {p:<45} {r}")
    print()

    ok, fail = [], []
    for p in targets:
        r = adb("shell", "am", "force-stop", p)
        if r.returncode == 0:
            ok.append(p)
            print(f"  ✓ force-stop {p}")
        else:
            fail.append(p)
            print(f"  ✗ force-stop {p} -> {r.stderr.strip()[:80]}")

    print(f"\n重启完成：成功 {len(ok)} / 失败 {len(fail)}")
    if ok:
        print("⚠️ 被重启的第三方应用进入了 stopped 状态："
              "在你手动打开一次之前不会收到通知（消息提醒会暂时不响）。")


if __name__ == "__main__":
    main()
