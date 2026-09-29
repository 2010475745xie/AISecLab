# AISecLab 后端部署说明

FastAPI 后端：调被测模型 / 攻击模型 / 裁判模型，跑规则 + LLM 三角色裁判，输出报告 JSON 供端侧渲染。

## 1. 本地跑（开发）

```bash
cd backend
pip install -r requirements.txt
export JUDGE_BASE_URL="https://api.openai.com/v1"
export JUDGE_API_KEY="sk-xxxx"        # 裁判模型 key（平台自有）
export JUDGE_MODEL="gpt-4o-mini"
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

健康检查：`curl http://localhost:8000/health`

## 2. 服务器部署（Docker，推荐）

写一份 `.env`（不要提交进仓库）：

```
JUDGE_BASE_URL=https://你的裁判模型端点/v1
JUDGE_API_KEY=sk-xxxx
JUDGE_MODEL=你的裁判模型名
```

启动：

```bash
cd backend
docker compose up -d --build
```

容器暴露 8000。**生产环境务必在前面加反向代理 + HTTPS**（见下），不要让 8000 裸奔到公网。

## 3. 服务器裸机部署（systemd，不用 Docker 时）

```bash
# /etc/systemd/system/aiseclab-backend.service
[Unit]
Description=AISecLab Backend
After=network.target

[Service]
WorkingDirectory=/opt/aiseclab/backend
ExecStart=/opt/aiseclab/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
EnvironmentFile=/opt/aiseclab/backend/.env
Restart=on-failure
User=aiseclab

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now aiseclab-backend
```

## 4. 反向代理 + HTTPS（公网暴露必须）

nginx 示例（certbot 申请免费证书）：

```nginx
server {
    listen 443 ssl;
    server_name api.your-domain.com;
    ssl_certificate /etc/letsencrypt/live/api.your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.your-domain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # SSE 需要关缓冲
        proxy_set_header Connection '';
        proxy_http_version 1.1;
        chunked_transfer_encoding off;
        proxy_read_timeout 3600s;
        proxy_buffering off;
    }
}
```

```bash
sudo certbot --nginx -d api.your-domain.com
```

## 5. 端侧怎么连

安卓端 `ProfileRepository` 增加「后端地址」配置项，改为：
- 创建任务：`POST https://api.your-domain.com/v1/sessions`（body 带被测模型 key，仅本次任务内存留存）
- 进度：`GET /v1/sessions/{id}/stream`（EventSource 读 SSE）
- 报告：`GET /v1/reports/{id}`

被测模型 key 由端侧在创建任务时传入，**后端只在任务生命周期内存放（sessions dict），不落 KMS、重启即失**——符合架构方案 §4 方案 2 的合规精神。

## 6. 安全提醒

- 公网必须 HTTPS，否则 key 与回答明文传输。
- 建议加一层简单鉴权（nginx basic auth 或下游中间件 token），避免谁都能调你的裁判模型。
- 裁判模型 key 在服务器环境变量里，用 `.env` / systemd EnvironmentFile 管理，别写进镜像或提交仓库。
- 云函数（Cloudflare Workers / 云函数）变体：短任务友好，但多轮侵蚀 + SSE 长连接受超时限制，需把 session 状态外置（Redis）并改成分段轮询。当前骨架是常驻进程形态，更适合自托管虚拟机。

## 7. 比赛 / 对方提供服务器，只交 docker-compose（最简交付）

对方拿到整个 `backend/` 目录后只需：

```bash
cd backend
cp .env.example .env          # 填入 JUDGE_BASE_URL / JUDGE_MODEL / JUDGE_API_KEY（裁判模型）
docker compose up -d --build  # 会自动按 Dockerfile 构建镜像并起服务
```

要点：

- **裁判模型必填**：`JUDGE_BASE_URL` 与 `JUDGE_MODEL` 不填，服务能起（`/health` 正常），但一调裁判就返回 503 并报"裁判模型未配置"。这是故意设计——避免空配置静默失败。
- **鉴权建议开**：在 `.env` 设 `API_TOKEN`，除 `/health` 外所有接口都要 `Authorization: Bearer <token>`。共享/公网服务器一定要开，否则谁都能调你的裁判模型额度。中间件是原始 ASGI 实现，**不缓冲响应体，SSE 长连接不受影响**。
- **容器非 root 运行**，默认 512M 内存上限（单用户足够，OOM 就调到 1G）。
- **健康检查**内置：`docker compose ps` 看 STATUS 是否为 `healthy`；或 `curl http://<服务器>:8000/health`。
- **被测模型 key 不进服务器配置**：端侧建任务时通过 `POST /v1/sessions` 的 body 传入，后端只在任务生命周期内存放、不落盘（重启即失）。

交付清单（把这几样给对方即可）：

```
backend/
├── Dockerfile
├── docker-compose.yml
├── requirements.txt
├── .env.example        # 让对方复制成 .env 填值
├── app/                # 全部源码
└── README.md           # 本文件
```

> 若比赛方要求固定端口（如 80），改 `docker-compose.yml` 的 `"${PORT:-8000}:8000"` 左边即可；若要求走域名 HTTPS，在他们侧反代到容器 8000，SSE 务必 `proxy_buffering off`（见 §4）。
