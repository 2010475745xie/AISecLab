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
from .checkpoint import (
    DONE, FAILED, INTERRUPTED, RUNNING, Checkpoint, CheckpointStore,
)
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

# SSE 心跳间隔（秒）。见 stream() 里 gen() 的注释：这两条事件之间的静默期可能长达一两分钟，
# 没有心跳，端上就分不清"模型正在慢慢答"和"连接早断了"。
# 取 15s：远小于端上的空闲读超时（BackendClient 里 60s），正常长跑不触发，真断线一分钟内就报错。
HEARTBEAT_SECONDS = 15


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
    # **必须留 task 引用**（2026-10-18）：没有它，停止一轮测试就只能等它自己跑完。
    # 用户点「停止并废弃」后，端上只是断开 SSE，后端 `_run` 照旧对着上游烧 token，
    # `running` 永远是 True ⇒ 续跑和丢弃断点全被 409 挡住，形成死锁。
    sessions[sid] = {"queue": q, "report": None, "running": True, "task": None}
    sessions[sid]["task"] = asyncio.create_task(_run(sid, req, q))
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
        """用例集合确定后回调一次。**最后一道「检查点 vs 本轮用例」对齐闸门**。

        ── 2026-10-16 修复（此前造成 79 条结果静默丢失）──
        旧实现把"不在本轮集合里"的旧结果**直接 pop 掉再落盘**，注释里叫"校准"，
        实际是**不可逆的数据销毁**：`CheckpointStore.save` 是原子写，pop 完就真没了。
        触发路径还极常见 —— 续跑时忘了勾「越狱变体集」（该开关**默认关**），
        本轮集合只有 50 条（不含 264 条变体），于是 79 条已跑完的结果被当场抹掉；
        用户回头把开关勾回去，那些结果已经回不来了（只能重烧 token 重跑）。

        现在的口径：**检查点是用户的资产，后端无权单方面删除。**
        发现集合对不上就**整轮拒绝**（抛异常 → `_run` 落到 `failed`，
        **检查点一个字节都不动**），把选择权交回用户：
          · 把配置改回与上次一致（通常就是勾上越狱变体集）→ 续跑，旧结果照常复用；
          · 确实想换一批用例 → 新建一轮（新 sid、新文件，各写各的，互不覆盖）。

        ⚠️ 反向（本轮是**超集**：用户多勾了一个维度）**不拦** —— 只增不减，
        旧结果全部仍在 `keep` 里，没有任何数据会丢，直接跑新的即可。
        """
        keep = set(fps)
        stale = sorted(k for k in cp.results if k not in keep)
        if stale:
            logger.error(
                "[main] session %s 拒绝续跑：本轮 %d 条用例与检查点 %d 条已有结果不一致，"
                "其中 %d 条不在本轮集合内", sid, len(fps), cp.completed, len(stale),
            )
            raise RuntimeError(
                "续跑配置与上次不一致，已中止本轮（**检查点原样保留，没丢任何数据**）：\n"
                "  本轮用例数   ：%d 条\n"
                "  检查点已完成 ：%d 条\n"
                "  对不上、会被丢弃：%d 条\n"
                "最常见的原因是漏勾了「越狱变体集」或「递归对抗」（这两个开关默认关闭）。\n"
                "请改回与上次一致的配置后重试续跑；确实要换一批用例，请新建一轮测试。"
                % (len(fps), cp.completed, len(stale))
            )
        cp.expected = list(fps)
        cp.total = len(fps)
        STORE.save(cp)

    def on_case_done(tr: TestResult, fp: str) -> None:
        """**每出一条就落盘**（含复用与失败两条分支）。

        这是断点续测的全部意义所在：等整轮跑完再写，中途被杀就一条都留不下。
        """
        cp.results[fp] = tr.model_dump()
        STORE.save(cp)

    async def on_progress(done, total, dim, tokens=None, message=None, resumed=None):
        # 往队列里放 **dict**（不是已序列化的字符串）：`stream()` 那边要读 `event` 字段判断收尾，
        # 序列化由它统一做，避免"在 JSON 字符串里按词匹配"这种一改格式就静默失效的判据。
        await q.put(
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
                # 累计「复用（未重跑、不计费）」条数。端上显示「进度 x/y（复用 a / 新跑 b）」，
                # 否则续跑时用户分不清是"从断点接着跑"还是"从头又烧了一遍 token"
                #（2026-10-16 用户就为此困惑过）。缺省 0 = 全新一轮。
                "resumed": int(resumed or 0),
            }
        )

    try:
        report = await run_session(req, judge_client, attack_client, on_progress,
                                   guard, g_threshold, g_review_band,
                                   on_case_done=on_case_done, on_plan=on_plan, reuse=reuse)
        sessions[sid]["report"] = report
        cp.state = DONE
        cp.error = None
        STORE.save(cp)
        await q.put({"event": "done", "reportId": sid})
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
        await q.put({"event": "error", "message": str(e)})
    finally:
        sessions.get(sid, {})["running"] = False
        # cancel 时这个 finally **也会进**（`asyncio.CancelledError` 是 BaseException，
        # 不归上面那个 `except Exception` 管）。此刻 task 已处于 cancelling 状态，
        # 里面的任何 await 都可能立刻再抛一次 —— 逐个兜住，别让"没关干净的 client"
        # 把一次正常的停止变成一条失败记录。
        for _closer in (judge_client.close, attack_client.close):
            try:
                await _closer()
            except BaseException:  # noqa: BLE001
                pass
        if guard is not None:
            try:
                await guard.close()
            except BaseException:  # noqa: BLE001
                pass


