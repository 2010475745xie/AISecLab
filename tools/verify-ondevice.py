#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""端上 vs PC 判定一致性验收的编排脚本。

做四件事，顺序固定：
  1. 让 Kotlin 侧渲染出**同一批 prompt**（`GuardParityDumpTest`）；
  2. 把这些 prompt 打到 **PC 侧 llama-server**，收原始响应；
  3. 把 prompts.bin 推到真机，用 `xguard_cli` 跑出端上结果；
  4. 让 Kotlin 侧的 `GuardParityCompareTest` 用**同一份生产解析代码**判定两边的输出并断言一致。

为什么判定要放回 Kotlin 而不是在这个脚本里做：
  如果这里用 Python 重写一遍"首 token → 标签 → 危险度"，那就是两套判定实现，
  验收本身就变成了第三份可能漂移的东西。脚本只搬数据，不下判定。

前置条件：
  * PC 侧 llama-server 在 127.0.0.1:8000 活着
        python -m llama_cpp.server --model D:/models/YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf \
            --model_alias xguard-0.6b --n_ctx 2048 --n_threads 8 --host 127.0.0.1 --port 8000
  * 真机已连着 adb，且是 arm64-v8a
  * `tools/build-native.py` 已产出 build/native-tools/xguard_cli

用法：
    python tools/verify-ondevice.py                 # 全流程
    python tools/verify-ondevice.py --device-only   # 跳过 PC 侧 HTTP（pc.json 已存在时）
    python tools/verify-ondevice.py --daemon        # 顺带把 PC 侧服务起起来（如果没起）
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

PROJECT = r"C:\AISecLab"
PY_MANAGED = r"C:\Users\谢国健\.workbuddy\binaries\python\versions\3.13.12\python.exe"
PY_VENV = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
ADB = r"D:\SDK\platform-tools\adb.exe"
MODEL_LOCAL = r"D:\models\YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"
CLI_LOCAL = os.path.join(PROJECT, "build", "native-tools", "xguard_cli")

PARITY_DIR = r"D:\xguard-parity"
DEV_DIR = "/data/local/tmp"
DEV_MODEL = DEV_DIR + "/xguard.gguf"
DEV_CLI = DEV_DIR + "/xguard_cli"
DEV_PROMPTS = DEV_DIR + "/prompts.bin"

PC_BASE = "http://127.0.0.1:8000"
PC_MODEL = "xguard-0.6b"
PC_MAX_TOKENS = 64
DEV_MAX_TOKENS = 64
DEV_THREADS = 4

RS = "\x1e"


def log(msg):
    print(msg, flush=True)


def sh(cmd, timeout=120, cwd=None):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace",
                          timeout=timeout, cwd=cwd)


def pc_alive():
    try:
        # trust_env=False 的等价物：显式不带代理，否则 127.0.0.1 会被沙箱代理打成 400
        op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        r = op.open(PC_BASE + "/v1/models", timeout=4)
        return r.status == 200
    except Exception:
        return False


def run_step(title):
    log("")
    log("=" * 78)
    log(title)
    log("=" * 78)


def step_compile():
    run_step("[1/5] 编译测试代码（Kotlin 侧模板渲染 + 比对都在这上面跑）")
    r = sh([PY_VENV, "-u", os.path.join(PROJECT, "tools", "build-apk.py"),
            ":app:compileDebugUnitTestKotlin"], timeout=1800)
    log(r.stdout.strip()[-3000:])
    if r.returncode != 0:
        log(r.stderr.strip()[-2000:])
        raise SystemExit("测试代码编译失败")


def step_dump():
    run_step("[2/5] Kotlin 渲染一致性样本 → prompts.bin / cases.json")
    r = sh([PY_MANAGED, "-u", os.path.join(PROJECT, "tools", "run-unit-tests.py"),
            "-Dxguard.parity.dir=" + PARITY_DIR,
            "com.xieguojian.aiseclab.GuardParityDumpTest"], timeout=600)
    log(r.stdout.strip()[-3000:])
    if r.returncode != 0:
        log(r.stderr.strip()[-2000:])
        raise SystemExit("渲染失败")
    if not os.path.exists(os.path.join(PARITY_DIR, "prompts.bin")):
        raise SystemExit("prompts.bin 未生成，检查上面输出")


