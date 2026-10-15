#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把设备上的历史报告全拉到本地 `_shot/_probe/reports/`（真机验收常用）。

为什么要它：报告在 App 私有目录，`adb pull` 拉不到，只能
`exec-out run-as <pkg> cat ...`（用 _shot/adb_raw.py 的 --save 也行，但 13 份要 13 次调用）。
本脚本一次拉完，并做「长度 + 是不是合法 JSON」的自检 —— 半截文件比没有文件更危险
（读的时候报 json 错，容易被当成"报告本身坏了"）。
"""

import json
import os
import subprocess

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PKG = "com.xieguojian.aiseclab"
PROJECT = r"C:\AISecLab"
DST = os.path.join(PROJECT, "_shot", "_probe", "reports")


def adb(*args, binary=False):
    return subprocess.run(
        [ADB, "-s", DEVICE, *args], capture_output=True,
        text=not binary, encoding=None if binary else "utf-8",
        errors=None if binary else "replace",
    )


def main():
    os.makedirs(DST, exist_ok=True)
    ls = adb("shell", "run-as", PKG, "ls", "files/history")
    names = [l.strip() for l in (ls.stdout or "").splitlines() if l.strip().endswith(".json")]
    if not names:
        print(f"设备上没有历史报告（ls 输出：{(ls.stdout or '').strip()!r}）")
        return 1

    ok = bad = 0
    for n in names:
        r = adb("exec-out", "run-as", PKG, "cat", f"files/history/{n}", binary=True)
        data = r.stdout or b""
        p = os.path.join(DST, n)
        with open(p, "wb") as f:
            f.write(data)
        try:
            json.loads(data.decode("utf-8"))
            ok += 1
        except Exception as e:
            bad += 1
            print(f"  !! {n} 不是合法 JSON（{len(data)} bytes）：{e}")
    # 设备上可能有本地已删的旧文件 —— 清掉，避免把陈旧样本当成"刚拉的"
    keep = set(names)
    for p in os.listdir(DST):
        if p.endswith(".json") and p not in keep:
            os.remove(os.path.join(DST, p))
            print(f"  -- 删除本地陈旧样本 {p}")

    print(f"拉取 {len(names)} 份 -> {DST}（合法 JSON {ok} / 损坏 {bad}）")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
