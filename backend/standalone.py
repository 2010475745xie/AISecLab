#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AISecLab 后端「免安装」启动器（被 PyInstaller 冻结成 exe 后使用）。

与 deploy.py 的区别：
  - 不依赖系统已装 Python（exe 自带解释器与依赖）
  - 显式 `from app.main import app` 让 PyInstaller 能静态收集 app 包
  - 首次运行用 .env.example 初始化 .env，并打印手机要填的地址
"""

import os
import re
import sys
import socket
import shutil
import subprocess
import time
import urllib.request

# 让 Windows cmd 中文不乱码：切到 UTF-8 代码页 + 重配 stdout/stderr
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass
if os.name == "nt":
    try:
        os.system("chcp 65001 >nul 2>&1")
    except Exception:
        pass


def bundle_root():
    """配置文件（.env）应放哪。

    优先找 run.bat 所在目录（= zip 解压根，用户一眼能看到的地方）；
    其次找含 .env.example 的目录；最后退回 exe 自身目录。
    """
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    parent = os.path.dirname(exe_dir)
    cwd = os.getcwd()
    # 1) run.bat 所在目录
    for d in (exe_dir, parent, cwd):
        if d and os.path.isfile(os.path.join(d, "run.bat")):
            return d
    # 2) 含 .env.example 的目录
    for d in (cwd, exe_dir, parent):
        if d and os.path.isfile(os.path.join(d, ".env.example")):
            return d
    # 3) 兜底
    return exe_dir


ROOT = bundle_root()


def load_env(path):
    env = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip()
                if len(v) >= 2 and v[0] in "\"'":
                    v = v[1:-1]
                env[k] = v
    except FileNotFoundError:
        pass
    return env


def _is_private(ip: str) -> bool:
    """RFC1918 私网地址判定：10/8、172.16/12、192.168/16。"""
    try:
        parts = [int(x) for x in ip.split(".")]
    except (ValueError, AttributeError):
        return False
    if len(parts) != 4:
        return False
    a, b = parts[0], parts[1]
    if a == 10:
        return True
    if a == 172 and 16 <= b <= 31:
        return True
    if a == 192 and b == 168:
        return True
    return False


def lan_ips() -> list:
    """枚举本机所有可用于手机直连的私网 IPv4（去重、私网优先）。

    为什么不能只用「UDP connect 8.8.8.8 取源 IP」：那是**默认路由**出口，
    一旦电脑开着 VPN / 代理 / 隧道，拿到的是隧道网卡地址（如 198.18.x.x），
    手机根本不在那个网段里，照抄横幅就必然连不上（2026-10-07 真实踩坑）。
    所以这里改为「先用默认路由，若不是私网则枚举网卡补上真正的 LAN 地址」。
    """
    ips = []

    # 1) 默认路由出口（没开 VPN 时它就是正确 LAN IP）
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except Exception:
        pass

    # 2) 枚举所有网卡（Windows 用 PowerShell，速度够快且不依赖额外库）
    if os.name == "nt":
        try:
            ps = (
                "Get-NetIPAddress -AddressFamily IPv4 | "
                "Where-Object {$_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*'} | "
                "Select-Object -ExpandProperty IPAddress"
            )
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                capture_output=True, timeout=12,
            ).stdout.decode("utf-8", errors="ignore")
            for line in out.splitlines():
                line = line.strip()
                if line and line not in ips:
                    ips.append(line)
        except Exception:
            pass
    else:  # 兜底：POSIX 用 ifconfig / ip
        for cmd in (["ifconfig", "-a"], ["ip", "-4", "addr"]):
            try:
                out = subprocess.run(cmd, capture_output=True, timeout=8).stdout.decode(
                    "utf-8", errors="ignore")
                for m in re.finditer(r"inet\s+(?:addr:)?(\d+\.\d+\.\d+\.\d+)", out):
                    ip = m.group(1)
                    if ip not in ips:
                        ips.append(ip)
                if ips:
                    break
            except Exception:
                continue

    # 3) 私网地址排前面（真正能给手机用），公网/VPN 隧道地址排后
    priv = [ip for ip in ips if _is_private(ip)]
    other = [ip for ip in ips if not _is_private(ip)]
    return priv + other


def lan_ip() -> str:
    """给手机填的那个地址：优先第一个私网地址（VPN 开着也不会吐隧道 IP）。"""
    ips = lan_ips()
    return ips[0] if ips else "127.0.0.1"


# .env.example 里未填的占位值特征。直接照抄 .env.example 就启动时，
# 不能把这些占位当真实配置注入环境（否则会得到一个指向假地址的裁判/护栏，报错更难懂）。
_PLACEHOLDER_MARKERS = ("你的", "xxxx", "填写", "example", "changeme")


def _looks_placeholder(v: str) -> bool:
    low = v.lower()
    return any(m in low or m in v for m in _PLACEHOLDER_MARKERS)


def main():
    # 1) .env 初始化（放在 ROOT，与 run.bat 同级）
    env_path = os.path.join(ROOT, ".env")
    if not os.path.exists(env_path):
        for cand in (os.path.join(ROOT, ".env.example"),
                     os.path.join(os.path.dirname(os.path.abspath(sys.executable)), ".env.example")):
            if os.path.exists(cand):
                shutil.copy(cand, env_path)
                print("[!] 已生成 .env（基于 .env.example）。若要用服务端裁判/攻击模型，")
                print("    请编辑 .env 填入 JUDGE_BASE_URL / JUDGE_MODEL / JUDGE_API_KEY。")
                print("    不填也能启动，但运行测试时服务端裁判会返回 503（可在 App 请求体自带 judge 规避）。")
                break

    cfg = load_env(env_path)
    # 把 .env 的值注入进程环境：main.py 在**请求时**读 os.environ 拿 JUDGE_* / GUARD_*。
    # 之前这里只取出 PORT，其余键全部丢弃 —— 导致 exe 路径下 .env 里填的裁判/护栏配置
    # **静默失效**（App 请求体自带 judge 所以没暴露，但服务端裁判与护栏会一直"没配"）。
    for k, v in cfg.items():
        if k == "PORT":
            continue
        if v and not _looks_placeholder(v):
            os.environ[k] = v
    port = int(cfg.get("PORT") or os.environ.get("PORT") or "8000")

    import uvicorn
    from app.main import app  # 静态导入，确保整个 app 包被打进 exe

    print("\n=== AISecLab 后端启动中 (port=%d, 配置目录=%s) ===" % (port, ROOT))
    _judge_ok = bool(os.environ.get("JUDGE_BASE_URL") and os.environ.get("JUDGE_MODEL"))
    _guard_ok = bool(os.environ.get("GUARD_BASE_URL") and os.environ.get("GUARD_MODEL"))
    print("  服务端裁判：%s" % ("已配置" if _judge_ok else "未配置（依赖 App 请求体自带 judge）"))
    print("  护栏模型  ：%s" % ("已配置 → 判定走「护栏优先」" if _guard_ok else "未配置 → 判定走纯规则层"))

    # 2) 后台探测 /health（uvicorn.run 是阻塞的，这里用线程轮询并打印地址）
    def probe():
        url = "http://127.0.0.1:%d/health" % port
        for _ in range(40):
            try:
                urllib.request.urlopen(url, timeout=2)
                ip = lan_ip()
                print("\n" + "=" * 60)
                print("  AISecLab 后端已启动")
                print("  请把下面地址填进手机 App「设置 -> 测试后端」：")
                print("    本机 / 模拟器 :  http://localhost:%d" % port)
                print("    手机同 Wi-Fi   :  http://%s:%d   <-- 一般用这个" % (ip, port))
                print("    健康检查       :  %s/health" % ("http://127.0.0.1:%d" % port))
                # 多个网卡 / VPN 场景下把候选都列出来，避免用户照抄到隧道地址
                ips = lan_ips()
                if len(ips) > 1:
                    print("-" * 60)
                    print("  其他网卡地址（填错地址是最常见的连不上原因）：")
                    for extra in ips[1:]:
                        tag = "私网" if _is_private(extra) else "非私网(可能是 VPN/虚拟网卡，手机多半连不上)"
                        print("    http://%s:%d   [%s]" % (extra, port, tag))
                    print("  提示：手机和电脑要在同一个 Wi-Fi/局域网；")
                    print("        若电脑开着 VPN，优先用上面的私网地址，并确认 VPN 没有阻断局域网入站。")
                print("=" * 60)
                print("  按 Ctrl+C 停止服务。\n")
                return
            except Exception:
                time.sleep(1)
        print("[!] 后端 40s 内未就绪，请检查上方报错（多半是端口被占用或依赖缺失）。")

    import threading
    threading.Thread(target=probe, daemon=True).start()

    try:
        uvicorn.run(app, host="0.0.0.0", port=port)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
