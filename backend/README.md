# AISecLab 后端 · 部署说明

FastAPI 后端：调用被测模型 / 攻击模型 / 裁判模型，跑「规则 + LLM 三角色裁判」判定链，
输出报告 JSON 供客户端（安卓 App）渲染。BYOK 设计 —— 被测模型与裁判模型的密钥由客户端
在每次任务里带上，服务端**不预置、不落盘**。

> **本服务对外是 HTTP API**，根路径 `/` 没有网页（打开会 404）。正式客户端是安卓 App。
> 想在浏览器里看接口、甚至手动调一次：打开 **`http://<地址>:8000/docs`**
> （FastAPI 自带的 Swagger UI，与 `/health`、`/openapi.json` 一样免鉴权）；
> 只想确认服务在不在：`curl http://<地址>:8000/health`。

**基准部署环境：Rocky Linux 9.2（x86_64）+ Docker 24+ / Compose v2。**
脚本是通用 POSIX shell，Debian / Ubuntu / macOS 也能跑（只需把 `dnf` 换成 `apt`）。

---

## 0. Rocky Linux 9.2 部署（基准环境）

### 0.1 一次性准备

```bash
# ① 基础包
sudo dnf install -y tar curl

# ② Docker —— ⚠️ Rocky 9 默认仓库里没有 docker-ce，要加 Docker 官方的 centos 仓库
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker

# ③ 让当前用户免 sudo 用 docker（改完要重新登录，或先 newgrp docker）
sudo usermod -aG docker "$USER"

# ④ 放行端口 —— ⚠️ Rocky 默认开 firewalld，不做这步「本机 curl 通、外部一律不通」
sudo firewall-cmd --permanent --add-port=8000/tcp
sudo firewall-cmd --reload
```

两个 Rocky / RHEL 专属坑，**本仓库已经处理好**，但你得知道它们存在：

- **SELinux 默认 Enforcing**：容器的绑定挂载若不带 SELinux 标签，容器内会 `EACCES` /
  `Permission denied` 读不到挂载目录。现在检查点用**命名卷** `aiseclab-data`（由 Docker 管理，
  不经过 SELinux 路径标签），这个问题已经不存在；如果你改回 `./data` 绑定挂载，
  记得加 **`:z`**（Debian / Ubuntu / Docker Desktop 会忽略它，无副作用）。
- **firewalld 默认开启**：`start.sh` 起完服务后会检测到 firewalld 正在跑，并打印放行命令。

### 0.2 起服务

```bash
cd backend
cp .env.example .env     # 可选：填 JUDGE_*；不填也能起服务（见 §7）
./start.sh               # 自动选路：装了 Docker 走容器，没有则走本机 venv
```

- **停止**：`./stop.sh`
- 强制某条路径：`./start.sh --docker`（只走容器） / `./start.sh --local`（只走本机 Python）
- 端口默认 **8000**（改 `.env` 里的 `PORT`，或用 `PORT=8080 ./start.sh`）
- 健康检查：`curl http://127.0.0.1:8000/health`

### 0.3 不用 Docker 的话（裸机 venv）

Rocky 9 的**系统 `python3` 是 3.9**。这个后端已按 **3.9 语法逐文件校验通过**
（`ast.parse(..., feature_version=(3,9))` 全过，且 pydantic 模型里没有 PEP 604 的 `X | Y`），
所以 3.9 能直接跑 —— 代价是 pip 会自动选择兼容 3.9 的稍旧依赖版本。

想和 Docker 镜像里的 **3.11** 对齐（行为更一致），装一个 AppStream 里的独立解释器即可 ——
**注意别去改系统 `python3` 软链**（dnf 自己也依赖它，改了会把包管理器搞坏）：

```bash
dnf list available 'python3.1*'                    # 先看仓库里有哪些版本
sudo dnf install -y python3.11 python3.11-pip     # 装到 /usr/bin/python3.11，系统 python3 不动
./start.sh --local
```

`start.sh --local` 挑解释器的顺序是
`python3.13 → 3.12 → 3.11 → 3.10 → python3 → 3.9`（新版优先，且校验 ≥3.9 与能否建 venv），
也可以自己指定：

```bash
AISECLAB_PYTHON=/usr/bin/python3.11 ./start.sh --local
```

> 若 `python3 -m venv` 报 **`ensurepip is not available`** —— 这是 RHEL 系特有的（不是 Debian 那种
> `python3-venv` 缺包）：`sudo dnf install -y python3-pip` 即可。

---

## 1. Windows 免安装（电脑无需装 Python / Docker）

下载 `aiseclab-backend-standalone-win.zip`，解压后双击里面的 `run.bat`：

- 首次运行会用 `.env.example` 自动生成 `.env`；
- 自动起服务、等 `/health` 就绪后打印 App 要填的地址；
- `Ctrl+C` 停止。

