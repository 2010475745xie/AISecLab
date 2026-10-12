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

# ── 4) 越狱变体集：开关打开后必须真的多跑 100 条 ──
# 这一步专门防"静默丢弃"：端上传 useJailbreakVariants，后端字段名对不上就不会报错，
# 表现只是"变体集永远不跑" —— 报告看起来完全正常（还是 50 条）。
# 所以这里显式断言条数与内容，而不是只看"没抛异常"。
os.environ["SAMPLES_PER_CASE"] = "1"
req_v = models.SessionRequest(
    target=models.ModelProfile(baseUrl="http://mock", model="mock"),
    dimensions=list(models.Dimension),
    recursive=False,
    attackBudget=0,
    useStaticCases=True,
    useJailbreakVariants=True,      # ← 关键开关
)
report_v = asyncio.run(engine.run_session(req_v, FakeClient(), FakeClient()))
nv = len(report_v.results)
assert nv == 150, f"开变体集后应为 150 条（50 基线 + 100 变体），实得 {nv}"
jbv = [r.testCase for r in report_v.results if r.testCase.id.startswith("jbv-")]
assert len(jbv) == 100, f"变体条数应为 100，实得 {len(jbv)}"
assert all(c.intent.value != "unknown" for c in jbv), "变体集存在未归类意图"
assert all(c.strategy.value != "none" for c in jbv), "变体集混入了 none 基线"
assert sum(1 for c in jbv if c.encoded) == 20, "编码族条数应为 20"
# 标签/字段必须一路活到报告里（同样会被静默丢弃）
assert all("jailbreak-variant" in c.tags for c in jbv), "变体集 tags 未落库"
dims_v = {d.value: len([r for r in report_v.results if r.testCase.dimension == d]) for d in models.Dimension}
assert dims_v["security"] == 118, f"安全维度应为 118 条（18 基线 + 100 变体），实得 {dims_v['security']}"
print(f"[variants] cases={nv} by_dim={dims_v} encoded={sum(1 for c in jbv if c.encoded)}")
print("[smoke] ALL OK")
