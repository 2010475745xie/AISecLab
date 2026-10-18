"""SSE 进度流的三条契约验收 —— **纯本地，不碰任何真实模型、不花一分钱**。

跑法（必须用 venv 解释器，且 `-u`）：
    <venv>/Scripts/python.exe -u backend/_verify_sse.py

## 为什么要单独验这个

`GET /v1/sessions/{id}/stream` 是条**长连接**，一次能连着跑一两个小时，而两条事件之间
可能长时间没有任何东西 —— 单条用例要等被测模型出完整回答 + 裁判裁决，慢的时候一两分钟
不出一个 progress 很正常。没有心跳时端上读到的是**纯静默**：TCP 被中间设备悄悄掐断
（NAT 超时 / 手机切网 / 后端被杀）端上完全感知不到，界面就永远停在某个数字上。
2026-10-16 实测：22:42 之后整整 25 分钟零输出，UI 停在 111/314，用户唯一能做的是手动停。

钉住三件事：
  A. **空闲时会发 `: keepalive`**（SSE 注释行），且它**不是** `data:` 帧 ——
     端上只认 `data:`，心跳不会被误当成一条进度；
  B. progress 帧里带 `resumed`（累计复用条数），事件一条不丢，最后一条是 `done`，
     而且**已经躺在队列里的事件会立刻送出**（不会为了等心跳白拖一个周期）；
  C. **跨过一次心跳超时之后再到达的事件照样送得出去** —— 这条守的是实现里那个关键选择：
     心跳用 `asyncio.wait` 而**不是** `asyncio.wait_for`。后者超时会 cancel 掉那个
     "等事件"的 future，而被 cancel 的 future 之后即使 `put` 进来也永远送不出去
     （端上就会一直干等，其实报告早就好了）。

## 为什么不走 TestClient / httpx

2026-10-17 实测：本机 `fastapi.testclient` 走 httpx + anyio portal，读一条**永不结束**的
SSE 流会把进程卡死到被外部杀掉（exit 0x40010004），而且重定向到文件时 stdout 是块缓冲，
连"卡在哪一步"都看不到。所以这里**直接迭代 `main.stream(sid)` 返回的
`StreamingResponse.body_iterator`** —— 验的是同一段生成器代码，去掉 HTTP 客户端那一层噪音，
时序还完全可控（C 用例需要在事件循环里精确控制"投事件的时刻"）。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import main  # noqa: E402

_ok = 0
_bad = 0


def check(name: str, cond: bool, extra: str = "") -> None:
    global _ok, _bad
    if cond:
        _ok += 1
        print("  OK   %s" % name, flush=True)
    else:
        _bad += 1
        print("  FAIL %s %s" % (name, extra), flush=True)


def _register(sid: str, prefill: list | None = None) -> "asyncio.Queue":
    # 队列里放的是 **dict**（与 main.on_progress / _run 一致）：序列化由 stream() 统一做，
    # 这样收尾判据能直接读 `event` 字段，不必在 JSON 字符串里按词匹配。
    q: asyncio.Queue = asyncio.Queue()
    for item in prefill or []:
        q.put_nowait(item)
    main.sessions[sid] = {"queue": q, "report": None, "running": True}
    return q


def _payloads(chunks) -> list:
    """把分片里的 `data:` 帧解出来（注释行 / 空行自动跳过）。"""
    out = []
    for c in chunks:
        if c.startswith("data:"):
            out.append(json.loads(c[len("data:"):].strip()))
    return out


# ── A. 空闲心跳 + 响应头 ──────────────────────────────────────────────────
async def test_heartbeat() -> None:
    print("\n[A] 空闲心跳", flush=True)
    sid = "sse-idle"
    _register(sid)   # 队列**空着**：模拟"模型正在慢慢答"
    resp = await main.stream(sid)
    check("media_type = text/event-stream", resp.media_type == "text/event-stream", resp.media_type)
    check("Cache-Control: no-cache", resp.headers.get("cache-control") == "no-cache",
          resp.headers.get("cache-control"))
    check("X-Accel-Buffering: no（防反代把 SSE 缓冲住）",
          resp.headers.get("x-accel-buffering") == "no", resp.headers.get("x-accel-buffering"))

    it = resp.body_iterator
    t0 = time.monotonic()
    chunk = await asyncio.wait_for(it.__anext__(), timeout=10)
    dt = time.monotonic() - t0
    check("空闲一个心跳周期后收到第一片", chunk is not None, repr(chunk))
    check("第一片是 SSE 注释行 `: keepalive`", chunk.strip() == ": keepalive", repr(chunk))
    check("心跳**不是** data 帧（不会被端上当成一条进度）", not chunk.startswith("data:"))
    check("没有白等（≤ 3 个心跳周期）", dt <= main.HEARTBEAT_SECONDS * 3, "%.2fs" % dt)
    await it.aclose()


# ── B. 数据帧：resumed 字段、不丢事件、done 收尾 ──────────────────────────
async def test_frames() -> None:
    print("\n[B] 数据帧：resumed 字段与收尾", flush=True)
    sid = "sse-frames"
    _register(sid, prefill=[
        {"event": "progress", "completed": 177, "total": 314, "dimension": "security",
         "tokens": {"total": 123, "prompt": 100, "completion": 23, "calls": 4},
         "message": "↻ 续跑复用《X》", "resumed": 176},
        {"event": "progress", "completed": 178, "total": 314, "dimension": "security",
         "message": "✓ 《Y》", "resumed": 176},
        {"event": "done", "reportId": sid},
    ])
    resp = await main.stream(sid)
    it = resp.body_iterator

    chunks = []
    t0 = time.monotonic()
    async for c in it:
        chunks.append(c)
    dt = time.monotonic() - t0

    check("done 之后生成器自己结束（不会挂住）", True)
    check("已躺在队列里的事件**立刻**送出（没有为了等心跳白拖一个周期）",
          dt < main.HEARTBEAT_SECONDS, "%.2fs" % dt)
    got = _payloads(chunks)
    check("收到全部 3 条事件（一条不丢）", len(got) == 3, str([g.get("event") for g in got]))
    check("最后一条是 done", bool(got) and got[-1]["event"] == "done",
          str(got[-1:] if got else None))
    prog = [g for g in got if g["event"] == "progress"]
    check("每条 progress 都带 resumed", bool(prog) and all("resumed" in g for g in prog))
    check("resumed 原样透传（176）", bool(prog) and prog[0]["resumed"] == 176,
          str(prog[0].get("resumed") if prog else None))
    check("completed / total 原样透传",
          bool(prog) and prog[0]["completed"] == 177 and prog[0]["total"] == 314)
    check("tokens / message 也在（端上要显示消耗与终端摘要）",
          bool(prog) and prog[0]["tokens"]["total"] == 123 and prog[0]["message"].startswith("↻"))
    check("没有多余的心跳插进来", not any(c.strip() == ": keepalive" for c in chunks),
          str(chunks))


# ── C. 跨过一次心跳超时后到达的事件不被吞 ────────────────────────────────
async def test_after_timeout() -> None:
    print("\n[C] 边界：跨过一次心跳超时后到达的事件不被吞", flush=True)
    sid = "sse-after-timeout"
    q = _register(sid)
    resp = await main.stream(sid)
    it = resp.body_iterator

    chunks: list = []
    t0 = time.monotonic()

    async def feeder():
        # 等 1.6 个心跳周期：确保**已经发生过一次超时**（已经发出过 keepalive）之后再投事件，
        # 正好走一遍"pending 被保留、下一轮立刻取到"的那条路径。
        await asyncio.sleep(main.HEARTBEAT_SECONDS * 1.6)
        await q.put({"event": "done", "reportId": sid})

    task = asyncio.ensure_future(feeder())
    try:
        async for c in it:
            chunks.append(c)
            p = _payloads([c])
            if p and p[0].get("event") in ("done", "error"):
                break
    finally:
        if not task.done():
            task.cancel()

    elapsed = time.monotonic() - t0
    check("跨超时后的事件能送达（没被 wait_for 式的 cancel 吞掉）",
          any(g.get("event") == "done" for g in _payloads(chunks)), str(chunks))
    n_keep = sum(1 for c in chunks if c.strip() == ": keepalive")
    check("中间确实发过至少一次 keepalive（说明这条路径真的被走到了）", n_keep >= 1,
          "keepalive 次数 = %d" % n_keep)
    check("done 在超时之后才到（时序符合预期）", elapsed > main.HEARTBEAT_SECONDS,
          "%.2fs" % elapsed)


def _run(coro, timeout: float = 30.0) -> None:
    """跑一个用例；超时/异常都转成"失败"，不把进程挂死。"""
    try:
        asyncio.run(asyncio.wait_for(coro, timeout=timeout))
    except asyncio.TimeoutError:
        check("用例在 %.0fs 内完成" % timeout, False, "超时 —— 生成器挂住了")
    except Exception as e:  # noqa: BLE001
        check("用例没抛异常", False, "%s: %s" % (type(e).__name__, e))


def run() -> int:
    old_hb, old_sessions = main.HEARTBEAT_SECONDS, main.sessions
    main.HEARTBEAT_SECONDS = 1          # 15s → 1s：结论不变，测试快 15 倍
    main.sessions = {}
    try:
        _run(test_heartbeat())
        _run(test_frames())
        _run(test_after_timeout())
    finally:
        main.HEARTBEAT_SECONDS = old_hb
        main.sessions = old_sessions

    print("\n%s\n通过 %d / 失败 %d" % ("=" * 46, _ok, _bad), flush=True)
    return 1 if _bad else 0


if __name__ == "__main__":
    sys.exit(run())
