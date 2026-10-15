#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""造 / 推 / 清「注入样本」—— 用来在真机上覆盖真实历史报告覆盖不到的取数分支。

## 为什么需要注入样本

B5 的 **SAMPLED 档** 与 B4 的后两态（`compared == 0` 的"对照没做成"、`compared > 0`
的真对照）在**生产数据里根本不会出现**：
- 13 份真实历史报告里，凡是有 `stabilityMeasured` 的都同时带 LLM 裁判 ⇒ 期望档全是
  `NON_DETERMINISTIC`，SAMPLED 档没有一个样本；
- B4 本轮只交付形状，**没有任何生产代码会写出 `externalCheck`** ⇒ 后两态永远不会出现。

要真机验证这两块，只能**注入**。基底用**真实报告**改字段（保留真实结构），
不从零编 —— 编出来的结构一旦与真实报告有差异，验的就不是生产路径了。

## 三条纪律（比脚本本身重要）

1. **不覆盖任何真实报告**：文件名用远未来的时间戳（2027-01-01），列表里排最前、
   日期一眼可辨；`--clean` 只删这一批前缀的文件。
2. **验完立刻清**（`--clean`）：设备上留下的伪造报告会被当成真实测评结论 ——
   那是比"没验证"更糟的状态（假证据）。
3. **注入样本不得当作外部效度/复现性的证据**：它只证明"取数分支与渲染对"。

用法：
    python _shot/_probe/inject-fixtures.py --make    # 生成到 _shot/_probe/fixtures/
    python _shot/_probe/inject-fixtures.py --push    # make + 推到设备 files/history/
    python _shot/_probe/inject-fixtures.py --list    # 列出设备上已注入的
    python _shot/_probe/inject-fixtures.py --clean   # 从设备删除（只删本批）
