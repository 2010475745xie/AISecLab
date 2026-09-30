"""探测可用的 llama.cpp / 预编译二进制来源。沙箱出网走 127.0.0.1:7585 代理。"""
import requests

PROXY = {"http": "http://127.0.0.1:7585", "https": "http://127.0.0.1:7585"}

URLS = [
    # 1) llama-cpp-python 官方 wheel 索引（GitHub Pages）
    "https://abetlen.github.io/llama-cpp-python/whl/cpu/llama-cpp-python/",
    # 2) Ollama 官方安装器（非 GitHub）
    "https://ollama.com/download/OllamaSetup.exe",
    # 3) GitHub release 代理们 —— 用 releases/latest API 探活
    "https://ghfast.top/https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
    "https://gh-proxy.com/https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
    "https://ghproxy.net/https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
    "https://mirror.ghproxy.com/https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
    # 4) 国内镜像
    "https://gitee.com/mirrors/llama.cpp",
    "https://www.modelscope.cn/api/v1/models?Name=llama.cpp&PageSize=5",
    # 5) PyTorch CPU wheel 源
    "https://download.pytorch.org/whl/cpu/torch/",
    # 6) 对照组：已知可用
    "https://hf-mirror.com",
]


def probe(url: str) -> str:
    try:
        r = requests.get(url, proxies=PROXY, timeout=20, stream=True,
                         headers={"User-Agent": "Mozilla/5.0"})
        info = f"HTTP {r.status_code}"
        if "content-length" in r.headers:
            info += f" len={r.headers['content-length']}"
        ct = r.headers.get("content-type", "")
        if ct:
            info += f" ct={ct.split(';')[0]}"
        r.close()
        return info
    except Exception as e:
        return f"FAIL {type(e).__name__}: {str(e)[:120]}"


for u in URLS:
    print(f"{probe(u):<70} {u}")
