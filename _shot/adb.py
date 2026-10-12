#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""adb 包装：**必须用列表参数调 adb，不能经 bash**。

原因：Git Bash（MSYS）会把 `/sdcard/ui.xml`、`/PID` 这类以斜杠开头的参数
当成路径做自动转换，转成 `C:/.../PortableGit/sdcard/ui.xml` ——
`uiautomator dump /sdcard/ui.xml` 于是报 "dumped to: /C:/.../sdcard/ui.xml"
并且文件根本不在设备上。用 subprocess 列表调用可以完全绕开 shell 转换。

用法：
    python _shot/adb.py dump            # dump 当前界面到 _shot/ui.xml 并打印可点节点
    python _shot/adb.py shot _shot/x.png # 截图
    python _shot/adb.py tap 540 1200     # 点击
"""

import os
import re
import subprocess
import sys

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PROJECT = r"C:\AISecLab"


def adb(*args, text=True):
    """经 subprocess 列表调用 adb —— 不经 shell，不做 MSYS 路径转换。"""
    return subprocess.run(
        [ADB, "-s", DEVICE, *args],
        capture_output=True,
        text=text,
        encoding="utf-8" if text else None,
        errors="replace" if text else None,
    )


def shot(path):
    p = os.path.join(PROJECT, path)
    r = subprocess.run([ADB, "-s", DEVICE, "exec-out", "screencap", "-p"], capture_output=True)
    with open(p, "wb") as f:
        f.write(r.stdout)
    print(f"screencap -> {p} ({len(r.stdout)} bytes)")


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    local = os.path.join(PROJECT, "_shot", "ui.xml")
    r = subprocess.run([ADB, "-s", DEVICE, "pull", "/sdcard/ui.xml", local], capture_output=True, text=True)
    if not os.path.exists(local):
        print("pull 失败：", r.stdout, r.stderr)
        return
    xml = open(local, encoding="utf-8", errors="replace").read()
    print(f"ui.xml {len(xml)} chars")
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = (re.search(r'text="([^"]*)"', t) or [None, ""])[1]
        cd = (re.search(r'content-desc="([^"]*)"', t) or [None, ""])[1]
        bd = re.search(r'bounds="([^"]*)"', t)
        cl = (re.search(r'clickable="([^"]*)"', t) or [None, ""])[1]
        label = txt or cd
        if bd and (cl == "true" or label):
            print(f"  click={cl} {bd.group(1)} {label!r}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "tap":
        x, y = sys.argv[2], sys.argv[3]
        adb("shell", "input", "tap", x, y)
        print(f"tap {x},{y}")
    elif cmd == "key":
        adb("shell", "input", "keyevent", sys.argv[2])
        print(f"keyevent {sys.argv[2]}")
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *sys.argv[2:6])
        print("swipe", sys.argv[2:6])
    else:
        dump()
