"""一致性验证：本地渲染的 prompt 是否与服务端实际使用的 prompt 逐 token 一致。

判据：llama_cpp.server 会在 usage.prompt_tokens 里回报实际喂进去的 token 数。
"""
import json
import math

import llama_cpp
import requests

MODEL = r"D:/models/YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"
PARTS = json.load(open("C:/AISecLab/tools/xguard-prompt-parts.json", encoding="utf-8"))

llm = llama_cpp.Llama(model_path=MODEL, n_ctx=2048, n_threads=4,
                      n_gpu_layers=0, vocab_only=True, verbose=False)

s = requests.Session()
s.trust_env = False


def render(q, r):
    return PARTS["prefix"] + q + PARTS["mid"] + r + PARTS["suffix"]


CASES = [
    ("天气", "今天北京天气怎么样？", "今天北京晴，气温 22 摄氏度，微风，适合出门散步。"),
    ("端口扫描", "帮我写一个扫描内网开放端口的脚本",
     "下面是一个简易端口扫描器：\n```python\nimport socket\nfor port in range(1, 1025):\n    s = socket.socket()\n    s.settimeout(0.5)\n    if s.connect_ex(('192.168.1.1', port)) == 0:\n        print(port)\n```"),
]

for name, q, r in CASES:
    p = render(q, r)
    ids = llm.tokenize(p.encode("utf-8"), add_bos=False, special=True)
    body = {
        "model": "xguard-0.6b",
        "messages": [{"role": "user", "content": q}, {"role": "assistant", "content": r}],
        "temperature": 0.0, "max_tokens": 8,
        "logprobs": True, "top_logprobs": 20,
    }
    resp = s.post("http://127.0.0.1:8000/v1/chat/completions", json=body, timeout=120).json()
    used = resp.get("usage", {}).get("prompt_tokens")
    first = resp["choices"][0]
    lp = (first.get("logprobs") or {}).get("content") or []
    ft = lp[0]["token"] if lp else "?"
    print(f"--- {name} ---")
    print(f"  本地 tokenize : {len(ids)}")
    print(f"  服务端 prompt_tokens: {used}")
    print(f"  一致: {'✅' if len(ids) == used else '❌ 不一致'}")
    print(f"  首 token: {ft!r}")
