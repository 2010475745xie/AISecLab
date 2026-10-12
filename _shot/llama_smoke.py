"""llama-server 冒烟：起一个 llama-server，等它就绪，打一发 chat/completions，再关掉。

用途：在拿到真正的对话权重之前，先用本机现成的任意 GGUF（如 XGuard 分类器）
把「二进制能跑 / 端口能绑 / OpenAI 形状正确」这条链路验掉。

用法：
    python _shot/llama_smoke.py <gguf路径> [端口]
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BIN = r"C:\third_party\llama.cpp-bin"
EXE = os.path.join(BIN, "llama-server.exe")


def main() -> int:
    gguf = sys.argv[1] if len(sys.argv) > 1 else r"D:\models\YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 18080
    if not os.path.exists(gguf):
        print("找不到权重:", gguf)
        return 2

    log = open(os.path.join(BIN, "smoke-server.log"), "wb")
    proc = subprocess.Popen(
        [EXE, "-m", gguf, "--host", "127.0.0.1", "--port", str(port),
         "-c", "2048", "-t", "8"],
        cwd=BIN, stdout=log, stderr=subprocess.STDOUT,
    )
    print("llama-server pid", proc.pid, "加载中…")
    try:
        # 1) 等就绪
        ready = False
        t0 = time.time()
        while time.time() - t0 < 120:
            if proc.poll() is not None:
                print("进程提前退出，rc=", proc.returncode)
                return 3
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3) as r:
                    if r.status == 200:
                        ready = True
                        break
            except Exception:
                pass
            time.sleep(1)
        print("就绪耗时 %.1fs" % (time.time() - t0))
        if not ready:
            print("超时未就绪")
            return 4

        # 2) 打一发
        payload = {
            "model": "smoke",
            "messages": [{"role": "user", "content": "用一句话自我介绍。"}],
            "temperature": 0.0,
            "max_tokens": 48,
            "stream": False,
        }
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/v1/chat/completions",
            data=json.dumps(payload).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json"},
        )
        # 关键：空 ProxyHandler，别让 urllib 去读注册表里的失效系统代理
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        t1 = time.time()
        try:
            with opener.open(req, timeout=120) as resp:
                body = json.loads(resp.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            print("HTTP", e.code, e.read().decode("utf-8", "replace")[:400])
            return 5
        ms = (time.time() - t1) * 1000
        msg = ((body.get("choices") or [{}])[0].get("message") or {})
        print("HTTP 200  用时 %.0fms" % ms)
        print("  model   :", body.get("model"))
        print("  content :", str(msg.get("content"))[:300])
        print("  usage   :", body.get("usage"))
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()
        print("llama-server 已关闭")


if __name__ == "__main__":
    raise SystemExit(main())
