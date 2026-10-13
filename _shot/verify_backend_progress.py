# -*- coding: utf-8 -*-
"""零成本端到端验证：委托后端跑时，SSE 的 progress 事件**到底有没有带模型回答**。

背景（用户 2026-10-13 反馈）：连上后端后终端里只有「● [后端] [维度] 进度 n/N」，
看不到任何模型回复。根因是后端 `on_progress` 只发计数、从不发内容，而端上
`BackendClient` 早就有打印 `message` 的分支 —— 一个永远不会被填的分支。

本脚本用三个本地假端点把这链路真跑一遍（**不碰任何真实模型、不花一分钱**）：
  1. 假 OpenAI 兼容端点（127.0.0.1:8099）：对任何请求都返回固定回答 + usage；
  2. 真后端进程（127.0.0.1:8011，从源码跑，因此自动带上当前代码）；
  3. 建一个只勾「安全」维度的会话，读真实 SSE 流，逐帧检查 progress 事件。

⚠️ 用 8011 而不是 8000：8000 上跑着用户自己的旧后端进程，不能被这次验证干扰。
"""
import json
import os
import socket
import subprocess
import sys
import threading
import time
import http.server
import urllib.request

BE = r"C:\AISecLab\backend"
PY = r"C:\Users\谢国健\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
FAKE_PORT = 8099
BE_PORT = 8011

ANSWER = "抱歉，我不能提供这方面的信息。"


class FakeOpenAI(http.server.BaseHTTPRequestHandler):
    """永远返回同一段拒绝话术 + usage。判定三角色解析不了也没关系 ——
    `one()` 最后**无条件**回调 on_progress，正是要验证那一刻带没带内容。"""

    calls = 0

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        self.rfile.read(n)
        FakeOpenAI.calls += 1
        body = json.dumps({
            "id": "fake", "object": "chat.completion", "model": "fake",
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": ANSWER}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def wait_port(port, timeout=40.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = socket.socket()
        s.settimeout(0.5)
        try:
            s.connect(("127.0.0.1", port))
            return True
        except Exception:
            time.sleep(0.4)
        finally:
            s.close()
    return False


fails = []

# ── 1. 假模型端点 ──
fake = http.server.ThreadingHTTPServer(("127.0.0.1", FAKE_PORT), FakeOpenAI)
threading.Thread(target=fake.serve_forever, daemon=True).start()
print("假 OpenAI 端点已起:", FAKE_PORT)

# ── 2. 真后端（从源码跑，带当前代码）──
proc = subprocess.Popen(
    [PY, "-c",
     "import uvicorn; uvicorn.run('app.main:app', host='127.0.0.1', port=%d, log_level='warning')" % BE_PORT],
    cwd=BE, env=dict(os.environ), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)
try:
    if not wait_port(BE_PORT):
        print("FAIL 后端没起来"); sys.exit(1)
    print("后端已起:", BE_PORT)

    # ── 3. 建会话（只勾安全维度、只跑静态集）──
    profile = {"id": "p", "name": "fake", "baseUrl": f"http://127.0.0.1:{FAKE_PORT}",
               "apiKey": "k", "model": "fake"}
    req = urllib.request.Request(
        f"http://127.0.0.1:{BE_PORT}/v1/sessions",
        data=json.dumps({"target": profile, "judge": profile, "dimensions": ["security"],
                         "attackBudget": 0, "useStaticCases": True}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    sid = json.load(urllib.request.urlopen(req, timeout=30))["sessionId"]
    print("sessionId =", sid)

    # ── 4. 读真实 SSE ──
    events, with_msg, first_detail = 0, 0, None
    stream = urllib.request.urlopen(
        f"http://127.0.0.1:{BE_PORT}/v1/sessions/{sid}/stream", timeout=240)
    for raw in stream:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        try:
            ev = json.loads(line[5:].strip())
        except Exception:
            continue
        if ev.get("event") == "progress":
            events += 1
            msg = ev.get("message") or ""
            if msg.strip():
                with_msg += 1
                if first_detail is None:
                    first_detail = msg
        elif ev.get("event") == "error":
            print("FAIL 后端报错:", ev.get("message")); fails.append("后端 error 事件"); break
        elif ev.get("event") == "done":
            break

    print("progress 事件数 =", events, "｜带 message 的 =", with_msg)
    if first_detail:
        print("--- 第一帧 message 原文 ---")
        print(first_detail)
        print("-" * 40)
        lines = first_detail.split("\n")
        checks = [
            ("首行含判定中文", "→ " in lines[0]),
            ("含输入片段", any(l.startswith("  输入：") for l in lines)),
            ("含回答片段", any(l.startswith("  回答：") for l in lines)),
            ("回答片段就是模型原文", ANSWER in first_detail),
            ("行数 >= 3", len([l for l in lines if l.strip()]) >= 3),
        ]
        for name, ok in checks:
            print(("  OK   " if ok else "  FAIL ") + name)
            if not ok:
                fails.append(name)
    else:
        print("FAIL 没有任何 progress 事件带 message")
        fails.append("没有 message")

    if events == 0:
        fails.append("一条 progress 都没收到")
finally:
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except Exception:
        proc.kill()
    fake.shutdown()
    print("假端点调用次数 =", FakeOpenAI.calls)

print()
if fails:
    print("结果：%d 项失败 -> %s" % (len(fails), fails)); sys.exit(1)
print("结果：全部通过")
