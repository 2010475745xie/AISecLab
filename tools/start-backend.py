#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""起 / 停 AISecLab 后端（`backend/standalone.py`）。

## 为什么要有它

手敲启动命令每次都会漏三件事，而且**漏了不一定报错**（最难查的那种）：

  1. **必须用 venv 的解释器**：base python（3.13.12）没装 uvicorn / fastapi，
     用它直接 `ImportError: No module named uvicorn`。
     venv = `~/.workbuddy/binaries/python/envs/default`
  2. **必须剥掉沙箱代理**：本机 `HTTP_PROXY=127.0.0.1:12331`，而后端用 httpx
     （`trust_env=True`）连裁判模型 → 请求被代理拦成 502。
     ⚠️ 同一个代理还会把"探测 127.0.0.1:8000"也拦掉，所以**健康探测也得走剥过代理的 env**。
  3. **cwd 必须是 `backend/dist`**：`standalone.py` 的 `bundle_root()` 按 cwd 找
     `run.bat` / `.env.example` 来定位 `.env`。cwd 不对就去读别的目录的 .env（或读不到），
     表现为"改了 .env 不生效"。

## 用法

    python tools/start-backend.py            # 起服务；等 /health 通了再返回（进程留后台）
    python tools/start-backend.py --status   # 看现在有没有在跑
    python tools/start-backend.py --stop     # 停掉

## 沙箱