"""

import json
import os
import subprocess
import sys

ADB = r"D:\SDK\platform-tools\adb.exe"
DEVICE = "ADNQUT6227001894"
PKG = "com.xieguojian.aiseclab"
PROJECT = r"C:\AISecLab"
SRC = os.path.join(PROJECT, "_shot", "_probe", "reports")
DST = os.path.join(PROJECT, "_shot", "_probe", "fixtures")

# 远未来的时间戳基数（2027-01-01 起）—— 列表里排最前、日期一眼可辨，
# 不会与任何真实报告的时间段重叠。
BASE_TS = 1798761600000  # 2027-01-01 00:00:00 GMT+8

# 注入样本的文件名前缀：--clean 只认它，绝不误删真实报告
PREFIX = "report_17987"


def adb(*args, binary=False):
    return subprocess.run(
        [ADB, "-s", DEVICE, *args], capture_output=True,
        text=not binary, encoding=None if binary else "utf-8",
        errors=None if binary else "replace",
    )


def load_src(name):
    p = os.path.join(SRC, name)
    if not os.path.exists(p):
        raise SystemExit(f"找不到基底报告 {p}（先跑 _shot/_probe/pull-reports.py）")
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def force_non_llm(r, fallback="L2_SEMANTIC"):
    """把 LLM 裁判的条目改成规则层 —— 让期望的复现性档从 NON_DETERMINISTIC 变成 SAMPLED/DETERMINISTIC。"""
    n = 0
    for x in r.get("results", []):
        jr = x.get("judgeResult", {})
        if jr.get("level") == "LLM":
            jr["level"] = fallback
            n += 1
    return n


def make():
    os.makedirs(DST, exist_ok=True)
    made = []

    # ── A: SAMPLED 档（规则层判定 + 目标侧多次采样）──
    # 基底取「50 条带采样的 LLM 报告」，把 level 降成规则层 —— 采样标志原样保留。
    r = load_src("report_1791875293638.json")
    force_non_llm(r)
    r["timestamp"] = BASE_TS + 3000
    # 造完先自检期望值：必须是「有采样、无 LLM」
    assert any(x.get("stabilityMeasured") for x in r["results"]), "样本 A 没有采样标志"
    assert not any(x["judgeResult"]["level"] == "LLM" for x in r["results"]), "样本 A 还有 LLM"
    p = os.path.join(DST, f"{PREFIX}0000000003.json")
    json.dump(r, open(p, "w", encoding="utf-8"), ensure_ascii=False)
    made.append(p)

    # ── B: 外部对照「接入了但一条都没拿到」（compared = 0）──
    r = load_src("report_1791875293638.json")
    r["timestamp"] = BASE_TS + 2000
    r["externalCheck"] = {
        "providerId": "stub", "providerLabel": "桩（仅测试）",
        "compared": 0, "agreed": 0, "disagreementIds": [], "unavailable": 50,
    }
    p = os.path.join(DST, f"{PREFIX}0000000002.json")
    json.dump(r, open(p, "w", encoding="utf-8"), ensure_ascii=False)
    made.append(p)

    # ── C: 外部对照「真对照过」（compared > 0，含分歧与不可用）──
    r = load_src("report_1791875293638.json")
    r["timestamp"] = BASE_TS + 1000
    ids = [x["testCase"]["id"] for x in r["results"][:4]]
    r["externalCheck"] = {
        "providerId": "stub", "providerLabel": "桩（仅测试）",
        "compared": 40, "agreed": 36, "disagreementIds": ids, "unavailable": 10,
    }
    p = os.path.join(DST, f"{PREFIX}0000000001.json")
    json.dump(r, open(p, "w", encoding="utf-8"), ensure_ascii=False)
    made.append(p)

    print(f"生成 {len(made)} 份注入样本 -> {DST}")
    for p in made:
        print(f"  {os.path.basename(p)}  ({os.path.getsize(p)} bytes)")
    print()
    print("期望（真机上必须逐条对上，对不上就是 bug）：")
    print("  A ...0003 : 复现性 = 采样可复现（SAMPLED）；外部对照 = 未接入")
    print("  B ...0002 : 外部对照 = 「已接入（桩（仅测试）），但本轮没有任何一条拿到可用结果」")
    print("  C ...0001 : 外部对照 = 一致 36/40（90%）、分歧 4 条、另有 10 条不可用")
    return made


def push(made):
    ok = 0
    for p in made:
        name = os.path.basename(p)
        tmp = f"/data/local/tmp/{name}"
        r1 = adb("push", p, tmp)
        if r1.returncode != 0:
            print(f"  !! push 失败 {name}: {r1.stderr}")
            continue
        # run-as 以 app 身份执行 cp —— 只有它能写进 files/history/
        r2 = adb("shell", "run-as", PKG, "cp", tmp, f"files/history/{name}")
        if r2.returncode != 0:
            print(f"  !! cp 失败 {name}: {r2.stderr}")
            continue
        adb("shell", "rm", "-f", tmp)
        ok += 1
        print(f"  -> files/history/{name}")
    print(f"推送完成 {ok}/{len(made)}")
    return ok


def remote_list():
    r = adb("shell", "run-as", PKG, "ls", "files/history")
    names = [l.strip() for l in (r.stdout or "").splitlines() if l.strip().endswith(".json")]
    injected = [n for n in names if n.startswith(PREFIX)]
    print(f"设备上共 {len(names)} 份报告，其中注入样本 {len(injected)} 份：")
    for n in injected:
        print(f"  {n}")
    return injected


def clean():
    injected = remote_list()
    for n in injected:
        adb("shell", "run-as", PKG, "rm", "-f", f"files/history/{n}")
        print(f"  已删除 files/history/{n}")
    left = remote_list()
    print(f"清理完成，剩余注入样本 {len(left)} 份（应为 0）")
    return len(left)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "--make"
    if cmd == "--make":
        make()
    elif cmd == "--push":
        push(make())
    elif cmd == "--list":
        remote_list()
    elif cmd == "--clean":
        raise SystemExit(0 if clean() == 0 else 1)
    else:
        print(__doc__)
