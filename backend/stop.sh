#!/usr/bin/env bash
# =============================================================================
# AISecLab 后端 —— Linux / macOS 一键停止
#
#   ./stop.sh
#
# 做两件事（谁在跑就停谁）：
#   1) Docker 路径：docker compose down（连容器带网络一起清掉，不动 ./data）
#   2) 本机路径  ：结束 deploy.py / 手工起的 uvicorn app.main:app 进程
#
# 注意：只结束 AISecLab 自己的进程；不碰 ./data 目录里的任何东西。
# =============================================================================

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

stopped=0

# ── 1) Docker Compose ────────────────────────────────────────────────────────
DC=""
if docker compose version >/dev/null 2>&1; then
    DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    DC="docker-compose"
fi

if [ -n "$DC" ]; then
    # shellcheck disable=SC2086
    if $DC ps --quiet 2>/dev/null | grep -q .; then
        echo "[·] ${DC} down ..."
        # shellcheck disable=SC2086
        $DC down --remove-orphans
        stopped=1
    fi
fi

# ── 2) 本机 venv 起的 uvicorn ────────────────────────────────────────────────
if command -v pkill >/dev/null 2>&1; then
    if pkill -f 'uvicorn app.main:app' 2>/dev/null; then
        echo "[·] 已结束本机 uvicorn 进程（uvicorn app.main:app）"
        stopped=1
    fi
fi

if [ "$stopped" -eq 0 ]; then
    echo "[i] 没有发现正在运行的后端（容器与本机进程都没有）。"
fi

echo "[i] 数据目录 ./data 未改动。"