> 已装 Python 也可以用：双击 `deploy.bat`（等价于 `python deploy.py`）。

---

## 2. 本机开发跑

```bash
cd backend
pip install -r requirements.txt
export JUDGE_BASE_URL="https://api.openai.com/v1"
export JUDGE_API_KEY="sk-xxxx"
export JUDGE_MODEL="gpt-4o-mini"
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

健康检查：`curl http://localhost:8000/health`

---

## 3. Docker 部署细节（云主机 / 服务器推荐）

```bash
cd backend
cp .env.example .env
docker compose up -d --build
```

- 容器以**非 root**（`appuser`）运行；
- 内置 `HEALTHCHECK`（每 30 秒探一次 `/health`，见 `Dockerfile`，`docker-compose.yml` 刻意不重复定义）；
- 默认内存上限 512M；
- `./data` 以 `:z` 标签绑定挂载到容器 `/app/data`（预留的报告落盘目录，当前后端为内存态，见 §10）。

对外端口取 `.env` 里的 `PORT`（默认 8000）。**生产环境务必在前面加反向代理 + HTTPS**（见 §5）。

---

## 4. 裸机部署（systemd，不用 Docker 时）

```bash
# /etc/systemd/system/aiseclab-backend.service
[Unit]
Description=AISecLab Backend
After=network.target

[Service]
WorkingDirectory=/opt/aiseclab/backend
# 用你实际装的那个解释器；Rocky 9 系统自带 /usr/bin/python3 是 3.9，也能用
ExecStart=/opt/aiseclab/backend/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
EnvironmentFile=/opt/aiseclab/backend/.env
Restart=on-failure
User=aiseclab

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now aiseclab-backend
# Rocky 上还要放行 firewalld 与（若开了 SELinux 且服务要写目录）相应标签
```

---

## 5. 反向代理 + HTTPS（公网暴露必须）

nginx 示例（certbot 申请免费证书）：

```nginx
server {
    listen 443 ssl;
    server_name api.your-domain.com;
    ssl_certificate     /etc/letsencrypt/live/api.your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.your-domain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # SSE 进度流必须关缓冲，否则进度会攒到最后一次性吐出
        proxy_set_header Connection '';
        proxy_http_version 1.1;
        chunked_transfer_encoding off;
        proxy_read_timeout 3600s;
        proxy_buffering off;
    }
}
```

```bash
# Rocky：先装 nginx 与 certbot（EPEL）
sudo dnf install -y epel-release
sudo dnf install -y nginx certbot python3-certbot-nginx
sudo systemctl enable --now nginx
sudo certbot --nginx -d api.your-domain.com
# 别忘了放行 443
sudo firewall-cmd --permanent --add-service=https && sudo firewall-cmd --reload
```

---

## 6. 端侧怎么连

安卓端在「设置 → 测试后端」里填后端地址，之后：

- 创建任务：`POST /v1/sessions`（body 带被测模型配置与裁判模型 `judge`，仅本次任务内存留存）
- 进度：`GET /v1/sessions/{id}/stream`（SSE）
- 报告：`GET /v1/reports/{id}`

被测模型密钥由端侧在创建任务时传入，**后端只在任务生命周期内存放（内存 dict），
不落 KMS、不落盘、重启即失**。

