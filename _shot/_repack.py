import os
import zipfile
import zlib

BACKEND = r"C:\AISecLab\backend"
DIST = r"C:\AISecLab\backend\dist"
OUT = r"C:\AISecLab\dist"

STANDALONE = os.path.join(OUT, "aiseclab-backend-standalone-win.zip")
SOURCE = os.path.join(OUT, "aiseclab-backend.zip")

SOURCE_FILES = [
    ".dockerignore", ".env.example", ".gitattributes", ".gitignore",
    "Dockerfile", "README.md",
    "app/__init__.py", "app/attack.py", "app/engine.py", "app/guard.py",
    "app/judge.py", "app/llm_client.py", "app/main.py", "app/models.py",
    "app/report_gen.py",
    "data/.gitkeep",
    "deploy.bat", "deploy.py", "docker-compose.yml", "requirements.txt",
    "run.bat", "smoke_test.py", "standalone.py",
]


def zcrc(path):
    return "%08x" % (zlib.crc32(open(path, "rb").read()) & 0xFFFFFFFF)


# ── 1) 独立包（免 Python 环境）──────────────────────────────────
n = 0
with zipfile.ZipFile(STANDALONE, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(DIST, "run.bat"), "run.bat")
    z.write(os.path.join(BACKEND, ".env.example"), ".env.example")
    n = 2
    root = os.path.join(DIST, "aiseclab-backend")
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, DIST).replace(os.sep, "/")
            z.write(full, rel)
            n += 1
print("standalone: %d entries, %.2f MB" % (n, os.path.getsize(STANDALONE) / 1048576))

# ── 2) 源码包（白名单）─────────────────────────────────────────
with zipfile.ZipFile(SOURCE, "w", zipfile.ZIP_DEFLATED) as z:
    for rel in SOURCE_FILES:
        z.write(os.path.join(BACKEND, rel),
                "aiseclab-backend/" + rel.replace(os.sep, "/"))
print("source: %d entries, %.2f KB" % (len(SOURCE_FILES), os.path.getsize(SOURCE) / 1024))

# ── 3) 校验：包内 exe / judge.py 必须与本机构建产物一致 ────────
EXE = os.path.join(DIST, "aiseclab-backend", "aiseclab-backend.exe")
JUDGE = os.path.join(BACKEND, "app", "judge.py")
with zipfile.ZipFile(STANDALONE) as z:
    info = z.getinfo("aiseclab-backend/aiseclab-backend.exe")
    print("\n[standalone] exe  in-zip crc=%08x size=%d" % (info.CRC, info.file_size))
    print("[standalone] exe  on-disk  crc=%s" % zcrc(EXE))
    assert "%08x" % info.CRC == zcrc(EXE), "exe 不一致！"
    assert len(z.namelist()) == n, "条目数异常"
with zipfile.ZipFile(SOURCE) as z:
    info = z.getinfo("aiseclab-backend/app/judge.py")
    print("[source] judge.py in-zip crc=%08x" % info.CRC)
    print("[source] judge.py on-disk  crc=%s" % zcrc(JUDGE))
    assert "%08x" % info.CRC == zcrc(JUDGE), "judge.py 不一致！"
    bad = z.testzip()
    assert bad is None, "CRC 校验失败：%s" % bad

print("\n[repack] ALL OK")
