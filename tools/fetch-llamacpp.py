"""下载 llama.cpp 源码（GitHub 直连不通，走 gh-proxy；必须带续传与完整性校验）。

为什么写得这么啰嗦：gh-proxy 会中途断流，第一次只拿到 2 MB 就"下载成功"了 ——
结果 tar 解到一半抛 EOFError，留下一个残缺的源码目录，后面对着一堆缺文件的
CMakeLists 报错，很难判断是网络问题还是代码问题。
所以这里：多源候选 + 断点续传 + 解压前先完整校验 gzip 流。
"""
import os
import shutil
import sys
import tarfile
import time

import requests

PROXY = {"http": "http://127.0.0.1:7585", "https": "http://127.0.0.1:7585"}
TAG = "v0.5.0"
TGZ = rf"D:\llama.cpp-{TAG}.tar.gz"
DEST = r"D:\third_party"
TARGET = os.path.join(DEST, f"llama.cpp-{TAG}")

CANDIDATES = [
    f"https://gh-proxy.com/https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/{TAG}",
    f"https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/archive/refs/tags/{TAG}.tar.gz",
    f"https://gh-proxy.com/https://api.github.com/repos/ggml-org/llama.cpp/tarball/{TAG}",
]


def download(url: str) -> int:
    """带续传的单源下载。返回最终文件大小。"""
    for attempt in range(1, 6):
        done = os.path.getsize(TGZ) if os.path.exists(TGZ) else 0
        headers = {"Range": f"bytes={done}-"} if done else {}
        try:
            with requests.get(url, proxies=PROXY, stream=True, timeout=120,
                              headers=headers) as r:
                if done and r.status_code != 206:
                    print(f"  服务端不支持续传（HTTP {r.status_code}），从头下")
                    done = 0
                r.raise_for_status()
                total = int(r.headers.get("content-length", 0)) + done
                mode = "ab" if done else "wb"
                with open(TGZ, mode) as f:
                    for chunk in r.iter_content(1 << 20):
                        f.write(chunk)
                        done += len(chunk)
                        if done % (5 << 20) < (1 << 20):
                            print(f"  {done>>20} MB / {total>>20} MB", flush=True)
            size = os.path.getsize(TGZ)
            print(f"  本轮结束：{size>>20} MB")
            if total and size >= total:
                return size
            print(f"  短于预期（{size} < {total}），重试续传…")
        except Exception as e:
            print(f"  第 {attempt} 次中断：{type(e).__name__}: {str(e)[:100]}，续传重试")
        time.sleep(2)
    return os.path.getsize(TGZ) if os.path.exists(TGZ) else 0


def verify_gzip(path: str) -> bool:
    """完整读一遍 gzip 流，确认没被截断。"""
    try:
        with tarfile.open(path, "r:gz") as t:
            n = sum(1 for _ in t)
        print(f"  gzip 完整，含 {n} 个条目")
        return True
    except Exception as e:
        print(f"  ❌ 压缩流不完整：{type(e).__name__}: {str(e)[:120]}")
        return False


def main():
    os.makedirs(DEST, exist_ok=True)

    # 清掉上次截断留下的残缺目录，否则解压会跳过、后面找不到文件
    stray = os.path.join(DEST, "llama.cpp-0.5.0")
    if os.path.isdir(stray):
        print("清理残缺目录", stray)
        shutil.rmtree(stray, ignore_errors=True)

    if not (os.path.exists(TGZ) and verify_gzip(TGZ)):
        for url in CANDIDATES:
            print("尝试源:", url)
            size = download(url)
            if verify_gzip(TGZ):
                print(f"✅ 下载完整：{size>>20} MB")
                break
            print("换下一个源…")
        else:
            print("❌ 所有源都没拿到完整文件")
            return 1
    else:
        print("已有完整源码包，跳过下载")

    if os.path.exists(os.path.join(TARGET, "include", "llama.h")):
        print("已解压:", TARGET)
        return 0

    print("解压到", DEST)
    with tarfile.open(TGZ, "r:gz") as t:
        t.extractall(DEST)
    for n in os.listdir(DEST):
        p = os.path.join(DEST, n)
        if os.path.isdir(p) and n.startswith("llama.cpp-") and n != f"llama.cpp-{TAG}":
            if os.path.isdir(TARGET):
                shutil.rmtree(TARGET, ignore_errors=True)
            os.rename(p, TARGET)
            break

    ok = os.path.exists(os.path.join(TARGET, "include", "llama.h"))
    print("源码根目录:", TARGET, "→ llama.h", "存在" if ok else "❌ 缺失")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
