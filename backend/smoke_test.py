"""后端离线冒烟：py_compile 全模块 + monkeypatch 跑 run_session（50 用例）。
不触网：ChatClient/run_recursive/judge 全部打桩。
"""
from __future__ import annotations
import os
import sys
import asyncio
import py_compile
import glob

# 让 backend 目录成为包根（app 包在其下）
BACKEND = r"C:\AISecLab\backend"
sys.path.insert(0, BACKEND)

# ── 1) 编译所有 .py ──
errors = []
for f in glob.glob(os.path.join(BACKEND, "app", "*.py")):
    try:
        py_compile.compile(f, doraise=True)
    except py_compile.PyCompileError as e:
        errors.append(f"{f}: {e}")
print(f"[compile] modules={len(glob.glob(os.path.join(BACKEND,'app','*.py')))} errors={errors}")
assert not errors, "py_compile 失败: " + "; ".join(errors)

# ── 2) 打桩后跑 run_session ──
import app.engine as engine
import app.models as models

# 用一个可 await 的假 ChatClient（run_session 会 ChatClient(req.target) 并 close()）
class FakeClient:
    def __init__(self, profile=None):
        pass
    async def close(self):
        return None

async def fake_run_recursive(target, attack_client, tc, max_rounds=10):
    # 不触网：返回确定回答 + trail + **每轮结构化记录**。
    # 第三个返回值是 attackRounds（与 app/attack.py::run_recursive 同步扩的），
    # 少返回一个会让 engine 的三元解包直接抛 ValueError。
    resp = f"[mock response for {tc.id}]"
    return resp, [f"R0 {tc.id}"], [
        models.AttackRound(round=1, prompt=tc.prompt, response=resp, refused=True)
    ]

async def fake_judge(judge_client, tc, resp, guard=None, threshold=0.5, review_band=0.1):
    # 确定性裁决：security 维度强制 PASS（模拟守得住），其余随机用 FAIL 制造覆盖
    # 注意：签名必须跟上 engine.py 的实参个数（护栏功能引入后多了 guard/threshold/band）。
    from app.models import JudgeResult, Verdict, JudgeLevel
    v = Verdict.PASS if tc.dimension == models.Dimension.security else Verdict.FAIL
    return JudgeResult(verdict=v, confidence=0.9, level=JudgeLevel.LLM,
                       score=1.0 if v == Verdict.PASS else 0.0)

engine.ChatClient = FakeClient
engine.run_recursive = fake_run_recursive
engine.judge = fake_judge

req = models.SessionRequest(
    target=models.ModelProfile(baseUrl="http://mock", model="mock"),
    dimensions=list(models.Dimension),
    recursive=False,
    attackBudget=0,           # 默认只跑静态 50 条
    useStaticCases=True,
)
# 多次采样验证 stability 字段（N=3）
os.environ["SAMPLES_PER_CASE"] = "3"

report = asyncio.run(engine.run_session(req, FakeClient(), FakeClient()))

# ── 3) 断言 ──
n = len(report.results)
assert n == 50, f"用例数应为 50，实得 {n}"
assert all(hasattr(r, "stability") for r in report.results), "TestResult 缺 stability 字段"
# attackRounds 必须真的从 attack.py 一路穿过 engine 落到报告里 ——
# 两端都是"忽略未知键"，字段接错是**静默丢弃**（图空的但不报错），所以这里显式断言。
assert all(len(r.attackRounds) == 1 for r in report.results), "attackRounds 未落库/被静默丢弃"
ar = report.results[0].attackRounds[0]
assert ar.round == 1 and isinstance(ar.refused, bool), f"AttackRound 字段异常: {ar}"
stab = {r.testCase.id: r.stability for r in report.results}
assert all(s == 1.0 for s in stab.values()), f"确定性裁决下稳定性应全 1.0，实得 {set(stab.values())}"
# 维度覆盖
dims = {d.value: len([r for r in report.results if r.testCase.dimension == d]) for d in models.Dimension}
print(f"[run] cases={n} by_dim={dims} overall={report.overallScore}")
print(f"[run] stability sample (3 ids) = { {k: stab[k] for k in list(stab)[:3]} }")
print(f"[report] summary={report.summary!r}")
print("[smoke] ALL OK")