本机沙箱会**回收整条进程树**（`DETACHED_PROCESS` 也救不了），所以本脚本必须在
**沙箱外**跑：调用方需要 `dangerouslyDisableSandbox=true`。
`--status` / `--stop` 只是查/杀进程，沙箱内也能用。
"""

import os
import re
import subprocess
import sys
import time
import urllib.request

PROJECT = r"C:\AISecLab"
DIST = os.path.join(PROJECT, "backend", "dist")
LAUNCHER = os.path.join(PROJECT, "backend", "standalone.py")
VENV_PY = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
LOG = r"D:\aiseclab-backend.log"
PIDFILE = os.path.join(DIST, ".aiseclab-backend.pid")

# 与 backend/app/llm_client.py 的 _PROXY_ENV_KEYS **同源**：那边靠这份名单判断"要不要走代理"，
# 少剥一个就会让后端悄悄走代理（实测漏掉 AISECLAB_PROXY 时启动横幅会打「代理 127.0.0.1:2898」，
# 而它连的是沙箱代理，不是用户自己要的出口）。
PROXY_KEYS = (
    "AISECLAB_PROXY",
    "HTTPS_PROXY", "https_proxy",
    "HTTP_PROXY", "http_proxy",
    "ALL_PROXY", "all_proxy",
)


def clean_env():
    """剥掉代理 —— 见文件头第 2 条。父进程与子进程必须用同一份。"""
    env = dict(os.environ)
    for k in PROXY_KEYS:
        env.pop(k, None)
    return env


def read_port():
    """端口从 dist/.env 读（standalone.py 也是这么做的），兜底 8000。"""
    try:
        for line in open(os.path.join(DIST, ".env"), encoding="utf-8"):
            t = line.strip()
            if t.startswith("PORT="):
                return int(t.split("=", 1)[1].strip() or 8000)
    except Exception:
        pass
    return 8000


def health_ok(port, timeout=1.0):
    """探测 /health。⚠️ 必须绕代理（沙箱代理会把本机 127.0.0.1:8000 也拦成 502）。"""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"http://127.0.0.1:{port}/health", timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False


def pid_from_file():
    try:
        return int(open(PIDFILE, encoding="utf-8").read().strip())
    except Exception:
        return None


def pid_alive(pid):
    if not pid:
        return False
    r = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    # tasklist 查不到时输出的是 "信息: 没有运行的任务..."（或英文版），不会含该 PID
    return str(pid) in (r.stdout or "")


def do_status():
    pid = pid_from_file()
    port = read_port()
    running = health_ok(port)
    print(f"PID 文件 : {PIDFILE} -> {pid if pid else '（无）'}")
    print(f"进程存活 : {'是' if pid_alive(pid) else '否'}")
    print(f"/health  : {'通  http://127.0.0.1:%d/health' % port if running else '不通'}")
    return 0 if running else 1


def do_stop():
    pid = pid_from_file()
    if not pid_alive(pid):
        print(f"没有在跑（PID 文件：{pid if pid else '无'}）")
        if os.path.exists(PIDFILE):
            os.remove(PIDFILE)
        return 0
    # /T 连子进程一起收掉：uvicorn 的 reload / worker 都是它的子进程
    # ⚠️ `errors="replace"` 不能省：中文 Windows 上 taskkill 的提示是 **GBK 字节**，
    #    用 utf-8 严格解码会在**进程已经杀掉、功能已经生效**之后抛
    #    `UnicodeDecodeError: 'utf-8' codec can't decode byte 0xb3` ——
    #    于是「停止」看上去失败了，其实已经停了（2026-10-16 实测踩到）。
    subprocess.run(["taskkill", "/PID", str(pid), "/F", "/T"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
    if os.path.exists(PIDFILE):
        os.remove(PIDFILE)
    print(f"已停止 PID {pid}")
    return 0


def do_start():
    port = read_port()

    if health_ok(port):
        print(f"已经在跑了：http://127.0.0.1:{port}（无需重复启动）")
        return 0

    if not os.path.exists(VENV_PY):
        print(f"❌ 找不到 venv 解释器：{VENV_PY}")
        return 1
    if not os.path.exists(LAUNCHER):
        print(f"❌ 找不到启动器：{LAUNCHER}")
        return 1

    env = clean_env()
    env["PYTHONUNBUFFERED"] = "1"
    log = open(LOG, "w", encoding="utf-8", errors="replace")
    print("$ %s -u %s   (cwd=%s)" % (VENV_PY, LAUNCHER, DIST))
    p = subprocess.Popen(
        [VENV_PY, "-u", LAUNCHER],
        cwd=DIST,                  # ← 见文件头第 3 条
        env=env,                   # ← 见文件头第 2 条
        stdout=log, stderr=subprocess.STDOUT,
        creationflags=subprocess.DETACHED_PROCESS,
    )
    with open(PIDFILE, "w", encoding="utf-8") as f:
        f.write(str(p.pid))
    print(f"已拉起 PID {p.pid}，日志 {LOG}")

    # 等 /health 通（uvicorn 冷启动通常 2~5 秒；给 30 秒上限，
    # 首次跑还要装依赖 / 初始化，慢一点是正常的）
    for i in range(60):
        time.sleep(0.5)
        if p.poll() is not None:
            print(f"❌ 进程已退出（exit={p.returncode}），日志末尾：")
            print("\n".join(open(LOG, encoding="utf-8", errors="replace").read().splitlines()[-25:]))
            return 1
        if health_ok(port):
            print(f"✅ /health 已通：http://127.0.0.1:{port}")
            print(f"   手机 / 局域网：http://<本机局域网 IP>:{port}")
            return 0

    print(f"⚠️ 等了 30 秒 /health 仍不通（进程还在）。日志末尾：")
    print("\n".join(open(LOG, encoding="utf-8", errors="replace").read().splitlines()[-25:]))
    return 1


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "--status":
        return do_status()
    if arg == "--stop":
        return do_stop()
    if arg == "--inline":
        # 给「受管理的后台任务」用的模式（Bash 工具的 run_in_background）：
        # **同进程**把 standalone.py 跑起来（runpy），进程从头到尾是同一个 PID。
        #
        # 为什么不能像 do_start() 那样 Popen(DETACHED_PROCESS)：
        #   本机**会回收整条进程树**（2026-10-07 / 2026-10-15 两次实测）——
        #   脚本一返回，uvicorn 就没了：netstat 查不到 :8000、tasklist 查不到 PID，
        #   而它自己的日志里 /health 明明通过、甚至已经处理过真实请求。
        #
        # ⚠️ 也不能用 os.execv「自己变成 uvicorn」：**Windows 上的 exec 是
        #   CreateProcess + 退出原进程，PID 是会变的**（实测 18104 → 28008）。
        #   受管理的那个 PID 一退出，工具就判定任务 completed 并把整棵树收掉 ——
        #   服务只活了 5 秒。runpy 才是真正的同进程。
        env = clean_env()
        env["PYTHONUNBUFFERED"] = "1"
        os.environ.clear()
        os.environ.update(env)          # 同进程执行，先把自己环境里的代理剥掉
        os.chdir(DIST)                  # ← 见文件头第 3 条
        with open(PIDFILE, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
        sys.stderr.write(f"[start-backend] inline, cwd={DIST}, pid={os.getpid()}\n")
        sys.stderr.flush()
        import runpy
        # ⚠️ standalone.py 里有 `from app.main import app`，靠的是"脚本所在目录进 sys.path[0]"。
        # 直接跑 `python ../standalone.py` 时 Python 自动这么干，但 runpy 不会 ——
        # 换成 runpy 后 sys.path[0] 是 tools/，于是 `ModuleNotFoundError: No module named 'app'`。
        # 所以得手动把 backend/ 放进去。
        sys.path.insert(0, os.path.dirname(LAUNCHER))
        runpy.run_path(LAUNCHER, run_name="__main__")   # uvicorn 在这行阻塞
        return 0
    if arg:
        print(__doc__)
        return 2
    return do_start()


if __name__ == "__main__":
    sys.exit(main())