@app.get("/v1/sessions/{sid}/stream")
async def stream(sid: str):
    s = sessions.get(sid)
    if not s:
        raise HTTPException(404, "session not found")
    q = s["queue"]

    async def gen():
        """SSE 生成器。**带心跳**（2026-10-16 加）。

        为什么必须有心跳：这条流一次能连着跑一两个小时，而两条事件之间可能长时间没东西 ——
        单条用例要等被测模型出完整回答 + 裁判裁决，慢的时候一两分钟不出一个 progress 很正常。
        没有心跳时，端上（OkHttp）读到的是**纯静默**：TCP 连接被中间设备悄悄掐断
        （NAT 超时、手机切网、后端被杀）端上完全感知不到，表现就是"进度卡住不动、
        不报错、不重连"，唯一出路是用户手动停 —— 而用户不一定知道"停"不等于"白跑"。
        （2026-10-16 实测：22:42 之后整整 25 分钟零输出，界面停在 111/314。）

        心跳用 SSE 注释行（`: keepalive`）：规范里以 `:` 开头的行客户端**必须忽略**，
        所以端上的 `data:` 解析逻辑不用改，也不会被误当成一条 progress。

        ⚠️ 这里用 `asyncio.wait` 而**不是** `asyncio.wait_for`：后者超时时会 **cancel**
        那个取事件的 future，而 cancel 与"事件刚好在同一瞬间到达"之间存在竞态 ——
        万一被吃掉的是 `done`，端上就要一直傻等到读超时才报错（报告其实早就好了）。
        `asyncio.wait` 超时**不取消**，pending 直接留到下一轮接着等，一个事件都不会丢。
        """
        pending = None
        try:
            while True:
                if pending is None:
                    pending = asyncio.ensure_future(q.get())
                done, _ = await asyncio.wait({pending}, timeout=HEARTBEAT_SECONDS)
                if not done:
                    yield ": keepalive\n\n"
                    continue
                ev = pending.result()      # 队列里放的是 **dict**，在这里统一序列化
                pending = None
                yield f"data: {json.dumps(ev)}\n\n"
                # ⚠️ 收尾判据读的是**解析出来的 event 字段**，而不是在序列化后的字符串里找
                # `"event":"done"` —— 后者曾经是个真 bug：`json.dumps` 默认在冒号后加空格
                # （`"event": "done"`），字符串匹配**永远命中不了**，于是 done 之后这个流
                # 永不结束、连接与协程一直挂着，非等客户端自己断开才被回收。
                # 2026-10-17 被 backend/_verify_sse.py 的用例抓到（同一类坑：**别按词比**）。
                if isinstance(ev, dict) and ev.get("event") in ("done", "error", "cancelled"):
                    break
        finally:
            # 客户端断开（StreamingResponse 取消生成器）时别把取事件的 task 留成孤儿。
            if pending is not None:
                pending.cancel()

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


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


@app.post("/v1/sessions/{sid}/cancel")
async def cancel_session(sid: str):
    """**停止一轮正在跑的测试**（2026-10-18 新增）。

    为什么必须有这个接口：端上的「停止并废弃」只做两件事 —— 断开 SSE 连接、清掉本地状态。
    后端的 `_run` 任务对此毫不知情，会继续对着上游烧 token（实测 314 条能烧一个多小时），
    而 `sessions[sid]["running"]` 始终是 True ⇒
      · `POST /v1/sessions`（续跑）→ 409「该会话仍在运行中」
      · `DELETE .../checkpoint`（丢弃断点）→ 409「该会话仍在运行中」
    用户被彻底卡死：**"已经停止并废弃了，断点续测依然正在运行，且无法丢弃断点。"**

    三个动作缺一不可：
      ① `task.cancel()` —— 真正掐掉后台任务（不是只标记）；
      ② 检查点 `running → interrupted` —— 否则磁盘上永远停在 running，
         下次服务重启时 sweep 才能收拾，但在那之前「继续上次测试」里它仍是"正在运行"；
      ③ 往 SSE 队列塞一条 `cancelled` —— 还挂着的端上立刻收尾，不用等 12 秒心跳。
    """
    s = sessions.get(sid)

    if s is None:
        # 服务重启过 / 被 200 条上限淘汰 / sid 打错。磁盘上可能还留着检查点，
        # 那就顺手收拾干净并回一个**能看懂**的结果，而不是拿 404 把用户挡回去 ——
        # 客户端调 cancel 的本意是"我要停下/我要清理"，回 404 对它没有任何帮助。
        cp = STORE.load(sid)
        if cp is None:
            raise HTTPException(404, "没有这个会话：%s" % sid)
        if cp.state == RUNNING:
            cp.state = INTERRUPTED
            STORE.save(cp)
            return {"cancelled": False,
                    "note": "任务已不在内存中（服务重启过？），已把检查点标为 interrupted"}
        return {"cancelled": False, "note": "该会话已不在运行中（state=%s）" % cp.state}

    was_running = bool(s.get("running"))
    task = s.get("task")
    if task is not None and not task.done():
        task.cancel()
    s["running"] = False

    cp = STORE.load(sid)
    if cp is not None and cp.state == RUNNING:
        # 只在**还是 running** 时改：任务可能刚好在这一瞬间跑完了 DONE，
        # 把一个已完成的会话改成 interrupted 会让报告"消失"。
        cp.state = INTERRUPTED
        cp.error = None
        STORE.save(cp)

    q = s.get("queue")
    if q is not None:
        try:
            q.put_nowait({"event": "cancelled"})
        except asyncio.QueueFull:
            pass
    return {"cancelled": was_running, "checkpoint": "interrupted" if cp else None}


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
