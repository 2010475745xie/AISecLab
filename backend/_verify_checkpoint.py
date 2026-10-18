"""断点续测的最小可证伪验收 —— **纯本地，一个真模型都不打**。

跑法（必须用 venv 解释器）：
    env -u HTTP_PROXY -u HTTPS_PROXY <venv>/Scripts/python.exe backend/_verify_checkpoint.py

为什么这么写：这套逻辑的价值全在"被打断之后"，而"被打断"在真机上很难复现（要真去杀进程）。
所以这里用**死端点**（127.0.0.1:1）把"调用失败"变成确定性事件，逐条断言：

  A. 指纹：内容变 → 指纹变
     （防的正是 `gen-security-0` 那种"编号固定、内容每次不同"被当成同一条）
  B. 存储：原子写 / 损坏文件当不存在 / 非法 sid 拒落盘 / sweep_running / 只列未完成的
  C. 引擎：**单条用例失败不再掀翻整轮**（全失败也要产出报告，且全部记为 SKIPPED 不是 PASS）
  D. 引擎：指纹命中 → **不重跑**（复用条的 verdict 原样保留），进度行说的是"续跑复用"
  E. 接线：`main._run` 逐条落盘 → 终态 done；resumeFrom 的 404/409 守卫；复用条数进报告自证
  G. **集合不一致 → 拒绝本轮、检查点逐字节不变**（2026-10-16 静默丢 79 条结果的回归测试）
"""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 死端点：连接必然失败 → run_recursive 抛异常 → engine 的兜底分支。重试设 0 让失败"立刻发生"。
os.environ["MAX_RETRIES"] = "0"
os.environ["REQUEST_TIMEOUT"] = "5"
os.environ["MAX_CASE_CONCURRENCY"] = "3"

from fastapi import HTTPException  # noqa: E402

from app import main  # noqa: E402
from app.checkpoint import (  # noqa: E402
    DONE,
    INTERRUPTED,
    CheckpointStore,
    fingerprint,
)
from app.engine import run_session  # noqa: E402
from app.llm_client import ChatClient  # noqa: E402
from app.models import Dimension, ModelProfile, SessionRequest, Verdict  # noqa: E402

DEAD = ModelProfile(id="dead", name="dead", baseUrl="http://127.0.0.1:1/v1",
                    apiKey="k", model="m")

_ok = 0
_bad = 0


def check(name: str, cond: bool, extra: str = "") -> None:
    global _ok, _bad
    if cond:
        _ok += 1
        print("  OK   %s" % name)
    else:
        _bad += 1
        print("  FAIL %s %s" % (name, extra))


def _req(**kw) -> SessionRequest:
    base = dict(target=DEAD, judge=DEAD, aux=DEAD,
                dimensions=[Dimension.factuality], useStaticCases=True,
                recursive=False, attackBudget=0)
    base.update(kw)
    return SessionRequest(**base)


# ── A. 指纹 ───────────────────────────────────────────────────────────────
def test_fingerprint() -> None:
    print("\n[A] 指纹")
    from app.attack import STATIC_CASES

    a = STATIC_CASES[0]
    b = a.model_copy(update={"prompt": a.prompt + "（换了个问法）"})
    check("同一条用例指纹稳定", fingerprint(a) == fingerprint(a))
    check("内容变了指纹就变（防编号复用）", fingerprint(a) != fingerprint(b))
    # 边界：id 不同但 prompt 相同也必须不同（id 参与哈希）
    c = a.model_copy(update={"id": a.id + "-x"})
    check("id 变了指纹也变", fingerprint(a) != fingerprint(c))
    # 分隔符生效：否则 ("ab","c") 与 ("a","bc") 会撞
    d = a.model_copy(update={"id": a.id + "b", "prompt": "c"})
    e = a.model_copy(update={"id": a.id, "prompt": "bc"})
    check("id/prompt 之间有分隔符，不撞哈希", fingerprint(d) != fingerprint(e))


