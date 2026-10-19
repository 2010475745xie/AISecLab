#!/usr/bin/env bash
# =============================================================================
# AISecLab 后端 —— 初始化脚本
#
# 本项目**没有数据库**：评测状态在内存（sessions dict），检查点以 JSON 落盘，
# 所以「数据初始化」在这里只做三件事：建目录、查端口、验依赖。
# 不存在种子数据，也不需要 migration。
#
#   ./init.sh            常规初始化（幂等，可反复跑）
#   ./init.sh --check    只检查，不创建任何东西
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

PORT="$(grep -E '^[[:space:]]*PORT[[:space:]]*=' "$HERE/.env" 2>/dev/null | tail -n1 | sed 's/.*=[[:space:]]*//' || true)"
PORT="${PORT:-8000}"

echo "[1/4] 目录"
if [ "$CHECK_ONLY" = "1" ]; then
  [ -d "$HERE/data" ] && echo "      data/ 已存在" || echo "      data/ 不存在（--check 模式不创建）"
else
  mkdir -p "$HERE/data" "$HERE/data/checkpoints"
  echo "      data/ 与 data/checkpoints/ 就绪"
fi

echo "[2/4] 运行环境"
if command -v docker >/dev/null 2>&1; then
  echo "      docker $(docker --version 2>/dev/null | sed 's/Docker version //;s/,.*//')"
  if docker compose version >/dev/null 2>&1; then
    echo "      compose v2 可用"
  else
    echo "      [!] 未检测到 docker compose（v2）。请装 docker-compose-plugin 后重试" >&2
  fi
elif command -v python3 >/dev/null 2>&1; then
  echo "      未装 Docker，回退到本机 Python：$(python3 -V 2>&1)"
  python3 - "$HERE" <<'PY' || true
import os, sys
here = sys.argv[1]
missing = [m for m in ("fastapi", "uvicorn", "httpx", "pydantic")
           if not __import__("importlib").util.find_spec(m)]
print("      依赖缺失：" + ", ".join(missing) if missing else "      依赖齐全")
PY
else
  echo "      [!] 既没有 docker 也没有 python3" >&2
fi

echo "[3/4] 端口 ${PORT}"
if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | grep -q ":${PORT} "; then
    echo "      [!] ${PORT} 已被占用，改 .env 里的 PORT 或先停掉占用进程" >&2
  else
    echo "      ${PORT} 空闲"
  fi
elif command -v netstat >/dev/null 2>&1; then
  if netstat -ltn 2>/dev/null | grep -q ":${PORT} "; then
    echo "      [!] ${PORT} 已被占用" >&2
  else
    echo "      ${PORT} 空闲"
  fi
else
  echo "      跳过（没有 ss / netstat）"
fi

echo "[4/4] 配置文件"
if [ -f "$HERE/.env" ]; then
  echo "      .env 已存在（裁判模型配置从它读取；缺省不影响 /health）"
else
  if [ "$CHECK_ONLY" = "1" ]; then
    echo "      .env 不存在（--check 模式不创建）"
  else
    cp "$HERE/.env.example" "$HERE/.env"
    echo "      已从 .env.example 生成 .env（里面是占位值，不是真实密钥）"
  fi
fi

echo
echo "初始化完成。启动：docker compose up -d --build    健康检查：curl http://127.0.0.1:${PORT}/health"
