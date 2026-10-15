"""
AISecLab 后端入口：FastAPI 路由 + SSE 进度流。

端点（与 docs/端后端架构方案.md §3 一致）：
- POST /v1/sessions              创建测试任务（被测模型 key 仅本次任务内存留存，不持久化）
- GET  /v1/sessions/{id}/stream   SSE 进度流（progress / done / error）
- GET  /v1/reports/{id}           拉完整报告 JSON
- POST /v1/judge                   单条交裁判（端侧规则预筛不确定时）
- POST /v1/attacks/generate       生成攻击用例
- GET  /v1/sessions/incomplete     列出**可续跑**的会话（断点续测）
- DELETE /v1/sessions/{id}/checkpoint  丢掉某个会话的断点
- GET  /health                    存活探针

断点续测（2026-10-15）：`POST /v1/sessions` 的请求体带 `resumeFrom=<上次 sid>` 时，
后端从检查点里把**已完成用例的结果直接复用、不重跑**，只补跑剩下的，合并成一份完整报告，
并沿用同一个 sessionId（一个会话一个检查点文件，见 checkpoint.py）。

裁判模型配置从环境变量读取（平台自有，不进代码）：JUDGE_BASE_URL / JUDGE_API_KEY / JUDGE_MODEL。
可选 API_TOKEN：设置后除 /health 外的所有接口都需 `Authorization: Bearer <token>`。
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel

from .attack import generate_cases
from .checkpoint import DONE, FAILED, RUNNING, Checkpoint, CheckpointStore
from .engine import run_session
from .judge import judge
from .llm_client import ChatClient
from .models import (
    Dimension,
    ModelProfile,
    Report,
    SessionRequest,
    TestCase,
    TestResult,
)


# 断点检查点。**模块级构造**（`CheckpointStore` 本身不建目录，见其 __init__ 注释），
# 默认落在 <cwd>/checkpoints —— 服务从 backend/dist 起（tools/start-backend.py 定的），
# 所以实际路径是 backend/dist/checkpoints。
STORE = CheckpointStore()


@asynccontextmanager
async def lifespan(_app: "FastAPI"):
    """启动收尾：把上次进程留下的 `running` 检查点标成 `interrupted`。

    为什么必须做：进程被外部杀掉时来不及写收尾，磁盘上留下的仍是 `running`。
    不刷新的话「继续上次测试」列表会显示一个**早已不存在的进程还在跑**——状态要对用户说真话。
    """
    n = STORE.sweep_running()
    if n:
        logger.warning("[main] 启动扫描：%d 个会话被上次退出中断，已标记为可续跑", n)
    yield


app = FastAPI(title="AISecLab Backend", version="0.1.0", lifespan=lifespan)

logger = logging.getLogger(__name__)

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


def _guard_env() -> dict:
    """护栏相关环境变量。缺 BASE_URL/MODEL 时视为未配护栏（判定退化为纯规则层）。"""
    return {
        "base_url": (os.environ.get("GUARD_BASE_URL") or "").strip(),
        "api_key": (os.environ.get("GUARD_API_KEY") or "").strip(),
        "model": (os.environ.get("GUARD_MODEL") or "").strip(),
        "threshold": float(os.environ.get("GUARD_THRESHOLD", "0.5")),
        "review_band": float(os.environ.get("GUARD_REVIEW_BAND", "0.1")),
    }


def _build_guard(req: Optional[SessionRequest] = None):
    """构建护栏（与端上 GuardMode 对应）。

    - 未配 GUARD_BASE_URL/GUARD_MODEL → None（判定退化为纯规则层，与旧行为一致）；
    - GUARD_MODE=user_llm → 用裁判模型当分类器（LlmSafetyGuard）；
    - 默认 → HttpSafetyGuard 指向 OpenAI 兼容的护栏端点。
    返回 (guard, threshold, review_band)。调用方负责 close 里挂着 client 的护栏。
    """
    cfg = _guard_env()
    if not cfg["base_url"] or not cfg["model"]:
        return None, cfg["threshold"], cfg["review_band"]

    mode = (os.environ.get("GUARD_MODE") or "http_service").strip().lower()
    if mode == "user_llm":
        from .guard import LlmSafetyGuard
        base = _judge_profile(req)  # 复用裁判端点
        client = ChatClient(base)
        return LlmSafetyGuard(client, base.model), cfg["threshold"], cfg["review_band"]

    from .guard import HttpSafetyGuard
    profile = ModelProfile(
        id="guard", name=os.environ.get("GUARD_NAME", "guard"),
        baseUrl=cfg["base_url"], apiKey=cfg["api_key"], model=cfg["model"],
    )
    client = ChatClient(profile)
    return HttpSafetyGuard(client, cfg["base_url"]), cfg["threshold"], cfg["review_band"]


def _guard_resume(cp: "Checkpoint", req: SessionRequest) -> None:
    """续跑的三道守卫。抽成独立函数是为了能在验收脚本里**直接断言**这几条判据，
    而不必真的起一个后台任务（`create_session` 会 `asyncio.create_task`，在单测里难收尾）。

    三道都拒绝得**说清原因**：这三件事都能单独把"续跑"变成一份假报告，不能只回一句 400。
    """
    if cp.state == DONE:
        raise HTTPException(409, "该会话已跑完（%d/%d 条），无需续跑；要重跑请新建一次测试"
                            % (cp.completed, cp.total))
    if sessions.get(cp.sid, {}).get("running"):
        # 同一个 sid 起两个 _run 会**双跑同一批用例**（各写各的结果、互相覆盖检查点），
        # 烧的是真 token，所以必须挡住。
        raise HTTPException(409, "该会话仍在运行中，不能重复续跑")
    # 换过被测模型就不许续跑：复用旧结果会把两个模型的结论混进同一份报告（假证据）。
    # 只比 model 名、不比 baseUrl —— 同一模型换地址（局域网 IP ↔ adb reverse）是常规操作。
    if cp.target_model and req.target.model and cp.target_model != req.target.model:
        raise HTTPException(
            409,
            "不能续跑：上次那轮跑的是「%s」，本次配的是「%s」。"
            "沿用上次的判定结果会把两个模型的结果混进同一份报告，"
            "换模型请重新开始一轮测试。" % (cp.target_model, req.target.model),
        )


@app.post("/v1/sessions")
async def create_session(req: SessionRequest):
    """建会话。带 `resumeFrom` 时=**断点续测**：沿用那个 sid，检查点里已完成的结果直接复用。

    为什么沿用同一个 sid 而不是新建：一个会话对应一个检查点文件，续跑只是把同一份文件
    接着写。这样反复续跑不会攒下一堆内容互相覆盖的副本，`GET /v1/reports/{sid}` 也仍然有效。
    """
    resume = (req.resumeFrom or "").strip()
    if resume:
        cp = STORE.load(resume)
        if cp is None:
            # 端点要给出可复现的原因：文件被清过、sid 打错、或服务换过 CHECKPOINT_DIR。
            raise HTTPException(404, "找不到可续跑的检查点：%s（可能已跑完并被清理，或不在本机）" % resume)
        _guard_resume(cp, req)
        sid = resume
    else:
        sid = str(time.time_ns())

    q: asyncio.Queue = asyncio.Queue()
    # 优化点④：长驻服务内存防护。sessions 永不清理会无限增长，超过上限淘汰最旧会话。
    if len(sessions) >= 200:
        sessions.pop(next(iter(sessions)), None)
    sessions[sid] = {"queue": q, "report": None, "running": True}
    asyncio.create_task(_run(sid, req, q))  # 后台跑，请求立即返回 sessionId
    return {"sessionId": sid, "resumedFrom": resume or None}


async def _run(sid: str, req: SessionRequest, q: asyncio.Queue) -> None:
    judge_client = ChatClient(_judge_profile(req))
    attack_client = ChatClient(_attack_profile(req))
    guard, g_threshold, g_review_band = _build_guard(req)

    # ── 检查点（断点续测）──
    # 顺序刻意如此：**先把 client / guard 建起来再碰检查点**。裁判没配好（_judge_profile 抛 503）
    # 时不该动检查点分毫 —— 那种情况下用户什么都没跑到，上一轮的断点必须留在 interrupted 状态，
    # 修好配置再来续跑。放在后面就会把它写成 running/failed，反而更难认。
    cp = STORE.load(sid) or Checkpoint(sid=sid, created_at=time.time(), updated_at=time.time())
    reuse: dict = {}
    for fp, raw in list(cp.results.items()):
        try:
            reuse[fp] = TestResult.model_validate(raw)
        except Exception as e:  # noqa: BLE001
            # 单条读不回来就当没有（这一条重跑），不能让一条旧记录把整个续跑毁掉。
            logger.warning("[main] session %s 检查点里有一条结果读不回来，将重跑：%s", sid, e)
    cp.state = RUNNING
    cp.error = None
    # 记下这一轮跑的是哪个模型（续跑时据此拒绝"换了模型还复用旧结果"）。
    # `or` 而不是直接赋值：续跑时保持**上一次**的记录，才比得出来模型有没有换过。
    cp.target_model = cp.target_model or (req.target.model or "")
    cp.judge_model = cp.judge_model or (req.judge.model if req.judge else "")
    STORE.save(cp)
    if reuse:
        logger.info("[main] session %s 断点续测：%d 条已有结果将直接复用（不重跑）", sid, len(reuse))

    def on_plan(fps) -> None:
        """用例集合确定后回调一次。把检查点校准到**本轮真正要跑的集合**。

        用例集合会变（用户改了维度、开关了越狱变体集），所以必须校准：
        留着上一轮的旧指纹会让 `completed / total` 对不上，文件也会一直驮着没用的数据。
        """
        keep = set(fps)
        stale = [k for k in cp.results if k not in keep]
        for fp in stale:
            cp.results.pop(fp, None)
        cp.expected = list(fps)
        cp.total = len(fps)
        STORE.save(cp)
        if stale:
            logger.info("[main] session %s 用例集合变化：剔除 %d 条不再需要的旧结果", sid, len(stale))

    def on_case_done(tr: TestResult, fp: str) -> None:
        """**每出一条就落盘**（含复用与失败两条分支）。

        这是断点续测的全部意义所在：等整轮跑完再写，中途被杀就一条都留不下。
        """
        cp.results[fp] = tr.model_dump()
        STORE.save(cp)

    async def on_progress(done, total, dim, tokens=None, message=None):
        await q.put(
            json.dumps(
                {
                    "event": "progress",
                    "completed": done,
                    "total": total,
                    "dimension": dim.value,
                    # 累计 token 用量（被测 + 裁判 + 攻击），端上据此实时显示消耗
                    "tokens": tokens or {},
                    # 该条用例的**终端摘要**（多行文本，见 engine.progress_message）。
                    # 端上按行拆开显示。没有它，委托后端跑时终端里只有进度数字、
                    # 看不到任何模型回答 —— 用户 2026-10-13 反馈的正是这个问题。
                    "message": message or "",
                }
            )
        )

    try:
        report = await run_session(req, judge_client, attack_client, on_progress,
                                   guard, g_threshold, g_review_band,
                                   on_case_done=on_case_done, on_plan=on_plan, reuse=reuse)
        sessions[sid]["report"] = report
        cp.state = DONE
        cp.error = None
        STORE.save(cp)
        await q.put(json.dumps({"event": "done", "reportId": sid}))
    except Exception as e:  # noqa: BLE001
        # 以前这里只往 SSE 塞一句 message，服务端**不留任何日志** ——
        # 端上只看到「连接失败」时，服务端也查不到是哪个上游、什么栈。见 2026-10-11 排查。
        logger.exception("session %s 运行失败：%s: %s", sid, type(e).__name__, e)
        # 失败要把原因落进检查点：端上「继续上次测试」列表里能直接告诉用户上次是怎么断的。
        # 注意 state 写 failed（∈ INCOMPLETE_STATES）而不是 done —— 它**是可以续跑的**，
        # 已完成的那几十条都还在 results 里。
        cp.state = FAILED
        cp.error = "%s: %s" % (type(e).__name__, e)
        STORE.save(cp)
        await q.put(json.dumps({"event": "error", "message": str(e)}))
    finally:
        sessions.get(sid, {})["running"] = False
        await judge_client.close()
        await attack_client.close()
        if guard is not None:
            await guard.close()


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


@app.get("/v1/sessions/incomplete")
async def list_incomplete():
    """列出**可续跑**的会话（断点续测的入口）。

    ⚠️ 不含已跑完的（`done`）：那些没有"续"的必要，端上要的正是"哪些没跑完"。
    返回的是轻量摘要（不含 50 条结果本身 —— 几百 KB 不该驮在列表接口上），
    端上点"继续"时用 `sessionId` 填 `resumeFrom` 重建请求。
    """
    return {"sessions": [cp.summary() for cp in STORE.list_incomplete()]}


@app.delete("/v1/sessions/{sid}/checkpoint")
async def drop_checkpoint(sid: str):
    """丢掉某个会话的断点（用户放弃续跑）。正在跑的会话不许丢 —— 它还会继续往里写。"""
    if sessions.get(sid, {}).get("running"):
        raise HTTPException(409, "该会话仍在运行中，不能丢掉它的断点")
    if STORE.load(sid) is None:
        raise HTTPException(404, "没有这个会话的断点：%s" % sid)
    STORE.delete(sid)
    sessions.pop(sid, None)
    return {"deleted": sid}


class JudgeBody(BaseModel):
    testCase: TestCase
    response: str


@app.post("/v1/judge")
async def judge_one(body: JudgeBody):
    """单条交裁判（端侧规则预筛不确定时调用）。配了护栏则走护栏优先。"""
    jc = ChatClient(_judge_profile())
    guard, g_th, g_band = _build_guard()
    try:
        return await judge(jc, body.testCase, body.response, guard, g_th, g_band)
    finally:
        await jc.close()
        if guard is not None:
            await guard.close()


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