# ── B. 存储 ───────────────────────────────────────────────────────────────
def test_store() -> None:
    print("\n[B] 存储")
    tmp = tempfile.mkdtemp(prefix="aiseclab-cp-")
    try:
        st = CheckpointStore(tmp)
        cp = st.new("s1", 3, ["a", "b", "c"])
        check("new 后能读回", st.load("s1") is not None and st.load("s1").total == 3)
        check("落盘不留 .tmp", not os.path.exists(os.path.join(tmp, "s1.json.tmp")))
        cp.results["a"] = {"x": 1}
        cp.state = DONE
        st.save(cp)
        check("done 不进可续跑列表", [c.sid for c in st.list_incomplete()] == [])
        cp.state = INTERRUPTED
        st.save(cp)
        check("interrupted 进可续跑列表", [c.sid for c in st.list_incomplete()] == ["s1"])
        check("summary 不带结果本体", "results" not in cp.summary()
              and cp.summary()["completed"] == 1)

        # 损坏文件 → 当不存在（不能把续跑请求 500 掉）
        with open(os.path.join(tmp, "broken.json"), "w", encoding="utf-8") as f:
            f.write("{ not json")
        check("损坏文件读回 None", st.load("broken") is None)
        check("损坏文件不炸整张列表", all(c.sid == "s1" for c in st.list_incomplete()))

        # 非法 sid 拒落盘（路径穿越）
        evil = st.new("../../evil", 1, [])
        check("非法 sid 不落盘", not os.path.exists(os.path.join(tmp, "..", "..", "evil.json")))
        check("非法 sid 读回 None", st.load("../../evil") is None)
        check("非法 sid 也不该出现在列表", all("evil" not in c.sid for c in st.list_incomplete()))

        # sweep_running：进程被杀留下的 running
        cp.state = "running"
        st.save(cp)
        n = st.sweep_running()
        check("sweep_running 标了 1 个", n == 1)
        check("sweep 后是 interrupted", st.load("s1").state == INTERRUPTED)
        check("sweep 留下了原因", bool(st.load("s1").error))
        check("sweep 幂等（第二次 0 个）", st.sweep_running() == 0)

        st.delete("s1")
        check("delete 清掉文件", st.load("s1") is None)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ── C/D. 引擎 ─────────────────────────────────────────────────────────────
async def _run_engine(reuse=None):
    jc, ac = ChatClient(DEAD), ChatClient(DEAD)
    done, plan, msgs, resumed_seen = [], [], [], []

    async def on_progress(n, total, dim, tokens=None, message=None, resumed=None):
        msgs.append(message or "")
        resumed_seen.append(resumed or 0)

    try:
        report = await run_session(
            _req(), jc, ac, on_progress, None, 0.5, 0.1,
            on_case_done=lambda tr, fp: done.append((tr, fp)),
            on_plan=lambda fps: plan.append(list(fps)),
            reuse=reuse,
        )
    finally:
        await jc.close()
        await ac.close()
    return report, done, plan, msgs, resumed_seen


def test_engine() -> None:
    print("\n[C] 引擎：单条失败不掀翻整轮 / [D] 复用不重跑")
    report, done, plan, msgs, resumed_seen = asyncio.run(_run_engine())
    check("on_plan 收到用例集合", len(plan) == 1 and len(plan[0]) == 10,
          "plan=%s" % (len(plan[0]) if plan else None))
    check("死端点下**照样产出报告**（不再整轮 error）", report is not None)
    check("10 条全落结果", len(report.results) == 10, "len=%d" % len(report.results))
    check("全部记为 SKIPPED（不是 PASS 也不是 FAIL）",
          all(r.judgeResult.verdict == Verdict.SKIPPED for r in report.results))
    check("没有有效样本 → 风险等级 unknown（不是 critical）",
          report.riskLevel.value == "unknown", report.riskLevel.value)
    check("每条都回调过 on_case_done", len(done) == 10, "len=%d" % len(done))
    check("全新一轮 resumedCount=0", report.resumedCount == 0)
    check("失败行也说清了原因", any("执行失败" in m for m in msgs))
    check("全新一轮的 progress 都报 resumed=0（不虚报复用）",
          len(resumed_seen) == 10 and all(r == 0 for r in resumed_seen), str(resumed_seen))

    # ── D. 拿前 3 条当"上次已跑完"，并给它们一个**不可能由死端点产生**的结论 ──
    reuse = {}
    for tr, fp in done[:3]:
        reuse[fp] = tr.model_copy(update={
            "judgeResult": tr.judgeResult.model_copy(update={
                "verdict": Verdict.PASS,
                "evidence": ["[verify] 这条来自检查点"],
            }),
            "response": "[verify] 上轮的回答",
        })
    report2, done2, _, msgs2, resumed_seen2 = asyncio.run(_run_engine(reuse=reuse))
    kept = [r for r in report2.results
            if r.judgeResult.evidence[:1] == ["[verify] 这条来自检查点"]]
    check("复用的 3 条保留原结论（没被重跑覆盖成 SKIPPED）", len(kept) == 3, "kept=%d" % len(kept))
    check("复用条的回答也是上轮的", all(r.response == "[verify] 上轮的回答" for r in kept))
    check("其余 7 条是本轮真跑的（死端点 → SKIPPED）",
          sum(1 for r in report2.results if r.judgeResult.verdict == Verdict.SKIPPED) == 7)
    check("报告自证：resumedCount=3", report2.resumedCount == 3, str(report2.resumedCount))
    check("依旧 10 条（复用 + 新跑合并）", len(report2.results) == 10)
    check("终端里说的是「续跑复用」而不是「通过/失守」",
          sum(1 for m in msgs2 if "续跑复用" in m) == 3,
          "命中=%d" % sum(1 for m in msgs2 if "续跑复用" in m))
    check("复用行不含输入/回答（不刷屏）", all(("输入：" not in m) for m in msgs2 if "续跑复用" in m))
    check("续跑的 progress 上报的累计复用数单调递增到 3",
          resumed_seen2 == sorted(resumed_seen2) and max(resumed_seen2) == 3, str(resumed_seen2))
    check("续跑最后一条也报 3（界面才能显示「复用 3 / 新跑 7」）",
          resumed_seen2[-1] == 3, str(resumed_seen2[-1]))


