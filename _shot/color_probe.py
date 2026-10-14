#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""取原始帧做配色统计（不依赖 Pillow）。

`adb exec-out screencap`（不带 -p）给的是裸 RGBA：前 12/16 字节是 w,h,format(,colorspace)，
之后每个像素 4 字节。比 16 位色的 PNG 更适合做精确取样 —— 也就绕开了本机没有 PIL 的问题。

用法：
    python _shot/color_probe.py             # 当前帧：直方图 + 关键取样点亮度
    python _shot/color_probe.py both        # 明暗两档各取一帧对照（会切系统夜间模式，验后还原）
"""

import collections
import struct
import subprocess
import sys
import time

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"


def raw():
    r = subprocess.run([ADB, "-s", DEVICE, "exec-out", "screencap"], capture_output=True)
    b = r.stdout
    w, h, fmt = struct.unpack("<III", b[:12])
    off = 16 if len(b) >= 16 + w * h * 4 else 12
    return w, h, b[off:]


def lum(c):
    return round(0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2], 1)


def probe(tag):
    w, h, px = raw()

    def at(x, y):
        i = (y * w + x) * 4
        return (px[i], px[i + 1], px[i + 2])

    cnt = collections.Counter()
    for y in range(0, h, 6):
        for x in range(0, w, 6):
            cnt[at(x, y)] += 1
    print(f"===== {tag}  ({w}x{h}) =====")
    print("  主色 top8（含亮度）：")
    for c, n in cnt.most_common(8):
        print(f"    {str(c):>16}  x{n:<6} lum={lum(c)}")
    # 关键语义色取样：风险徽标 / 大分数 / 正常文字 / 卡片填充 / 页面底
    pts = {
        "页面底色": (600, 1290),
        "大分数 98.0": (150, 610),
        "风险徽标·低风险": (1090, 620),
    }
    print("  取样点：")
    for k, (x, y) in pts.items():
        c = at(x, y)
        print(f"    {k:<16} {str(c):>16} lum={lum(c)}")


def set_night(v):
    subprocess.run([ADB, "-s", DEVICE, "shell", "cmd", "uimode", "night", v], capture_output=True)
    time.sleep(2.5)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "both":
        orig = subprocess.run([ADB, "-s", DEVICE, "shell", "cmd", "uimode", "night"],
                              capture_output=True, text=True).stdout.strip()
        print(f"原夜间模式：{orig!r}")
        keep = "yes" if "yes" in orig else "no"
        try:
            for v, tag in (("no", "浅色档"), ("yes", "深色档")):
                set_night(v)
                probe(tag)
        finally:
            set_night(keep)
            now = subprocess.run([ADB, "-s", DEVICE, "shell", "cmd", "uimode", "night"],
                                 capture_output=True, text=True).stdout.strip()
            print(f"已还原为：{now!r}（原状态 {orig!r}）")
    else:
        probe("当前帧")
