"""验证「停止一轮测试」能真正掐掉后台任务（2026-10-18 修的 409 死锁）。

背景（用户原话）：
    「为什么 app 上已经停止并废弃了，断点续测依然正在运行而且无法丢弃断点」

根因：端上「停止」只断开 SSE，后端 `_run` 继续烧 token，`sessions[sid]["running"]`
      永远为 True ⇒ `_guard_resume` 与 `drop_checkpoint` 都返回 409。

本脚本**直接 await 接口函数**（不经 HTTP/TestClient）：
  · 不需要启动服务，不依赖上游模型；
  · 检查点写到临时目录，不碰 backend/dist/checkpoints 里的真实数据。
"""
from __future__ import annotations

import asyncio
import os
import sys
import tempfile
import time

# ⚠️ 必须在 import app.main 之前设：STORE 在模块加载时就建好了。
os.environ["CHECKPOINT_DIR"] = tempfile.mkdtemp(prefix="aiseclab-cancel-")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi import HTTPException  # noqa: E402

import app.main as m  # noqa: E402
from app.checkpoint import INTERRUPTED, RUNNING, Checkpoint  # noqa: E402

FAILS: list = []


def check(label: str, got, want) -> None:
    ok = got == want
    print("  %s %-40s got=%r want=%r" % ("OK  " if ok else "FAIL", label, got, want))
    if not ok:
        FAILS.append(label)


def _mk_cp(sid: str, state: str = RUNNING) -> Checkpoint:
    cp = Checkpoint(sid=sid, created_at=time.time(), updated_at=time.time())
    cp.state = state
    cp.total = 50
    m.STORE.save(cp)
    return cp


def _fake_session(sid: str):
    """造一个「正在跑」的会话：一个永不结束的 task + 空队列。"""
    q: asyncio.Queue = asyncio.Queue()
    task = asyncio.create_task(asyncio.sleep(999))
    m.sessions[sid] = {"queue": q, "report": None, "running": True, "task": task}
    return q, task


async def case_a_running_session() -> None:
    print("\n[1] 正在跑的会话 → cancel 必须真掐掉")
    sid = "cancel-running-1"
    _mk_cp(sid)
    q, task = _fake_session(sid)
    await asyncio.sleep(0)                     # 让 task 真正开始跑

    r = await m.cancel_session(sid)
    await asyncio.sleep(0.05)                  # 给 CancelledError 传播的时间

    check("接口返回 cancelled", r.get("cancelled"), True)
    check("task 已取消", task.cancelled() or task.cancelling() > 0, True)
    check("sessions 里 running 已 False", m.sessions[sid]["running"], False)
    cp = m.STORE.load(sid)
    check("检查点 state", cp.state, INTERRUPTED)
    ev = q.get_nowait() if not q.empty() else None
    check("SSE 队列收到 cancelled 事件", (ev or {}).get("event"), "cancelled")

    # 关键：这两件事之前都被 409 挡住
    print("  -- cancel 之后，之前被 409 挡住的两个操作 --")
    try:
        r2 = await m.cancel_session(sid)        # 再停一次要幂等，不能抛
        check("重复 cancel 幂等", r2.get("cancelled"), False)
    except Exception as e:  # noqa: BLE001
        check("重复 cancel 幂等", "抛了 %s" % type(e).__name__, "不抛")

    try:
        d = await m.drop_checkpoint(sid)
        check("drop_checkpoint 不再 409", d.get("deleted"), sid)
    except HTTPException as e:
        check("drop_checkpoint 不再 409", "HTTP %s" % e.status_code, sid)
    check("检查点文件已删除", m.STORE.load(sid), None)


async def case_b_not_in_memory() -> None:
    print("\n[2] 服务重启过（内存里没有）→ 也要能收拾")
    sid = "cancel-orphan-1"
    _mk_cp(sid)
    m.sessions.pop(sid, None)
    r = await m.cancel_session(sid)
    check("cancelled 为 False", r.get("cancelled"), False)
    check("检查点标成 interrupted", m.STORE.load(sid).state, INTERRUPTED)


async def case_c_missing() -> None:
    print("\n[3] 完全不存在 → 404")
    try:
        await m.cancel_session("no-such-sid-xyz")
        check("抛 404", "没抛", "HTTPException")
    except HTTPException as e:
        check("抛 404", e.status_code, 404)


async def case_d_done_not_downgraded() -> None:
    print("\n[4] 已跑完的会话不能被 cancel 降级")
    sid = "cancel-done-1"
    _mk_cp(sid, state="done")
    q, task = _fake_session(sid)
    await asyncio.sleep(0)
    await m.cancel_session(sid)
    await asyncio.sleep(0.05)
    check("检查点仍是 done（报告不能消失）", m.STORE.load(sid).state, "done")


async def main() -> int:
    await case_a_running_session()
    await case_b_not_in_memory()
    await case_c_missing()
    await case_d_done_not_downgraded()
    print("\n" + "=" * 60)
    if FAILS:
        print("FAILED %d 项：%s" % (len(FAILS), FAILS))
        return 1
    print("ALL OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