# ── E. main 接线 ──────────────────────────────────────────────────────────
def test_wiring() -> None:
    print("\n[E] 接线：落盘 / 终态 / 守卫 / 自证")
    tmp = tempfile.mkdtemp(prefix="aiseclab-wire-")
    old_store, old_sessions = main.STORE, main.sessions
    try:
        main.STORE = CheckpointStore(tmp)
        main.sessions = {}
        sid = "verify-1"
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}

        asyncio.run(main._run(sid, _req(), asyncio.Queue()))

        cp = main.STORE.load(sid)
        check("检查点落盘", cp is not None)
        check("终态是 done", cp.state == DONE, cp.state if cp else "-")
        check("completed == total == 10", cp.completed == 10 and cp.total == 10,
              "%s/%s" % (cp.completed, cp.total))
        check("expected 记了本轮指纹", len(cp.expected) == 10)
        check("跑完的会话不再出现在可续跑列表",
              sid not in [c.sid for c in main.STORE.list_incomplete()])
        check("report 挂到 sessions 上", main.sessions[sid]["report"] is not None)
        check("running 标志复位", main.sessions[sid]["running"] is False)
        check("全新一轮 resumedCount=0", main.sessions[sid]["report"].resumedCount == 0)

        # 守卫 ①：已跑完 → 409
        try:
            main._guard_resume(cp, _req())
            check("已跑完的会话续跑被拒（409）", False, "居然通过了")
        except HTTPException as e:
            check("已跑完的会话续跑被拒（409）", e.status_code == 409, str(e.status_code))

        # 守卫 ②：不存在的断点 → 404（走 create_session，这条在 404 就返回、不起任务）
        try:
            asyncio.run(main.create_session(_req(resumeFrom="no-such-session")))
            check("不存在的断点续跑被拒（404）", False, "居然通过了")
        except HTTPException as e:
            check("不存在的断点续跑被拒（404）", e.status_code == 404, str(e.status_code))

        # 守卫 ③：还在跑 → 409
        cp.state = INTERRUPTED
        main.STORE.save(cp)
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        try:
            main._guard_resume(cp, _req())
            check("运行中的会话不许重复续跑（409）", False, "居然通过了")
        except HTTPException as e:
            check("运行中的会话不许重复续跑（409）", e.status_code == 409, str(e.status_code))

        # 守卫 ④：换过被测模型 → 409（否则两个模型的结果会混进同一份报告 = 假证据）
        check("检查点记下了上一轮的被测模型", cp.target_model == DEAD.model, cp.target_model)
        main.sessions[sid]["running"] = False
        other = DEAD.model_copy(update={"model": "另一个模型"})
        try:
            main._guard_resume(cp, _req(target=other))
            check("换了被测模型的续跑被拒（409）", False, "居然通过了")
        except HTTPException as e:
            check("换了被测模型的续跑被拒（409）", e.status_code == 409, str(e.status_code))
        # 但只换地址（同一模型：局域网 IP ↔ adb reverse 127.0.0.1）必须放行 —— 这是常规操作
        moved = DEAD.model_copy(update={"baseUrl": "http://127.0.0.1:9/v1"})
        try:
            main._guard_resume(cp, _req(target=moved))
            check("同一模型换地址仍可续跑（守卫不比 baseUrl）", True)
        except HTTPException as e:
            check("同一模型换地址仍可续跑（守卫不比 baseUrl）", False, "被拒了：%s" % e.detail)

        # 真正的续跑：把上一次的全部结果复用掉 → 一个模型调用都不该发
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        q = asyncio.Queue()
        asyncio.run(main._run(sid, _req(), q))
        rep = main.sessions[sid]["report"]
        check("续跑后 10 条全部复用", rep.resumedCount == 10, str(rep.resumedCount))
        check("续跑后总数仍是 10（没跑成 20）", len(rep.results) == 10)
        check("终态回到 done", main.STORE.load(sid).state == DONE)
        events, payloads = [], []
        while not q.empty():
            ev = q.get_nowait()          # 队列里是 dict（序列化在 stream() 里统一做）
            events.append(ev["event"])
            payloads.append(ev)
        check("最后一条 SSE 是 done", events and events[-1] == "done", str(events[-3:]))
        prog = [p for p in payloads if p["event"] == "progress"]
        check("SSE 的 progress 事件带 resumed 字段（端上才能显示复用/新跑）",
              bool(prog) and all("resumed" in p for p in prog), str(prog[:1]))
        check("续跑末尾 SSE 报 resumed=10", prog and prog[-1]["resumed"] == 10,
              str(prog[-1]["resumed"] if prog else None))

        # 最坏情况：用例全都跑完了，**收尾那一步才炸**。这里用"会话没登记"制造一个确定性异常
        #（`sessions[sid]["report"] = report` → KeyError）—— 要证的正是：那些已经落盘的结果
        # **一条都不能丢**，会话标成 failed（可续跑）而不是 done/失踪。
        asyncio.run(main._run("bad", _req(), asyncio.Queue()))
        cpb = main.STORE.load("bad")
        check("整轮异常时落 failed 且留下原因",
              cpb is not None and cpb.state == "failed" and bool(cpb.error),
              "state=%s" % (cpb.state if cpb else None))
        check("炸在收尾时已跑完的 10 条结果仍在检查点里",
              cpb is not None and cpb.completed == 10,
              "completed=%s" % (cpb.completed if cpb else None))
        check("failed 会话仍在可续跑列表里",
              "bad" in [c.sid for c in main.STORE.list_incomplete()])
    finally:
        main.STORE, main.sessions = old_store, old_sessions
        shutil.rmtree(tmp, ignore_errors=True)


