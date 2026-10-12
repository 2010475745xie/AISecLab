# -*- coding: utf-8 -*-
"""AISecLab 真机操作封装。

为什么要有这个文件：Git Bash 会把 /PID、/sdcard 这类参数当 Windows 路径改写，
所以一律走 subprocess.run(list) 直调 adb，不经过 shell。

用法（在 C:\\AISecLab 下）:
  python _shot/adb_dev.py devices
  python _shot/adb_dev.py shell <args...>          # 普通 shell
  python _shot/adb_dev.py runas <args...>          # run-as com.xieguojian.aiseclab ...
  python _shot/adb_dev.py start                    # 重启 App
  python _shot/adb_dev.py tap X Y
  python _shot/adb_dev.py swipe X1 Y1 X2 Y2 [ms]
  python _shot/adb_dev.py back
  python _shot/adb_dev.py dump [out.xml]           # uiautomator dump 并拉回
  python _shot/adb_dev.py shot out.png
"""
import os
import subprocess
import sys

ADB = r"D:\SDK\platform-tools\adb.exe"
PKG = "com.xieguojian.aiseclab"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run(args, binary=False, timeout=60):
    p = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    if binary:
        return p.returncode, p.stdout, p.stderr
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    rest = sys.argv[2:]

    if cmd == "devices":
        rc, out, err = run(["devices", "-l"])
        print(out, err)

    elif cmd == "shell":
        rc, out, err = run(["shell"] + rest)
        print("rc=", rc)
        print(out, err)

    elif cmd == "runas":
        rc, out, err = run(["shell", "run-as", PKG] + rest)
        print("rc=", rc)
        print(out, err)

    elif cmd == "start":
        rc, out, err = run(["shell", "am", "start", "-n", f"{PKG}/.MainActivity"])
        print("rc=", rc, out, err)

    elif cmd == "tap":
        rc, out, err = run(["shell", "input", "tap", rest[0], rest[1]])
        print("rc=", rc, out, err)

    elif cmd == "swipe":
        ms = rest[4] if len(rest) > 4 else "300"
        rc, out, err = run(["shell", "input", "swipe", rest[0], rest[1], rest[2], rest[3], ms])
        print("rc=", rc, out, err)

    elif cmd == "back":
        rc, out, err = run(["shell", "input", "keyevent", "KEYCODE_BACK"])
        print("rc=", rc, out, err)

    elif cmd == "dump":
        out_path = rest[0] if rest else os.path.join(ROOT, "_shot", "ui_dump.xml")
        rc, out, err = run(["shell", "uiautomator", "dump", "/sdcard/ui_dump.xml"])
        print("dump rc=", rc, out.strip(), err.strip())
        rc2, data, err2 = run(["exec-out", "cat", "/sdcard/ui_dump.xml"], binary=True)
        if rc2 == 0 and data:
            with open(out_path, "wb") as f:
                f.write(data)
            print("saved", out_path, len(data), "bytes")
        else:
            print("pull failed rc=", rc2, err2)

    elif cmd == "shot":
        out_path = rest[0] if rest else os.path.join(ROOT, "_shot", "dev.png")
        rc, data, err = run(["exec-out", "screencap", "-p"], binary=True)
        if rc == 0 and data:
            with open(out_path, "wb") as f:
                f.write(data)
            print("saved", out_path, len(data), "bytes")
        else:
            print("shot failed rc=", rc, err)

    else:
        print("unknown cmd", cmd)
        print(__doc__)


if __name__ == "__main__":
    main()
