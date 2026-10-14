#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""平板宽屏下「内容限宽」到底居不居中 —— 用原始帧量，不靠肉眼估。

做法：把显示临时改成等效 ~1066dp 宽（> ReportScreen 的 840dp 上限），
进报告页，然后在原始帧里找卡片填充色的 x 范围，得到内容块的左右边距；验后还原显示参数。

注意：改 density 会让 Activity 重建、导航状态回到首页，所以必须**改完再进报告页**。

用法：
    python _shot/wide_layout_probe.py
"""

import collections
import re
import struct
import subprocess
import sys
import time

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PKG = "com.xieguojian.aiseclab"

# 临时覆盖值：1600/1.5 = 1066dp 宽，稳稳超过 CONTENT_MAX_WIDTH=840dp
OVR_SIZE = "1600x2560"
OVR_DENSITY = "240"


def adb(*a):
    return subprocess.run([ADB, "-s", DEVICE, *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


def raw():
    r = subprocess.run([ADB, "-s", DEVICE, "exec-out", "screencap"], capture_output=True)
    b = r.stdout
    w, h, _ = struct.unpack("<III", b[:12])
    off = 16 if len(b) >= 16 + w * h * 4 else 12
    return w, h, b[off:]


def set_display(size, density):
    adb("shell", "wm", "size", size)
    adb("shell", "wm", "density", density)
    time.sleep(3.5)


def extent(w, h, at, color, min_run=20):
    """某颜色成片出现的 x 范围（要求该列至少有 min_run 个像素是这个色，避开零星误命中）。"""
    cols = [x for x in range(w)
            if sum(1 for y in range(0, h, 2) if at(x, y) == color) >= min_run]
    return (cols[0], cols[-1]) if cols else None


def measure(tag):
    w, h, px = raw()

    def at(x, y):
        i = (y * w + x) * 4
        return (px[i], px[i + 1], px[i + 2])

    cnt = collections.Counter()
    for y in range(0, h, 6):
        for x in range(0, w, 6):
            cnt[at(x, y)] += 1
    top = cnt.most_common(4)
    print(f"===== {tag} 帧 {w}x{h} 主色 top4：{top} =====")

    dens = adb("shell", "wm", "density").stdout
    m = re.search(r"Override density: (\d+)", dens)
    dpi = int(m.group(1)) if m else int(re.search(r"Physical density: (\d+)", dens).group(1))
    scale = dpi / 160.0
    print(f"  密度 {dpi} dpi → 1dp = {scale}px；屏宽 = {w / scale:.0f}dp")

    # 主色是页面底色（铺满全宽），第二主色是卡片填充 —— 卡片范围 = 内容块范围
    for name, color in (("页面底色", top[0][0]), ("卡片填充", top[1][0])):
        e = extent(w, h, at, color)
        if not e:
            print(f"  {name} {color}：没有成片区域")
            continue
        l, r = e
        print(f"  {name} {color}：x=[{l},{r}] 宽 {(r - l) / scale:.0f}dp"
              f"；左边距 {l / scale:.0f}dp、右边距 {(w - r - 1) / scale:.0f}dp")
        if name == "卡片填充":
            ok = abs(l - (w - r - 1)) <= 8 * scale
            print(f"    → 内容块{'居中 ✓' if ok else '**贴左、右侧留白（不居中）**'}")


if __name__ == "__main__":
    try:
        set_display(OVR_SIZE, OVR_DENSITY)
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(3.2)
        print("--- 首页（无 840dp 上限，作对照）---")
        measure("首页")
        subprocess.run([ADB, "-s", DEVICE, "exec-out", "screencap", "-p"],
                       stdout=open(r"C:\AISecLab\_shot\p3_wide_home.png", "wb"))
        # 用 uiautomator 找第一条历史卡的可点区域（宽屏下坐标与手机不同，不能写死）
        adb("shell", "uiautomator", "dump", "/sdcard/w.xml")
        subprocess.run([ADB, "-s", DEVICE, "pull", "/sdcard/w.xml", r"C:\AISecLab\_shot\w.xml"],
                       capture_output=True)
        xml = open(r"C:\AISecLab\_shot\w.xml", encoding="utf-8", errors="replace").read()
        cands = []
        for m in re.finditer(r'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 - x1 > 300 and y2 - y1 > 60:
                cands.append((y1, x1, y2, x2))
        print("可点候选（按 y 排序）：", cands[:6])
        if cands:
            y1, x1, y2, x2 = min(cands)
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            print(f"点第一条历史卡 ({cx},{cy})")
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(1.8)
        print("--- 报告页（有 840dp 上限）---")
        measure("报告页")
        subprocess.run([ADB, "-s", DEVICE, "exec-out", "screencap", "-p"],
                       stdout=open(r"C:\AISecLab\_shot\p2_wide_report.png", "wb"))
        print("截图 -> _shot/p2_wide_report.png / p3_wide_home.png")
    finally:
        adb("shell", "wm", "size", "reset")
        adb("shell", "wm", "density", "reset")
        time.sleep(3.0)
        print("已还原显示参数：", adb("shell", "wm", "size").stdout.strip().replace("\n", " | "),
              "/", adb("shell", "wm", "density").stdout.strip().replace("\n", " | "))