# ── G. 集合不一致 → 拒绝，且绝不删除检查点数据（2026-10-16 丢 79 条的回归）──
def test_no_silent_purge() -> None:
    """这条测试守着一次**真实的、不可逆的数据丢失**。

    2026-10-16 实况：用户全勾（314 条）跑到 170+ 时断流，续跑时**漏勾了「越狱变体集」**
    （默认关、且续跑界面不一定显式提示），本轮集合只有 50 条 —— 旧实现的 `on_plan`
    把"不在本轮集合里"的 79 条已跑完结果**直接 pop 掉并落盘**，报告显示"共 50 条"，
    那 79 条再也回不来了。所以这里断言的不是"报错文案好看"，而是
    **检查点内容逐字节不变**。
    """
    print("\n[G] 续跑集合不一致：拒绝本轮、检查点原样保留")
    tmp = tempfile.mkdtemp(prefix="aiseclab-purge-")
    old_store, old_sessions = main.STORE, main.sessions
    try:
        main.STORE = CheckpointStore(tmp)
        main.sessions = {}
        sid = "purge-1"

        # 前置：正常跑完一轮（factuality 静态集 10 条）→ 拿到 10 个**真实**指纹
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        asyncio.run(main._run(sid, _req(), asyncio.Queue()))
        cp = main.STORE.load(sid)
        check("前置：第一轮落盘 10 条", cp.completed == 10, "completed=%d" % cp.completed)
        sample = cp.results[list(cp.results)[0]]

        # 再模拟"上一轮跑过越狱变体集"：塞 3 条**本轮集合里不存在**的结果
        injected = ["beef%012d" % i for i in range(3)]
        for fp in injected:
            cp.results[fp] = dict(sample)
        cp.state = INTERRUPTED
        main.STORE.save(cp)
        check("前置：检查点里现在 13 条", main.STORE.load(sid).completed == 13)
        before = json.dumps(main.STORE.load(sid).results, sort_keys=True)

        # 续跑：本轮只有 10 条（≙ 用户漏勾了越狱变体集）
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        q = asyncio.Queue()
        asyncio.run(main._run(sid, _req(resumeFrom=sid), q))

        after = main.STORE.load(sid)
        check("整轮被判 failed（而不是「成功了但其实少了 3 条」）",
              after.state == "failed", after.state)
        err = after.error or ""
        check("失败原因说清了「本轮几条 / 检查点几条 / 会丢几条」",
              "本轮用例数" in err and "检查点已完成" in err and "3 条" in err, repr(err[:120]))
        check("失败原因给了可操作的出路（勾回开关 or 新建一轮）",
              "越狱变体集" in err and "新建一轮" in err)
        check("★ 检查点 13 条一条都没被删", after.completed == 13,
              "completed=%d" % after.completed)
        check("★ 检查点内容逐字节不变（没有偷偷改过）",
              json.dumps(after.results, sort_keys=True) == before)
        check("没产出报告（拒绝得彻底，不产生半真半假的报告）",
              main.sessions[sid]["report"] is None)
        events = []
        while not q.empty():
            events.append(q.get_nowait()["event"])
        check("SSE 回的是 error 事件（端上会立刻看到，而不是干等）",
              events and events[-1] == "error", str(events))
        check("failed 会话仍可续跑（改回配置就能接着跑）",
              sid in [c.sid for c in main.STORE.list_incomplete()])

        # 反证 ①：把不匹配的 3 条删掉（≙ 用户改回原配置）→ 必须能正常续跑，且全复用
        cp2 = main.STORE.load(sid)
        for fp in injected:
            cp2.results.pop(fp, None)
        main.STORE.save(cp2)
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        asyncio.run(main._run(sid, _req(), asyncio.Queue()))
        rep = main.sessions[sid]["report"]
        check("配置改回一致后能正常续跑", rep is not None and main.STORE.load(sid).state == DONE)
        check("复用 10 条、一个模型调用都没发", rep is not None and rep.resumedCount == 10,
              str(rep.resumedCount if rep else None))

        # 反证 ②：本轮集合是**超集**（多勾一个维度）时必须放行 —— 只增不减，没有任何数据会丢，
        # 拦下来就是过度拦截（用户正常"续跑时加一个维度"会被挡住，体验更差）。
        main.sessions[sid] = {"queue": asyncio.Queue(), "report": None, "running": True}
        asyncio.run(main._run(
            sid, _req(dimensions=[Dimension.factuality, Dimension.security]),
            asyncio.Queue()))
        cp3 = main.STORE.load(sid)
        check("本轮是超集（多勾一维）→ 放行，不误拒",
              cp3.state == DONE and "不一致" not in (cp3.error or ""),
              "state=%s err=%s" % (cp3.state, (cp3.error or "")[:60]))
        check("超集跑完后旧结果仍在（只增不减）", cp3.completed >= 10,
              "completed=%d" % cp3.completed)
    finally:
        main.STORE, main.sessions = old_store, old_sessions
        shutil.rmtree(tmp, ignore_errors=True)


