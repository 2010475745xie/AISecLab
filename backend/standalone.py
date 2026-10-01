#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AISecLab 后端「免安装」启动器（被 PyInstaller 冻结成 exe 后使用）。

与 deploy.py 的区别：
  - 不依赖系统已装 Python（exe 自带解释器与依赖）
  - 显式 `from app.main import app` 让 PyInstaller 能静态收集 app 包
  - 仍会在首次运行时用 .env.example 初始化 .env，并打印手机要填的地址
"""

import os
import sys
import socket
import shutil
import time
import urllib.request

# 冻结后 sys.executable 是 exe 自身所在目录；源码运行时是 python.exe 目录
HERE = os.path.dirname(os.path.abspath(sys.executable))

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


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    # 1) .env 初始化
    env_path = os.path.join(HERE, ".env")
    if not os.path.exists(env_path):
        example = os.path.join(HERE, ".env.example")
        if os.path.exists(example):
            shutil.copy(example, env_path)
            print("[!] 已生成 .env（基于 .env.example）。若要用服务端裁判/攻击模型，")
            print("    请编辑 .env 填入 JUDGE_BASE_URL / JUDGE_MODEL / JUDGE_API_KEY。")
            print("    不填也能启动，但运行测试时服务端裁判会返回 503（可在 App 请求体自带 judge 规避）。")

    cfg = load_env(env_path)
    port = int(cfg.get("PORT") or os.environ.get("PORT") or "8000")

    import uvicorn
    from app.main import app  # 静态导入，确保整个 app 包被打进 exe

    print("\n=== AISecLab 后端启动中 (port=%d) ===" % port)

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
