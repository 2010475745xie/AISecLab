"""
AISecLab 后端入口：FastAPI 路由 + SSE 进度流。

端点（与 docs/端后端架构方案.md §3 一致）：
- POST /v1/sessions              创建测试任务（被测模型 key 仅本次任务内存留存，不持久化）
- GET  /v1/sessions/{id}/stream   SSE 进度流（progress / done / error）
- GET  /v1/reports/{id}           拉完整报告 JSON
- POST /v1/judge                   单条交裁判（端侧规则预筛不确定时）
- POST /v1/attacks/generate       生成攻击用例
- GET  /health                    存活探针

裁判模型配置从环境变量读取（平台自有，不进代码）：JUDGE_BASE_URL / JUDGE_API_KEY / JUDGE_MODEL。
可选 API_TOKEN：设置后除 /health 外的所有接口都需 `Authorization: Bearer <token>`。
"""
from __future__ import annotations

import asyncio
import json
import os
import time
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel

from .attack import generate_cases
from .engine import run_session
from .judge import judge
from .llm_client import ChatClient
from .models import Dimension, ModelProfile, Report, SessionRequest, TestCase


app = FastAPI(title="AISecLab Backend", version="0.1.0")

# ── 可选 token 鉴权（比赛/公网共享服务器建议设置 API_TOKEN）──────────────
# 用原始 ASGI 中间件：只校验请求头，不包裹响应体，SSE 长连接不会被缓冲。
EXPECTED_TOKEN = (os.environ.get("API_TOKEN") or "").strip()
_PUBLIC_PATHS = {"/health", "/openapi.json", "/docs", "/redoc"}


class _TokenAuthMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") == "http":
            path = scope.get("path", "")
            if EXPECTED_TOKEN and path not in _PUBLIC_PATHS:
                headers = dict(scope.get("headers", []))
                auth = headers.get(b"authorization", b"").decode("latin-1")
                if auth != f"Bearer {EXPECTED_TOKEN}":
                    await send(
                        {
                            "type": "http.response.start",
                            "status": 401,
                            "headers": [(b"content-type", b"application/json")],
                        }
                    )
                    await send(
                        {
                            "type": "http.response.body",
                            "body": b'{"detail":"unauthorized: missing or wrong API_TOKEN"}',
                        }
                    )
                    return
        await self.app(scope, receive, send)


app.add_middleware(_TokenAuthMiddleware)


sessions: dict = {}  # sid -> {"queue": asyncio.Queue, "report": Report | None}


def _judge_profile(req: Optional["SessionRequest"] = None) -> ModelProfile:
    # BYOK 委托场景：客户端在请求体显式带裁判模型（比赛/共享服务器通常没预置裁判）。有就优先用。
    if req is not None and req.judge is not None:
        j = req.judge
        if j.baseUrl.strip() and j.model.strip():
            return j
    base_url = (os.environ.get("JUDGE_BASE_URL") or "").strip()
    model = (os.environ.get("JUDGE_MODEL") or "").strip()
    if not base_url or not model:
        raise HTTPException(
            503,
            "裁判模型未配置：请在请求体带上 judge（被测模型自带裁判，BYOK 委托），"
            "或在环境变量设置 JUDGE_BASE_URL 与 JUDGE_MODEL（可选 JUDGE_API_KEY / JUDGE_NAME）",
        )
    return ModelProfile(
        id="judge",
        name=os.environ.get("JUDGE_NAME", "judge"),
        baseUrl=base_url,
        apiKey=os.environ.get("JUDGE_API_KEY", ""),
        model=model,
    )


def _attack_profile(req: SessionRequest) -> ModelProfile:
    return req.aux or _judge_profile(req)


@app.post("/v1/sessions")
async def create_session(req: SessionRequest):
    sid = str(time.time_ns())
    q: asyncio.Queue = asyncio.Queue()
    sessions[sid] = {"queue": q, "report": None}
    asyncio.create_task(_run(sid, req, q))  # 后台跑，请求立即返回 sessionId
    return {"sessionId": sid}


async def _run(sid: str, req: SessionRequest, q: asyncio.Queue) -> None:
    judge_client = ChatClient(_judge_profile(req))
    attack_client = ChatClient(_attack_profile(req))

    async def on_progress(done, total, dim):
        await q.put(
            json.dumps(
                {
                    "event": "progress",
                    "completed": done,
                    "total": total,
                    "dimension": dim.value,
                }
            )
        )

    try:
        report = await run_session(req, judge_client, attack_client, on_progress)
        sessions[sid]["report"] = report
        await q.put(json.dumps({"event": "done", "reportId": sid}))
    except Exception as e:  # noqa: BLE001
        await q.put(json.dumps({"event": "error", "message": str(e)}))
    finally:
        await judge_client.close()
        await attack_client.close()


@app.get("/v1/sessions/{sid}/stream")
async def stream(sid: str):
    s = sessions.get(sid)
    if not s:
        raise HTTPException(404, "session not found")
    q = s["queue"]

    async def gen():
        while True:
            item = await q.get()
            yield f"data: {item}\n\n"
            if '"event":"done"' in item or '"event":"error"' in item:
                break

    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/v1/reports/{sid}")
async def get_report(sid: str):
    s = sessions.get(sid)
    if not s or not s["report"]:
        raise HTTPException(404, "report not ready")
    return s["report"]


class JudgeBody(BaseModel):
    testCase: TestCase
    response: str


@app.post("/v1/judge")
async def judge_one(body: JudgeBody):
    """单条交裁判（端侧规则预筛不确定时调用）。"""
    jc = ChatClient(_judge_profile())
    try:
        return await judge(jc, body.testCase, body.response)
    finally:
        await jc.close()


class GenBody(BaseModel):
    dimension: Dimension
    strategy: str = ""
    count: int = 6


@app.post("/v1/attacks/generate")
async def gen(body: GenBody):
    """生成攻击用例（路线图第 5 条）。攻击生成复用裁判模型，可另配 AUX。"""
    ac = ChatClient(_judge_profile())
    try:
        return await generate_cases(ac, body.dimension, body.count)
    finally:
        await ac.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