# ── 静态检查：报告契约两端字段名一致（防止静默丢弃）────────────────────
def test_contract() -> None:
    print("\n[F] 契约：报告字段名与端上 React/Report 对齐")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    kt = os.path.join(root, "app", "src", "main", "java", "com", "xieguojian",
                      "aiseclab", "core", "model", "Report.kt")
    if not os.path.exists(kt):
        check("端上 Report.kt 存在", False, kt)
        return
    with open(kt, encoding="utf-8") as f:
        src = f.read()
    check("端上 Report 有 resumedCount（否则字段被静默丢弃）", "resumedCount" in src)
    check("端上会话请求有 resumeFrom", "resumeFrom" in _read(root, "core/api/BackendClient.kt"))


def _read(root: str, rel: str) -> str:
    p = os.path.join(root, "app", "src", "main", "java", "com", "xieguojian", "aiseclab", rel)
    if not os.path.exists(p):
        return ""
    with open(p, encoding="utf-8") as f:
        return f.read()


if __name__ == "__main__":
    test_fingerprint()
    test_store()
    test_engine()
    test_wiring()
    test_no_silent_purge()
    test_contract()
    print("\n%s\n通过 %d / 失败 %d" % ("=" * 46, _ok, _bad))
    sys.exit(1 if _bad else 0)
