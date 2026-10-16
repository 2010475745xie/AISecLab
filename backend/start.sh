#!/usr/bin/env bash
# =============================================================================
# AISecLab 后端 —— 一键启动（基准环境：Rocky Linux 9.2；Debian/Ubuntu/macOS 通用）
#
#   ./start.sh              自动选路：装了 Docker 就走容器，没有就走本机 venv
#   ./start.sh --docker     强制走 Docker Compose（服务器 / 比赛评审环境推荐）
#   ./start.sh --local      强制走本机 venv（无需 Docker，前台运行，Ctrl+C 停止）
#   ./start.sh --help       看这页
#
# 停止统一用：./stop.sh
#
# 环境要求
#   Docker 路径：Docker 20.10+，且带 compose（v2 的 `docker compose` 或 v1 的 `docker-compose`）
#   本机  路径：Python 3.9+，且能创建 venv
#               · Rocky / RHEL 9 的系统 python3 就是 3.9，可直接用；
#                 若报 "ensurepip is not available" → sudo dnf install -y python3-pip
#               · 想和 Docker 镜像的 3.11 对齐 → sudo dnf install -y python3.11 python3.11-pip
#                 然后 AISECLAB_PYTHON=/usr/bin/python3.11 ./start.sh --local
#               · Debian / Ubuntu 报 venv 缺失 → sudo apt install -y python3-venv
#
# 行为说明
#   - 首次运行若没有 .env，会自动从 .env.example 复制一份（里面是占位值，不是真实密钥）。
#   - 起完后会等 /health 通过，再打印「手机 App → 设置 → 测试后端」要填的地址。
#   - Docker 路径是后台运行（-d），命令会返回；本机路径是前台运行，会占住终端。
#   - Rocky / RHEL 的 firewalld 默认开着：脚本会提示放行端口的命令（不做，外部访问不通）。
# =============================================================================

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$HERE/.env"

usage() {
    # 打印文件头部那段 # 注释，直到第一个非注释行为止（再删掉那一行）。
    # 刻意不写死行号 —— 头部注释一旦增删，行号区间就会漂，把 `set -euo pipefail`
    # 之类的正片内容打出来（2026-10-16 踩过，正是这个 bug）。
    sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//'
}

