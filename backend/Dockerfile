# AISecLab 后端镜像：python:3.11-slim，非 root 运行
#
# 构建 / 启动：docker compose up -d --build   （或 docker build -t aiseclab-backend .）
# 单阶段构建，只装运行时依赖；测试脚本与本地数据由 .dockerignore 排除，不进镜像。
FROM python:3.11-slim

# 建一个系统用户跑服务，降低容器被攻破后的影响面
RUN groupadd --system appgroup && useradd --system --gid appgroup --create-home appuser

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# 数据目录：docker-compose.yml 会把宿主的 ./data 绑定挂载到这里。
# ⚠️ 必须在切 USER 之前 chown —— 否则宿主目录属主不是 appuser 时，容器里写入会 EACCES。
#    这个问题只在 Linux 上暴露（Windows / WSL 的绑定挂载默认放行权限），所以本机测不出来。
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 不写 __pycache__、输出不缓冲（否则 SSE 进度会被缓冲住，看起来像卡死）
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000

EXPOSE 8000

# 健康检查：唯一实现放在这里，docker-compose.yml 不再重复定义（避免两处口径漂移）。
# 判定：GET /health 返回 200。
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status == 200 else 1)"

USER appuser

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
