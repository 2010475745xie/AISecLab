"""取 GGUF 的权威 sha256 并校验本地副本。

为什么非要权威哈希：端上只校验自己算的哈希等于代码里写死的常量，
如果那个常量本身来自"我们自己下的这份文件"，就等于自证 ——
中间被污染也发现不了。所以从 HF 的 API 取 LFS oid（即 sha256），
再和本地算出来的比对：既拿到可信常量，又顺带验证本地文件是完整的。
"""
import hashlib
import json
import os

import requests

PROXY = {"http": "http://127.0.0.1:7585", "https": "http://127.0.0.1:7585"}
REPO = "mradermacher/YuFeng-XGuard-Reason-0.6B-GGUF"
FILE = "YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"
LOCAL = os.path.join(r"D:\models", FILE)

FALLBACK = {
    "YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf": (484217984, None),
    "YuFeng-XGuard-Reason-0.6B.IQ4_XS.gguf": (None, None),
    "YuFeng-XGuard-Reason-0.6B.Q3_K_S.gguf": (None, None),
}


def from_api():
    """HF 的 tree API 给出每个 LFS 文件的 oid == sha256。
    注意要用 `?recursive=true` 的 tree 接口；model 接口拿不到 oid。"""
    url = f"https://hf-mirror.com/api/models/{REPO}/tree/main?recursive=true"
    r = requests.get(url, proxies=PROXY, timeout=60)
    r.raise_for_status()
    out = {}
    for e in r.json():
        path = e.get("path", "")
        if not path.endswith(".gguf"):
            continue
        lfs = e.get("lfs") or {}
        out[path] = {"sha256": lfs.get("oid"), "size": e.get("size")}
    return out


def sha256_of(path: str, chunk=1 << 22) -> str:
    h = hashlib.sha256()
    n = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
            n += len(b)
    return h.hexdigest()


def main():
    print("查询 HF API …")
    meta = from_api()
    if not meta:
        print("API 没返回 gguf 条目，只能用本地哈希（可靠性打折）")
    result = {}
    for name, m in sorted(meta.items()):
        print(f"  {name}: size={m['size']} sha256={m['sha256']}")
    result.update(meta)

    print(f"\n计算本地 {FILE} 的 sha256 …")
    if os.path.exists(LOCAL):
        local = sha256_of(LOCAL)
        size = os.path.getsize(LOCAL)
        print(f"  size={size} sha256={local}")
        api = meta.get(FILE)
        if api and api.get("sha256"):
            ok = api["sha256"] == local
            print(f"  与 HF 权威哈希比对：{'✅ 一致' if ok else '❌ 不一致（文件被污染或下载不完整）'}")
            if not ok:
                return 1
        if api and api.get("size") and api["size"] != size:
            print(f"  ❌ 大小不一致：本地 {size} vs HF {api['size']}")
            return 1
        result[FILE] = {"sha256": local, "size": size}
    else:
        print("  本地没有该文件，跳过")

    with open("C:/AISecLab/tools/model-manifest.json", "w", encoding="utf-8") as f:
        json.dump({"repo": REPO, "files": result}, f, ensure_ascii=False, indent=2)
    print("\n已写出 tools/model-manifest.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
