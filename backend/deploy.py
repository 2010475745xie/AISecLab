#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AISecLab 后端一键部署脚本（Windows / macOS / Linux 通用）

做了什么：
  1) 在 backend 目录里建虚拟环境 venv（已存在则复用）
  2) 用 venv 安装 requirements.txt
  3) 没有 .env 时复制 .env.example -> .env（并提示你填裁判模型配置）
  4) 把 .env 里的 JUDGE_* / API_TOKEN / SAMPLES_PER_CASE / PORT 注入运行环境
  5) 启动 uvicorn(app.main:app, host=0.0.0.0, port=PORT) 并等待 /health 就绪
  6) 打印手机 App「测试后端」要填的地址（本机 + 同 Wi-Fi 局域网）

停止：Ctrl+C
"""

import os
import re
import sys
import socket
import shutil
import time
import subprocess
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

# ── 0) 解释器自检 ──────────────────────────────────────────────────────────
# 本项目最低 Python 3.9。注：Rocky / RHEL 9 的系统 python3 正好是 3.9，能直接跑；
# 想和 Docker 镜像（3.11）对齐，用 start.sh --local 让它挑一个新版解释器，
# 或自己指定 AISECLAB_PYTHON=/usr/bin/python3.11。
if sys.version_info < (3, 9):
    print("[!] 需要 Python 3.9 或更高，当前是 %d.%d。" % sys.version_info[:2])
    print("    Rocky / RHEL 系装一个 AppStream 里的新版解释器（不动系统 python3）：")
    print("        sudo dnf install -y python3.11 python3.11-pip")
    print("        AISECLAB_PYTHON=/usr/bin/python3.11 python3 deploy.py")
    print("    或者干脆走 Docker：./start.sh --docker")
    sys.exit(1)

# 建 venv 用哪个解释器：优先 start.sh 挑好的（AISECLAB_PYTHON），否则就用当前这个
BASE_PY = os.environ.get("AISECLAB_PYTHON") or sys.executable


def step(msg):
    print("\n=== " + msg + " ===")


def run(cmd, **kw):
    print(">>> " + " ".join(cmd))
    return subprocess.run(cmd, cwd=HERE, **kw)


# ── 1) 虚拟环境 ────────────────────────────────────────────────
VENV = os.path.join(HERE, "venv")
if os.name == "nt":
    PY = os.path.join(VENV, "Scripts", "python.exe")
else:
    PY = os.path.join(VENV, "bin", "python")
# 一律用「venv 自己的 python -m pip」，不直接调 pip / pip.exe：
# 一是不依赖 Scripts|bin 下那个启动器（Linux 上它的 shebang 指向建 venv 时的绝对路径，
# 目录一搬就废），二是 Windows 上 pip.exe 在某些代理/长路径环境下会自己起不来。
PIP = [PY, "-m", "pip"]

if not os.path.isfile(PY):
    step("创建虚拟环境")
    rc = run([BASE_PY, "-m", "venv", VENV]).returncode
    if rc != 0 or not os.path.isfile(PY):
        print("\n[!] 创建虚拟环境失败。")
        if os.name != "nt":
            print("    Rocky / RHEL / Fedora 系最常见的原因：ensurepip 缺失 —— 先执行：")
            print("        sudo dnf install -y python3-pip")
            print("    Rocky 9 也可直接装一个新版解释器（不会动系统 python3）：")
            print("        sudo dnf install -y python3.11 python3.11-pip")
            print("        AISECLAB_PYTHON=/usr/bin/python3.11 ./start.sh --local")
            print("    Debian / Ubuntu 系：sudo apt update && sudo apt install -y python3-venv")
            print("    或者干脆改用 Docker 部署（见 README 第 0 节）。")
        else:
            print("    Windows 常见原因：Python 未加入 PATH，或安装时未勾选 pip / venv 组件。")
        sys.exit(1)

# ── 2) 安装依赖 ────────────────────────────────────────────────
step("安装依赖")
try:
    subprocess.run(PIP + ["show", "fastapi"], check=True, capture_output=True)
    print("依赖已安装，跳过。")
except subprocess.CalledProcessError:
    req = os.path.join(HERE, "requirements.txt")
    if run(PIP + ["install", "-r", req, "--upgrade"]).returncode != 0:
        print("\n[!] 依赖安装失败。若在国内网络，先试镜像源：")
        print("    {} -m pip install -r {} -i https://pypi.tuna.tsinghua.edu.cn/simple".format(PY, req))
        sys.exit(1)

# ── 3) .env ───────────────────────────────────────────────────
if not os.path.exists(os.path.join(HERE, ".env")):
    step("初始化 .env")
    shutil.copy(os.path.join(HERE, ".env.example"), os.path.join(HERE, ".env"))
    print("[!] 已生成 .env（基于 .env.example）。若要用服务端裁判/攻击模型，")
    print("    请编辑 .env 填入 JUDGE_BASE_URL / JUDGE_MODEL / JUDGE_API_KEY。")
    print("    不填也能启动，但运行测试时服务端裁判会返回 503（可在 App 请求体自带 judge 规避）。")

# ── 4) 读取 .env 注入运行环境 ──────────────────────────────────
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


cfg = load_env(os.path.join(HERE, ".env"))
port = cfg.get("PORT") or os.environ.get("PORT") or "8000"

# ── 5) 启动 ────────────────────────────────────────────────────
step("启动后端 (uvicorn host=0.0.0.0 port=%s)" % port)
proc = subprocess.Popen(
    [PY, "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", str(port)],
    cwd=HERE,
    env={**os.environ, **cfg},
)

# ── 6) 等待健康检查 ────────────────────────────────────────────
url = "http://127.0.0.1:%s" % port
step("等待 /health 就绪")
ok = False
for _ in range(40):
    try:
        urllib.request.urlopen(url + "/health", timeout=2)
        ok = True
        break
    except Exception:
        time.sleep(1)
if not ok:
    print("[!] 后端 40s 内未就绪，请查看上方报错。")
    proc.terminate()
    sys.exit(1)


# ── 7) 打印连接地址 ────────────────────────────────────────────
def _is_private(ip: str) -> bool:
    """RFC1918 私网地址判定：10/8、172.16/12、192.168/16。"""
    try:
        parts = [int(x) for x in ip.split(".")]
    except (ValueError, AttributeError):
        return False
    if len(parts) != 4:
        return False
    a, b = parts[0], parts[1]
    return (a == 10) or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168)


def lan_ips() -> list:
    """枚举所有可用于手机直连的 IPv4（去重、私网优先）。

    不能只用「UDP connect 8.8.8.8 取源 IP」：那是**默认路由**出口，电脑一开
    VPN / 代理 / 隧道，拿到的是隧道网卡地址（如 198.18.x.x），手机不在那个网段，
    照抄横幅必然连不上（2026-10-07 真实踩坑）。与 standalone.py 保持同一实现。
    """
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except Exception:
        pass

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
    else:
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

    priv = [ip for ip in ips if _is_private(ip)]
    other = [ip for ip in ips if not _is_private(ip)]
    return priv + other


ips = lan_ips()
ip = ips[0] if ips else "127.0.0.1"
print("\n" + "=" * 60)
print("  AISecLab 后端已启动")
print("  请把下面地址填进手机 App「设置 -> 测试后端」：")
print("    本机 / 模拟器 :  http://localhost:%s" % port)
print("    手机同 Wi-Fi   :  http://%s:%s   <-- 一般用这个" % (ip, port))
print("    健康检查       :  %s/health" % url)
if len(ips) > 1:
    print("-" * 60)
    print("  其他网卡地址（填错地址是最常见的连不上原因）：")
    for extra in ips[1:]:
        tag = "私网" if _is_private(extra) else "非私网(可能是 VPN/虚拟网卡，手机多半连不上)"
        print("    http://%s:%s   [%s]" % (extra, port, tag))
    print("  提示：手机和电脑要在同一个 Wi-Fi/局域网；")
    print("        若电脑开着 VPN，优先用上面的私网地址，并确认 VPN 没有阻断局域网入站。")
print("=" * 60)
print("  停止：Ctrl+C（或在另一个终端执行 ./stop.sh）")
print("  提示：服务器 / 比赛评审环境建议走 Docker —— ./start.sh --docker")
print("=" * 60 + "\n")

try:
    proc.wait()
except KeyboardInterrupt:
    proc.terminate()
