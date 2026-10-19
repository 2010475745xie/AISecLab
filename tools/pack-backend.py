#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打 AISecLab 后端部署包。

## 为什么要有它

手工右键压缩会犯三个错，而且**错了不会有任何报错**，要等到对方服务器上才炸：

  1. **漏模块**：2026-10-12 那份 `aiseclab-backend.zip` 就漏了
     `checkpoint.py` / `jailbreak.py` / `jailbreak_cases.py` / `benign_controls.py` /
     `compliance.py` 五个模块 —— 对方 `uvicorn app.main:app` 直接 ImportError。
     所以这里**按 app/ 目录全量收**，并断言模块数，不靠手挑。
  2. **把 dev 脚本也塞进去**：`_verify_*.py`（10 个、~100KB）是本地验证用的，
     进了包只会让部署者困惑"这些是啥、要不要跑"。
  3. **把 .env 或 dist/ 打进去**：`dist/.env` 里可能有真实密钥。
     这两个是硬排除，脚本里断言"包内不存在任何 .env"。

另外两个只有 Linux 上才暴露的细节：

  - **`start.sh` / `stop.sh` 必须带可执行位**。zip 的 external_attr 不写，
    解压出来是 644，`./start.sh` 报 Permission denied。
  - **`start.sh` 必须是 LF**。CRLF 会报 `/usr/bin/env: 'bash\\r': No such file or directory`。

## 用法

    python tools/pack-backend.py              # 出包到 dist/
    python tools/pack-backend.py --out D:/x   # 换输出目录
"""

from __future__ import annotations

import os
import sys
import time
import zipfile

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(PROJECT, "backend")
QUICKSTART = os.path.join(PROJECT, "docs", "部署-后端快速部署.md")

# 包内顶层目录名（解压后是 aiseclab-backend/，不会把文件撒一地）
ROOT = "aiseclab-backend"

# 逐项白名单。app/ 目录整体收（只收 .py），其余文件逐个列 —— 新增文件要显式加进来，
# 免得将来又把 dev 脚本或密钥顺手带进去。
FILES = [
    ".dockerignore",
    ".gitattributes",
    ".gitignore",
    ".env.example",
    "Dockerfile",
    "README.md",
    "docker-compose.yml",
    "requirements.txt",
    "init.sh",
    "start.sh",
    "stop.sh",
    "deploy.py",
    "deploy.bat",
    "smoke_test.py",
    "data/.gitkeep",
]

# 必须带可执行位（0755）的文件
EXECUTABLE = {"init.sh", "start.sh", "stop.sh"}

# 包里绝不能出现的东西 —— 打完后逐条断言
FORBIDDEN_SUFFIX = (".env",)
FORBIDDEN_DIRS = ("dist/", "build/", "__pycache__/", ".git/")
FORBIDDEN_NAMES = ("aiseclab-backend.exe",)


def die(msg: str) -> None:
    print("[!] " + msg, file=sys.stderr)
    sys.exit(1)


def collect() -> list:
    """返回 [(包内路径, 磁盘绝对路径, 是否可执行)]。"""
    items = []

    for rel in FILES:
        src = os.path.join(BACKEND, rel.replace("/", os.sep))
        if not os.path.isfile(src):
            die("缺文件：%s（部署包必须完整，少一个就跑不起来）" % src)
        items.append((rel, src))

    app_dir = os.path.join(BACKEND, "app")
    if not os.path.isdir(app_dir):
        die("缺目录：%s" % app_dir)
    py = sorted(f for f in os.listdir(app_dir) if f.endswith(".py"))
    if len(py) < 10:
        die("app/ 下只找到 %d 个 .py，明显不对（预期 14 个）" % len(py))
    for f in py:
        items.append(("app/" + f, os.path.join(app_dir, f)))

    # 快速部署说明：从 docs/ 取，包内改名成更好认的名字
    if os.path.isfile(QUICKSTART):
        items.append(("DEPLOY.md", QUICKSTART))   # 包内文件名一律英文：GitHub / Linux unzip 对中文名容易乱码

    return [(rel, src, os.path.basename(rel) in EXECUTABLE) for rel, src in items]


def main() -> int:
    argv = sys.argv[1:]
    out_dir = os.path.join(PROJECT, "dist")
    if "--out" in argv:
        out_dir = argv[argv.index("--out") + 1]

    stamp = time.strftime("%Y%m%d")
    name = "aiseclab-backend-%s.zip" % stamp
    out_path = os.path.join(out_dir, name)
    os.makedirs(out_dir, exist_ok=True)

    items = collect()

    tmp = out_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, src, is_exec in items:
            raw = open(src, "rb").read()

            # .sh 必须是 LF，CRLF 在 Linux 上会报 "bash\r: No such file"
            if is_exec and b"\r\n" in raw:
                die("%s 是 CRLF，Linux 上跑不起来（先 sed -i 's/\\r$//' 修掉）" % rel)

            z.writestr(_info(ROOT + "/" + rel, is_exec), raw)

        # 空目录占位：data/ 在 compose 里被绑定挂载，缺目录时 docker 会建成 root 属主
        z.writestr(_dir_info(ROOT + "/data/"), b"")

    os.replace(tmp, out_path)

    # ── 自检：解回来逐条看（同一个句柄，别反复开）────────────────────────
    problems = []
    with zipfile.ZipFile(out_path) as z:
        names = z.namelist()
        bad = z.testzip()
        if bad is not None:
            problems.append("zip 内部损坏：%s" % bad)
        if len(names) != len(items) + 1:  # +1 = data/ 目录项
            problems.append("条目数不对：实际 %d，预期 %d" % (len(names), len(items) + 1))

        for n in names:
            base = os.path.basename(n)
            if base == ".env" or n.endswith("/.env"):
                problems.append("包里混进了 .env：%s" % n)
            if any(d in n for d in FORBIDDEN_DIRS):
                problems.append("包里混进了构建产物：%s" % n)
            if base in FORBIDDEN_NAMES:
                problems.append("包里混进了冻结 exe：%s" % n)
            if n.endswith(".sh"):
                mode = (z.getinfo(n).external_attr >> 16) & 0o777
                if mode != 0o755:
                    problems.append("%s 权限位是 %o，应为 755" % (n, mode))

    if problems:
        print("[!] 自检未通过：")
        for p in problems:
            print("    - " + p)
        return 1

    size = os.path.getsize(out_path)
    print("[ok] %s" % out_path)
    print("     %d 个文件 / %.1f KB" % (len(names) - 1, size / 1024.0))
    print("     app 模块：%d" % len([n for n in names if n.startswith(ROOT + "/app/") and n.endswith(".py")]))
    print("     排除项：_verify_*.py / standalone.py / *.spec / build / dist / __pycache__ / .env")
    return 0


def _info(arcname: str, is_exec: bool) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(arcname, date_time=time.localtime()[:6])
    info.compress_type = zipfile.ZIP_DEFLATED
    # zip 用 unix 权限高位：普通文件 0644，脚本 0755
    info.external_attr = (0o100755 if is_exec else 0o100644) << 16
    return info


def _dir_info(arcname: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(arcname, date_time=time.localtime()[:6])
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = (0o40755 << 16) | 0x10
    return info


if __name__ == "__main__":
    sys.exit(main())
