"""从清华 PyPI 镜像取 llama-cpp-python 的 sdist，并抽出 vendor/llama.cpp。

为什么绕这一圈拿 llama.cpp 源码：
* GitHub 直连不通，gh-proxy 的中转会对 30 MB 的 tarball 中途断流（实测只拿到 2 MB 就"完成"）；
* 而这个 sdist（59 MB）里**完整打包了 llama.cpp 源码**（`vendor/llama.cpp`），
  清华镜像 11 MB/s，稳定；
* 额外好处：它就是 PC 侧 llama-cpp-python 0.3.19 用的那份 llama.cpp，
  **PC 与端上跑同一版推理内核** —— 数值一致性更有保障，而不是"两边差不多"。
"""
import os
import re
import tarfile
import time
from urllib.parse import urljoin

import requests

PROXY = {"http": "http://127.0.0.1:7585", "https": "http://127.0.0.1:7585"}
VER = "0.3.19"
DEST = r"D:\third_party"
TGZ = os.path.join(DEST, f"llama_cpp_python-{VER}.tar.gz")
TARGET = os.path.join(DEST, "llama_cpp_src")

# 只抽构建 libllama 真正需要的子树。
# 全量抽 2559 个文件时实测在中途被打断（沙箱里大量写文件的进程会被 SIGTERM），
# 而 examples/docs/.github 这些对静态库毫无用处 —— 白写几千个文件还挨杀。
# 白名单比黑名单稳：llama.cpp 新增顶层目录时不会意外漏掉构建所需的部分。
WANTED_TOP = {
    "CMakeLists.txt", "LICENSE", "LICENSE.txt",
    "cmake",      # 根 CMakeLists 会 include 这里的 helper
    "include",    # llama.h 等公共头
    "src",        # llama.cpp / llama-*.cpp
    "ggml",       # ggml 后端（最大的一块，必需）
    "vendor",     # 第三方单头文件（json 等）
    "common",     # 某些版本根 CMakeLists 会引用
}


def download():
    os.makedirs(DEST, exist_ok=True)
    if os.path.exists(TGZ) and os.path.getsize(TGZ) > 50 << 20:
        print("已有 sdist，跳过下载:", round(os.path.getsize(TGZ) / 1048576, 1), "MB")
        return
    idx = "https://pypi.tuna.tsinghua.edu.cn/simple/llama-cpp-python/"
    html = requests.get(idx, proxies=PROXY, timeout=60).text
    # 注意：simple 索引的 href 是**相对路径**且带 `#sha256=...` 片段。
    # 两步都要做：先剥片段，再 urljoin 补全 —— 少任一步都会失败（两个坑都踩过）。
    urls = [urljoin(idx, u.split("#")[0]) for u in re.findall(r'href="([^"]+)"', html)]
    sdist = [u for u in urls if u.endswith(".tar.gz")]
    cand = [u for u in sdist if VER in u]
    if not cand:
        raise SystemExit(
            f"镜像上没找到 {VER} 的 sdist（共 {len(urls)} 个文件，其中 sdist {len(sdist)} 个）"
        )
    url = cand[0]
    print("下载", url)
    t0 = time.time()
    # 清华镜像的 CDN 对 `python-requests` 的 UA 直接 403（pip 自己下却没事），
    # 所以这里伪装成 pip 的 UA。踩过一次：403 Client Error: Forbidden。
    headers = {"User-Agent": "pip/26.2.1 (python 3.13)"}
    with requests.get(url, proxies=PROXY, stream=True, timeout=300, headers=headers) as r:
        r.raise_for_status()
        done = 0
        with open(TGZ, "wb") as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if done % (10 << 20) < (1 << 20):
                    print(f"  {done>>20} MB", flush=True)
    print(f"完成 {round(os.path.getsize(TGZ)/1048576,1)} MB，用时 {time.time()-t0:.0f}s")


def extract_llama_cpp():
    if os.path.exists(os.path.join(TARGET, "include", "llama.h")):
        print("已抽出 llama.cpp:", TARGET)
        return
    print("从 sdist 抽出 vendor/llama.cpp（仅构建所需子树）…", flush=True)

    staging = os.path.join(DEST, "_sdist_stage")
    with tarfile.open(TGZ, "r:gz") as t:
        # 必须排除 `/.git/`：sdist 里带了一个子模块的 .git 目录
        # （`llama_cpp_python-0.3.19/.git/modules/vendor/llama.cpp/…`）。
        # 它也包含 `/vendor/llama.cpp/` 子串，一旦混进来，用第一个成员算前缀就会得到
        # 错误的前缀，相对路径切片后文件全变成 `Lists.txt` `-cache.cpp` 这种鬼名字 ——
        # 目录树看着"有东西"，其实全是错的。踩过一次。
        members = []
        for m in t.getmembers():
            if "/vendor/llama.cpp/" not in m.name or "/.git/" in m.name or not m.isreg():
                continue
            head, rel = m.name.split("/vendor/llama.cpp/", 1)
            if rel.split("/", 1)[0] not in WANTED_TOP:
                continue
            members.append((m, rel))

        print(f"  选中条目：{len(members)}", flush=True)
        if not members:
            raise SystemExit("sdist 里没有 vendor/llama.cpp，需改用别的方式获取源码")

        # 逐文件抽取（而不是 extractall），为了**可续传**：
        # 沙箱里这个抽取过程会被 SIGTERM 打断（实测 ~69s 时），
        # 已存在的文件按大小跳过，重跑就能接着往下走。
        done = skipped = 0
        for m, rel in members:
            out = os.path.join(staging, *rel.split("/"))
            if os.path.exists(out) and os.path.getsize(out) == m.size:
                skipped += 1
            else:
                os.makedirs(os.path.dirname(out), exist_ok=True)
                src = t.extractfile(m)
                if src is None:
                    continue
                with open(out, "wb") as f:
                    while True:
                        buf = src.read(1 << 20)
                        if not buf:
                            break
                        f.write(buf)
            done += 1
            if done % 100 == 0:
                print(f"  {done}/{len(members)}（跳过 {skipped}）", flush=True)

    print(f"  抽取结束：处理 {done} 个，其中跳过已存在 {skipped} 个", flush=True)

    # 抽取时已经把 `llama_cpp_python-X/vendor/llama.cpp/` 这段前缀剥掉了，
    # 所以 llama.cpp 的根目录**就是 staging 本身**。
    # （一开始这里按 tar 里的完整路径算 src 目录，结果指向一个空壳目录 —— 见注释里的前缀坑。）
    if not os.path.exists(os.path.join(staging, "include", "llama.h")):
        raise SystemExit(f"抽取结果不完整，缺少 include/llama.h：{staging}")
    if os.path.isdir(TARGET):
        raise SystemExit(f"目标目录已存在，请先手动清理：{TARGET}")
    os.rename(staging, TARGET)
    print("  抽出完成 →", TARGET, flush=True)


def main():
    download()
    extract_llama_cpp()
    h = os.path.join(TARGET, "include", "llama.h")
    ok = os.path.exists(h)
    print("llama.h:", h, "→", "存在 ✅" if ok else "❌ 缺失")
    if ok:
        print("llama.cpp 根目录:", TARGET)
        print("CMakeLists.txt:", os.path.exists(os.path.join(TARGET, "CMakeLists.txt")))
        print("src/llama.cpp:", os.path.exists(os.path.join(TARGET, "src", "llama.cpp")))
        print("ggml/CMakeLists.txt:", os.path.exists(os.path.join(TARGET, "ggml", "CMakeLists.txt")))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
