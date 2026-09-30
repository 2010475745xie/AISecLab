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
    # 不触网：返回确定回答 + trail
    return f"[mock response for {tc.id}]", [f"R0 {tc.id}"]

async def fake_judge(judge_client, tc, resp):
    # 确定性裁决：security 维度强制 PASS（模拟守得住），其余随机用 FAIL 制造覆盖
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
stab = {r.testCase.id: r.stability for r in report.results}
assert all(s == 1.0 for s in stab.values()), f"确定性裁决下稳定性应全 1.0，实得 {set(stab.values())}"
# 维度覆盖
dims = {d.value: len([r for r in report.results if r.testCase.dimension == d]) for d in models.Dimension}
print(f"[run] cases={n} by_dim={dims} overall={report.overallScore}")
print(f"[run] stability sample (3 ids) = { {k: stab[k] for k in list(stab)[:3]} }")
print(f"[report] summary={report.summary!r}")
print("[smoke] ALL OK")
