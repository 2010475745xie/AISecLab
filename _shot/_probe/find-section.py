#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在**长页面**里定位某段文本（默认向上滚动找），打印它的 bounds 与上下文。

## 为什么需要它

报告页有 14 节、几千到上万像素高，用 `adb.py dump` 一次只能看到当前一屏。
手工反复 swipe + dump 很费时间，而且**很容易滑过头**（本节在 8 屏之后时，
"我滑了 5 次没看到" 会被误读成 "这一节没渲染" —— 本项目真出过这种误判：
加固建议在报告页**中部**，被当成"最后一节"去找，找不到就以为没渲染）。

本脚本把"滚到看见为止"变成确定性动作：找到就打印 bounds 并停下。

用法：
    python _shot/_probe/find-section.py "加固建议"            # 向上滚着找
    python _shot/_probe/find-section.py "加固建议" --max 25
    python _shot/_probe/find-section.py --text               # 只打印当前屏的全部文本
退出码：0 = 找到；1 = 滚到上限还没找到（**不等于没渲染**，见下）。
"""

import os
import re
import subprocess
import sys
import time

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PROJECT = r"C:\AISecLab"

SCREEN_W = 1200
SWIPE_FROM_Y = 2200
SWIPE_TO_Y = 500


def adb(*args):
    return subprocess.run([ADB, "-s", DEVICE, *args], capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


def dump_xml():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    local = os.path.join(PROJECT, "_shot", "_probe", "ui.xml")
    os.makedirs(os.path.dirname(local), exist_ok=True)
    adb("pull", "/sdcard/ui.xml", local)
    if not os.path.exists(local):
        return ""
    return open(local, encoding="utf-8", errors="replace").read()


def nodes(xml):
    """产出 (text, bounds)。text 取 text / content-desc 里非空的那个。"""
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = (re.search(r'\btext="([^"]*)"', t) or [None, ""])[1]
        cd = (re.search(r'\bcontent-desc="([^"]*)"', t) or [None, ""])[1]
        bd = re.search(r'bounds="([^"]*)"', t)
        label = txt or cd
        if label and bd:
            out.append((label, bd.group(1)))
    return out


def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(int(x) for x in m.groups()) if m else None


def print_text():
    for label, b in nodes(dump_xml()):
        print(f"  {b} {label!r}")
    return 0


def find(target, max_scroll=20):
    for i in range(max_scroll + 1):
        ns = nodes(dump_xml())
        hits = [(l, b) for l, b in ns if target in l]
        if hits:
            print(f"[第 {i} 屏] 命中 {len(hits)} 处：")
            for l, b in hits:
                print(f"  {b} {l[:200]!r}")
            # 顺手把命中处的**相邻节点**也打出来 —— 只看命中行容易漏掉"它下面那行才是正文"
            idx = [j for j, (l, _) in enumerate(ns) if target in l]
            for j in idx:
                for k in range(max(0, j - 1), min(len(ns), j + 3)):
                    print(f"      ctx {ns[k][1]} {ns[k][0][:160]!r}")
            return 0
        if i < max_scroll:
            adb("shell", "input", "swipe", str(SCREEN_W // 2), str(SWIPE_FROM_Y),
                str(SCREEN_W // 2), str(SWIPE_TO_Y))
            time.sleep(0.6)
    print(f"滚了 {max_scroll} 屏仍未命中 {target!r}")
    print("⚠️ 这不等于'没渲染'：可能是滚到底了、或该节在更后面。")
    print("   先确认它**应该**在哪一节（见 ReportScreen.kt 的 item 顺序），再判断。")
    return 1


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] == "--text":
        raise SystemExit(print_text())
    mx = 20
    if "--max" in args:
        k = args.index("--max")
        mx = int(args[k + 1])
        args = args[:k] + args[k + 2:]
    raise SystemExit(find(args[0], mx))