接口清单：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/health` | 健康检查（不需要鉴权） |
| `GET` | `/docs` | Swagger UI 接口预览（不需要鉴权） |
| `POST` | `/v1/sessions` | 创建/续跑测评任务 |
| `GET` | `/v1/sessions/{sid}/stream` | SSE 进度流 |
| `GET` | `/v1/reports/{sid}` | 取报告 JSON |
| `GET` | `/v1/sessions/incomplete` | 列出可续跑的会话 |
| `DELETE` | `/v1/sessions/{sid}/checkpoint` | 丢弃续跑检查点 |
| `POST` | `/v1/judge` | 单独问一次裁判 |
| `POST` | `/v1/attacks/generate` | 单独生成攻击用例 |

---

## 7. 裁判模型：可以不填

服务端 `.env` 的 `JUDGE_BASE_URL` / `JUDGE_MODEL` / `JUDGE_API_KEY` **不填也能起服务**
（`/health` 正常）。此时裁判由客户端在 `POST /v1/sessions` 的请求体里带（BYOK 委托）——
后端**优先用请求体里的裁判**，没有才回退到服务器环境变量。**两者皆无才返回 503。**

这是故意设计：避免空配置静默失败，让「谁没配」在响应里一眼可见。

---

## 8. 安全提醒

- 公网必须 HTTPS，否则密钥与回答明文传输。
- **建议开鉴权**：在 `.env` 设 `API_TOKEN`，除 `/health` `/docs` `/openapi.json` `/redoc`
  外所有接口都要 `Authorization: Bearer <token>`。共享 / 公网服务器一定要开，
  否则谁都能调你的裁判模型额度。中间件是原始 ASGI 实现，**不缓冲响应体，SSE 长连接不受影响**。
- 裁判模型密钥放服务器环境变量（`.env` / systemd `EnvironmentFile`），别写进镜像、别提交仓库。
- `.env` 已在 `.gitignore` / `.dockerignore` 里，仓库只提交 `.env.example`（占位值）。

---

## 9. 比赛 / 对方提供服务器：最简交付

把整个 `backend/` 目录给对方，只需三步：

```bash
cd backend
cp .env.example .env
docker compose up -d --build
```

交付清单：

```
backend/
├── Dockerfile
├── docker-compose.yml
├── start.sh            # 一键起（自动识别 docker compose / docker-compose）
├── stop.sh             # 一键停
├── deploy.py           # 无 Docker 时的本机路径（跨平台，Python 3.9+）
├── requirements.txt
├── .env.example        # 让对方复制成 .env 填值
├── app/                # 全部源码
└── README.md           # 本文件
```

---

## 10. 常见问题（Rocky Linux 9.2 专项）

**Q：`docker compose up` 报 `permission denied` 连不上 Docker。**
当前用户不在 docker 组：`sudo usermod -aG docker $USER`，**重新登录**（或先 `newgrp docker`）后重试。

**Q：宿主机 `curl localhost:8000/health` 通，但别的机器 / 手机连不上。**
**firewalld 没放行端口**（Rocky 默认开着）：
`sudo firewall-cmd --permanent --add-port=8000/tcp && sudo firewall-cmd --reload`。
`start.sh` 会检测到 firewalld 在跑并把这行打给你。

**Q：容器日志报 `Permission denied` / `EACCES`，读不到 `/app/data`。**
两种可能：
1. 你改回过 `./data` 绑定挂载但漏了 SELinux 标签 —— 本仓库默认用**命名卷** `aiseclab-data`，
   不经过 SELinux 路径标签；若坚持用宿主机目录，补 `:z` 后 `docker compose up -d --force-recreate`。
   排查现场：`sudo ausearch -m avc -ts recent`。
2. 宿主目录属主不是容器内的 `appuser`（Ubuntu 上用 `sudo` 起时 `./data` 属主是 root）——
   这正是改用命名卷的原因。

**Q：`python3 -m venv` 报 `ensurepip is not available`。**
这是 RHEL 系特有的（Debian 那边缺的是 `python3-venv` 包）：
`sudo dnf install -y python3-pip`。或者走 Docker：`./start.sh --docker`。

**Q：`./start.sh --local` 说解释器低于 3.9，或我根本不想要 3.9。**
装一个 AppStream 里的独立解释器（**不要动系统 `python3` 软链**，dnf 依赖它）：
`sudo dnf install -y python3.11 python3.11-pip`，然后
`AISECLAB_PYTHON=/usr/bin/python3.11 ./start.sh --local`。
若 `dnf list available 'python3.1*'` 里没有 3.11，先把系统更到最新 9.x，或直接用 Docker。

**Q：`docker: command not found`，但 `dnf install docker` 装不上需要的组件。**
Rocky 9 默认仓库没有 docker-ce，见 §0.1 第 ② 步加 Docker 官方 centos 仓库。
或者改用 podman（AppStream 自带）：`sudo dnf install -y podman`，用
`podman compose` / `podman-compose` 替代 `docker compose`。

**Q：`./start.sh` 报 `/usr/bin/env: 'bash\r': No such file or directory`。**
脚本被 checkout 成了 CRLF。仓库 `.gitattributes` 已锁 `*.sh eol=lf`；
若你手动拷过文件，`sed -i 's/\r$//' start.sh stop.sh` 修一下。

**Q：浏览器打开 `http://<地址>:8000/` 是 404，是不是没起来？**
不是。根路径本来就没有页面 —— 这是 API 服务，不是网站。
- 判断存活：`curl http://<地址>:8000/health`
- 看接口、手动试调：**`http://<地址>:8000/docs`**（Swagger UI，免鉴权）
- 正式客户端：安卓 App

**Q：报告存在哪？重启之后历史没了。**
当前后端是**内存态**：任务与报告都放在内存里，进程重启即失。
`./data` 是带 `:z` 标签的绑定挂载预留目录（用于后续报告落盘），现在不会写入任何东西。

**Q：App 填了地址连不上。**
① 手机与后端要在同一网段；② 放行端口（见上面 firewalld 那条）；
③ 别填 VPN / 虚拟网卡的地址 —— `start.sh` 打印的是各网卡地址，标了 `[私网]` 的那条才是。
