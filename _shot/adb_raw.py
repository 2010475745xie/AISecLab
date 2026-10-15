#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""adb 通用出口：把参数**原样**交给 adb（subprocess 列表调用，不经 shell）。

为什么单独有它：`_shot/adb.py` 只实现了 dump / shot / tap / key / swipe 五个动词，
**其余参数会静默落到 dump() 分支** —— 你写 `adb.py shell "dumpsys window"`，
它不报错，只是去截了个图。这个坑我踩过（两次回显一样才发现）。

需要任意 adb 命令（dumpsys / pm / am / settings / exec-out / logcat）时用本脚本。

用法：
    python _shot/adb_raw.py shell "dumpsys window | grep mCurrentFocus"
    python _shot/adb_raw.py shell pm list packages
    python _shot/adb_raw.py shell "wm size"
    python _shot/adb_raw.py --png _shot/x.png          # 截图（PNG）
    python _shot/adb_raw.py --raw _shot/frame.raw      # 裸帧：不带 -p，头部 + RGBA
                                                       # （无 PIL 也能精确取像素/量边界）
退出码 = adb 的退出码；stdout/stderr 原样透传（不加工、不截断）。
"""

import os
import subprocess
import sys

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PROJECT = r"C:\AISecLab"


def run(args, binary=False):
    return subprocess.run(
        [ADB, "-s", DEVICE, *args],
        capture_output=True,
        text=not binary,
        encoding=None if binary else "utf-8",
        errors=None if binary else "replace",
    )


def main(argv):
    if not argv:
        print(__doc__)
        return 2

    if argv[0] == "--save":
        # --save <本地路径> <任意 adb 参数...>：把 adb 的 stdout **二进制**写进本地文件。
        # 用途：`run-as <pkg> cat files/history/xxx.json` 这种读私有目录的活
        # （adb pull 拉不到 app 私有目录）。
        if len(argv) < 3:
            print("用法：--save <本地路径> <adb 参数...>")
            return 2
        p = os.path.join(PROJECT, argv[1])
        os.makedirs(os.path.dirname(p), exist_ok=True)
        r = run(argv[2:], binary=True)
        with open(p, "wb") as f:
            f.write(r.stdout)
        print(f"--save -> {p} ({len(r.stdout)} bytes, adb exit={r.returncode})")
        if r.stderr:
            sys.stderr.write(r.stderr.decode("utf-8", "replace"))
        return r.returncode

    if argv[0] in ("--png", "--raw"):
        dst = argv[1] if len(argv) > 1 else "_shot/frame." + ("png" if argv[0] == "--png" else "raw")
        p = os.path.join(PROJECT, dst)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        # --png 带 -p（PNG 编码）；--raw 不带（前 12/16 字节 w/h/format 头 + 原始 RGBA）
        extra = ["-p"] if argv[0] == "--png" else []
        r = run(["exec-out", "screencap", *extra], binary=True)
        with open(p, "wb") as f:
            f.write(r.stdout)
        print(f"{argv[0]} -> {p} ({len(r.stdout)} bytes)")
        return r.returncode

    r = run(argv)
    if r.stdout:
        sys.stdout.write(r.stdout)
    if r.stderr:
        sys.stderr.write(r.stderr)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
