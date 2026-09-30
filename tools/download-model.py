#!/usr/bin/env python3
"""
从 hf-mirror 下载 GGUF 模型（HuggingFace 主站在国内被墙，镜像可用）。

特性：断点续传、进度输出、按官方 API 给的 size/sha256 做完整性校验。
中途断了直接重跑同一条命令即可。

    python tools/download-model.py <repo_id> <filename> <dest_dir>

例：
    python tools/download-model.py mradermacher/YuFeng-XGuard-Reason-0.6B-GGUF \
        YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf D:/models
"""

import hashlib
import json
import os
import socket
import ssl
import sys
import time
import urllib.request

MIRROR = "https://hf-mirror.com"
CHUNK = 1024 * 256

socket.setdefaulttimeout(60)
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, context=CTX) as r:
        return json.load(r)


def expected(repo_id: str, filename: str):
    """拿到官方给的 size 与 lfs.sha256，用于校验。拿不到就返回 (None, None)。"""
    try:
        info = fetch_json(f"{MIRROR}/api/models/{repo_id}?blobs=true")
    except Exception as e:
        print(f"[警告] 取元数据失败，跳过校验：{e}")
        return None, None
    for s in info.get("siblings", []):
        if s.get("rfilename") == filename:
            lfs = s.get("lfs") or {}
            return s.get("size") or lfs.get("size"), lfs.get("sha256")
    print(f"[警告] 元数据里没有 {filename}，跳过校验")
    return None, None


def human(n):
    return f"{n / 1024 / 1024:.1f} MB"


def download(url, dest, total):
    pos = os.path.getsize(dest) if os.path.exists(dest) else 0
    if total and pos >= total:
        print(f"已完整，跳过下载（{human(pos)}）")
        return
    headers = {"User-Agent": "Mozilla/5.0"}
    if pos > 0:
        headers["Range"] = f"bytes={pos}-"
        print(f"断点续传，从 {human(pos)} 继续")
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, context=CTX) as r:
        if pos > 0 and r.status != 206:
            print("[警告] 服务端不支持续传，从头开始")
            pos = 0
        mode = "ab" if pos > 0 else "wb"
        done, last, t0 = pos, pos, time.time()
        with open(dest, mode) as f:
            while True:
                buf = r.read(CHUNK)
                if not buf:
                    break
                f.write(buf)
                done += len(buf)
                if time.time() - last > 2:
                    last = time.time()
                    speed = (done - pos) / max(time.time() - t0, 1e-6) / 1024 / 1024
                    pct = f"{done / total * 100:5.1f}%" if total else "  ?  "
                    print(f"  {pct}  {human(done)} / {human(total) if total else '?'}  {speed:.1f} MB/s")
        print(f"下载结束：{human(done)}")


def sha256(path, chunk=1024 * 1024):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    repo_id, filename, dest_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, filename)
    url = f"{MIRROR}/{repo_id}/resolve/main/{filename}"

    size, want_sha = expected(repo_id, filename)
    print(f"仓库 {repo_id}")
    print(f"文件 {filename}")
    print(f"目标 {dest}")
    print(f"预期大小 {human(size) if size else '未知'}")
    print()

    t0 = time.time()
    download(url, dest, size)
    print(f"耗时 {time.time() - t0:.0f}s")

    actual = os.path.getsize(dest)
    if size and actual != size:
        sys.exit(f"[失败] 大小不符：{actual} != {size}（重跑本命令可续传）")

    got = sha256(dest)
    if want_sha:
        if got.lower() != want_sha.lower():
            sys.exit(f"[失败] sha256 不符\n  期望 {want_sha}\n  实际 {got}")
        print(f"[OK] sha256 校验通过：{got}")
    else:
        print(f"sha256（官方未提供，自行记录）：{got}")


if __name__ == "__main__":
    main()