# ── 读 .env 里的一个键（不 source：.env 里可能含 # 注释与特殊字符）────────────
env_value() {  # $1=键名  $2=默认值
    local key="$1" default="$2" line value
    line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
    if [ -z "$line" ]; then
        printf '%s\n' "$default"
        return 0
    fi
    value="${line#*=}"
    value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    case "$value" in
        \"*\") value="${value#\"}"; value="${value%\"}" ;;
        \'*\') value="${value#\'}"; value="${value%\'}" ;;
    esac
    if [ -n "$value" ]; then
        printf '%s\n' "$value"
    else
        printf '%s\n' "$default"
    fi
}

# ── 探测 Docker Compose（v2 优先，兼容 v1）────────────────────────────────────
detect_compose() {
    if docker compose version >/dev/null 2>&1; then
        printf 'docker compose\n'
    elif command -v docker-compose >/dev/null 2>&1; then
        printf 'docker-compose\n'
    fi
}

# ── 挑一个本机解释器（新版优先，但不动系统 python3 软链）──────────────────────
# Rocky 9 的 python3 是 3.9：能用；但如果 AppStream 里装了 3.11/3.12，优先用新的，
# 这样和 Docker 镜像（3.11）行为一致。可用 AISECLAB_PYTHON 直接指定。
PYTHON_BIN=""
pick_python() {
    local c
    if [ -n "${AISECLAB_PYTHON:-}" ]; then
        PYTHON_BIN="$AISECLAB_PYTHON"
    else
        for c in python3.13 python3.12 python3.11 python3.10 python3 python3.9; do
            if command -v "$c" >/dev/null 2>&1; then
                PYTHON_BIN="$c"
                break
            fi
        done
    fi
    if [ -z "$PYTHON_BIN" ]; then
        echo "[!] 没找到 python3。Rocky / RHEL 系：" >&2
        echo "      sudo dnf install -y python3 python3-pip" >&2
        echo "    Debian / Ubuntu 系：sudo apt install -y python3 python3-venv" >&2
        exit 1
    fi
    # AISECLAB_PYTHON 可能给的是不存在的路径或命令名 —— 那种情况下面的版本探测
    # 也会失败，会被误报成"版本低于 3.9"。先单独把这一种区分出来。
    if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 && [ ! -x "$PYTHON_BIN" ]; then
        echo "[!] 指定的解释器不存在或不可执行：$PYTHON_BIN" >&2
        echo "    检查 AISECLAB_PYTHON 的值（用 command -v python3.11 能看真实路径）。" >&2
        exit 1
    fi
    if ! "$PYTHON_BIN" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
        echo "[!] 挑中的解释器 $PYTHON_BIN 版本低于 3.9，本项目要求 3.9+。" >&2
        echo "    Rocky / RHEL 系装一个 AppStream 里的新版（不动系统 python3）：" >&2
        echo "      sudo dnf install -y python3.11 python3.11-pip" >&2
        echo "      AISECLAB_PYTHON=/usr/bin/python3.11 ./start.sh --local" >&2
        exit 1
    fi
    if ! "$PYTHON_BIN" -m venv --help >/dev/null 2>&1; then
        echo "[!] $PYTHON_BIN 不能创建虚拟环境。" >&2
        echo "    Rocky / RHEL / Fedora 系（ensurepip 缺失）：sudo dnf install -y python3-pip" >&2
        echo "    Debian / Ubuntu 系：sudo apt install -y python3-venv" >&2
        echo "    或者改用 Docker 路径：./start.sh --docker" >&2
        exit 1
    fi
    echo "[·] 本机解释器：$("$PYTHON_BIN" -c 'import sys; print(sys.executable, "%d.%d.%d" % sys.version_info[:3])')"
}

# ── 列出本机非回环 IPv4（Linux 用 hostname -I / ip，macOS 用 ifconfig）───────
list_ips() {
    if hostname -I >/dev/null 2>&1; then
        hostname -I 2>/dev/null | tr ' ' '\n'
    elif command -v ip >/dev/null 2>&1; then
        ip -4 -o addr show scope global 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p'
    elif command -v ifconfig >/dev/null 2>&1; then
        ifconfig 2>/dev/null | sed -n 's/.*inet \(addr:\)\{0,1\}\([0-9.]*\).*/\2/p'
    fi
}

is_private() {
    case "$1" in
        10.*|192.168.*) return 0 ;;
        172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
        *) return 1 ;;
    esac
}

# ── 等 /health 通过 ─────────────────────────────────────────────────────────
wait_health() {
    local url="http://127.0.0.1:${PORT}/health" i
    printf '[·] 等待 %s 就绪' "$url"
    for i in $(seq 1 60); do
        if command -v curl >/dev/null 2>&1; then
            if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
                printf ' —— 通过\n'
                return 0
            fi
        elif command -v python3 >/dev/null 2>&1; then
            if python3 -c "import urllib.request;urllib.request.urlopen('${url}',timeout=2)" >/dev/null 2>&1; then
                printf ' —— 通过\n'
                return 0
            fi
        else
            printf '\n[!] 既没有 curl 也没有 python3，无法探活；请自行开 %s 确认。\n' "$url"
            return 0
        fi
        printf '.'
        sleep 1
    done
    printf ' —— 超时（60s）\n'
    return 1
}