def step_pc_collect():
    run_step("[3/5] 同一批输入打到 PC 侧 llama-server，收原始响应")
    cases = json.load(open(os.path.join(PARITY_DIR, "cases.json"), encoding="utf-8"))
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    bodies = []
    for c in cases:
        body = {
            "model": PC_MODEL,
            "messages": [
                {"role": "user", "content": c["query"]},
                {"role": "assistant", "content": c["response"]},
            ],
            "temperature": 0.0,
            "max_tokens": PC_MAX_TOKENS,
            "logprobs": True,
            "top_logprobs": 20,
        }
        req = urllib.request.Request(
            PC_BASE + "/v1/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with op.open(req, timeout=180) as resp:
            raw = resp.read().decode("utf-8")
        j = json.loads(raw)
        first = j["choices"][0]["message"]["content"][:40].replace("\n", "\\n")
        log(f"  [{c['name']}] 首段={first!r} tokens={j.get('usage', {}).get('prompt_tokens')}")
        bodies.append(raw)

    with open(os.path.join(PARITY_DIR, "pc.json"), "w", encoding="utf-8") as f:
        json.dump(bodies, f, ensure_ascii=False)
    log(f"  已写 pc.json（{len(bodies)} 条）")


def adb(*args, timeout=120):
    return sh([ADB] + list(args), timeout=timeout)


def sh_dev(cmd, timeout=1800):
    """在设备上执行 shell 命令。端上推理慢，超时给足。"""
    return sh([ADB, "shell", cmd], timeout=timeout)


def ensure_device_ready():
    run_step("[4/5] 推 prompts 到真机并跑 xguard_cli")
    r = adb("devices")
    log("  " + r.stdout.strip().replace("\n", "\n  "))
    if "\tdevice" not in r.stdout:
        raise SystemExit("没有在线设备（adb devices 里看不到 device）")

    abi = sh_dev("getprop ro.product.cpu.abi").stdout.strip()
    log(f"  设备 ABI: {abi}")
    if abi != "arm64-v8a":
        raise SystemExit(f"设备 ABI 是 {abi}，而 libxguard/xguard_cli 只编了 arm64-v8a")

    if not os.path.exists(CLI_LOCAL):
        raise SystemExit(f"缺少 {CLI_LOCAL}，先跑 tools/build-native.py")

    # 已经推过的就不重复推（460 MB 的模型每次重推纯属浪费）
    listing = sh_dev(f"ls -l {DEV_DIR}").stdout
    if "xguard_cli" not in listing:
        log("  推送 xguard_cli")
        log("  " + adb("push", CLI_LOCAL, DEV_CLI).stdout.strip())
    else:
        log("  xguard_cli 已在设备上")
    sh_dev(f"chmod 755 {DEV_CLI}")

    if "xguard.gguf" not in listing:
        log("  推送模型（约 462 MB）")
        r = adb("push", MODEL_LOCAL, DEV_MODEL, timeout=1800)
        log("  " + r.stdout.strip())
    else:
        log("  模型已在设备上")

    log("  推送 prompts.bin")
    log("  " + adb("push", os.path.join(PARITY_DIR, "prompts.bin"), DEV_PROMPTS).stdout.strip())

    return listing


def step_run_device():
    cores = sh_dev("nproc").stdout.strip() or "?"
    log(f"  设备核心数: {cores}，推理线程 {DEV_THREADS}")
    cmd = (f"{DEV_CLI} {DEV_MODEL} {DEV_PROMPTS} "
           f"--topk 20 --maxtokens {DEV_MAX_TOKENS} --ctx 2048 --threads {DEV_THREADS}")
    log(f"  $ adb shell {cmd}")
    t0 = time.time()
    r = sh_dev(cmd, timeout=2400)
    dur = time.time() - t0

    stderr_tail = "\n".join(r.stderr.strip().splitlines()[-6:])
    log(f"  stderr 尾部：\n    {stderr_tail.replace(chr(10), chr(10) + '    ')}")

    lines = [ln for ln in r.stdout.splitlines() if ln.strip().startswith("{")]
    log(f"  用时 {dur:.0f}s，拿到 {len(lines)} 条 JSON")
    if not lines:
        raise SystemExit(
            f"端上没输出任何 JSON。\nstdout: {r.stdout[:1000]}\nstderr: {r.stderr[:2000]}"
        )
    with open(os.path.join(PARITY_DIR, "device.jsonl"), "w", encoding="utf-8") as f:
        for ln in lines:
            f.write(ln.strip() + "\n")
    log(f"  已写 device.jsonl（{len(lines)} 条）")


def step_compare():
    run_step("[5/5] 用生产解析代码比对两端判定")
    r = sh([PY_MANAGED, "-u", os.path.join(PROJECT, "tools", "run-unit-tests.py"),
            "-Dxguard.parity.dir=" + PARITY_DIR,
            "com.xieguojian.aiseclab.GuardParityCompareTest"], timeout=600)
    log(r.stdout)
    if r.stderr.strip():
        log("--- stderr ---")
        log(r.stderr.strip()[-2000:])
    return r.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-compile", action="store_true", help="跳过编译（测试代码没改时用）")
    ap.add_argument("--skip-dump", action="store_true", help="跳过渲染（prompts.bin 已是最新时用）")
    ap.add_argument("--device-only", action="store_true", help="跳过 PC 侧 HTTP 采集")
    ap.add_argument("--no-device", action="store_true", help="只做 PC 侧采集，不碰真机")
    args = ap.parse_args()

    os.makedirs(PARITY_DIR, exist_ok=True)
    log(f"验收工作目录：{PARITY_DIR}")

    if not args.skip_compile:
        step_compile()
    if not args.skip_dump:
        step_dump()

    if not args.device_only:
        if not pc_alive():
            raise SystemExit(
                "PC 侧 llama-server 未就绪（127.0.0.1:8000）。请先启动：\n"
                f"  {PY_VENV} -m llama_cpp.server --model {MODEL_LOCAL} \\\n"
                "      --model_alias xguard-0.6b --n_ctx 2048 --n_threads 8 "
                "--host 127.0.0.1 --port 8000"
            )
        step_pc_collect()

    if not args.no_device:
        ensure_device_ready()
        step_run_device()

    return step_compare()


if __name__ == "__main__":
    sys.exit(main())