print_endpoints() {
    local ip
    echo
    echo "============================================================"
    echo "  AISecLab 后端已启动"
    echo "  健康检查 : http://127.0.0.1:${PORT}/health"
    echo "  接口预览 : http://127.0.0.1:${PORT}/docs   （Swagger UI，免鉴权）"
    echo
    echo "  手机 App「设置 → 测试后端」填："
    echo "    本机 / 模拟器              :  http://127.0.0.1:${PORT}"
    for ip in $(list_ips | sort -u); do
        case "$ip" in
            ''|127.*|169.254.*) continue ;;
        esac
        if is_private "$ip"; then
            echo "    同网段设备（手机一般填这个）:  http://${ip}:${PORT}   [私网]"
        else
            echo "    其他网卡                    :  http://${ip}:${PORT}   [非私网：可能是 VPN/虚拟网卡，多半连不上]"
        fi
    done
    # Rocky / RHEL 的 firewalld 默认是开的：不放行端口，宿主机自己通、外部一律不通。
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        echo
        echo "  ⚠️ firewalld 正在运行 —— 要让别的机器（含手机）连上，先放行端口："
        echo "      sudo firewall-cmd --permanent --add-port=${PORT}/tcp && sudo firewall-cmd --reload"
    fi
    echo
    echo "  停止：./stop.sh"
    echo "============================================================"
}

print_selinux_hint() {
    # SELinux 排查提示：只在起不来时才打，平时不啰嗦
    if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce 2>/dev/null || echo '')" = "Enforcing" ]; then
        echo "  ⚠️ 本机 SELinux 处于 Enforcing。若容器报 Permission denied / EACCES，"
        echo "     多半是绑定挂载缺 SELinux 标签 —— 本仓库的 docker-compose.yml 已给 ./data 打了 :z，"
        echo "     若你改过 volumes，请补回 ':z'（见 README 第 10 节）。"
    fi
}

run_docker() {  # $1 = "docker compose" / "docker-compose"
    local dc="$1"
    echo "[·] 使用「${dc}」构建并启动容器 ..."
    # 这里刻意不加引号：$dc 要么是 "docker compose" 两个词，要么是一个词，都需要按词拆分
    # shellcheck disable=SC2086
    $dc up -d --build
    if wait_health; then
        print_endpoints
        # shellcheck disable=SC2086
        echo "[i] 看日志：$dc logs -f --tail=100"
    else
        echo "[!] 容器起来了但 /health 没通过，看日志定位："
        # shellcheck disable=SC2086
        echo "    $dc logs --tail=100"
        print_selinux_hint
        exit 1
    fi
}

run_local() {
    pick_python
    echo "[·] 走本机 venv 路径（前台运行，Ctrl+C 停止）"
    echo "    它会：建 venv → 装依赖 → 生成 .env → 起 uvicorn → 等 /health → 打印地址"
    export AISECLAB_PYTHON="$PYTHON_BIN"
    exec "$PYTHON_BIN" "$HERE/deploy.py"
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
MODE=auto
while [ $# -gt 0 ]; do
    case "$1" in
        --docker) MODE=docker ;;
        --local)  MODE=local ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数：$1（可用 --docker / --local / --help）" >&2; exit 2 ;;
    esac
    shift
done

if [ ! -f "$ENV_FILE" ]; then
    cp "$HERE/.env.example" "$ENV_FILE"
    echo "[i] 已由 .env.example 生成 .env（占位值，不含真实密钥）。"
    echo "    想用服务端裁判/攻击模型，编辑它填 JUDGE_BASE_URL / JUDGE_MODEL / JUDGE_API_KEY；"
    echo "    不填也能起服务 —— 服务端裁判会返回 503，但 App 可在请求体里自带裁判（BYOK 委托）。"
    echo
fi

PORT="$(env_value PORT 8000)"
export PORT

DC="$(detect_compose)"

case "$MODE" in
    docker)
        if [ -z "$DC" ]; then
            echo "[!] 没检测到 Docker Compose。Rocky / RHEL 系装法：" >&2
            echo "      sudo dnf install -y dnf-plugins-core" >&2
            echo "      sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo" >&2
            echo "      sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin" >&2
            echo "      sudo systemctl enable --now docker" >&2
            echo "    也可用：./start.sh --local" >&2
            exit 1
        fi
        run_docker "$DC"
        ;;
    local)
        run_local
        ;;
    auto)
        if [ -n "$DC" ]; then
            run_docker "$DC"
        else
            echo "[i] 没检测到 Docker，回退到本机 venv 路径。（想强制用 Docker：./start.sh --docker）"
            run_local
        fi
        ;;
esac
