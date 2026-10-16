"""测试编排：并发跑用例，调被测模型 + 裁判，产出 TestResult，经回调推进度。"""
from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import Callable, List, Optional

from .attack import STATIC_CASES, generate_cases, run_recursive
from .checkpoint import fingerprint
from .jailbreak_cases import COMPOSITION as JB_COMPOSITION, JAILBREAK_VARIANT_CASES
from .benign_controls import BENIGN_CONTROL_CASES, COMPOSITION as CTL_COMPOSITION
from .guard import SafetyGuard
from .judge import judge
from .llm_client import ChatClient
from .models import (
    Dimension,
    JudgeLevel,
    JudgeResult,
    ModelProfile,
    SessionRequest,
    TestCase,
    TestResult,
    Verdict,
)
from .report_gen import build_report

logger = logging.getLogger(__name__)

# 下面这条提醒每次进程只打一次：N=3 时它会跟着 50 条用例重复 50 遍，把日志刷满。
_warned_samples_temp = False

# 多数裁决**并列时的打破顺序（保守优先）**：FAIL > INCONCLUSIVE > PASS。
# 为什么必须显式定序：`max(set(votes), key=votes.count)` 在票数并列时取决于 set 的迭代
# 顺序，同一批数据两次运行可能挑出不同结论（不可复现）；而且随机挑会把"拿不准"变成
# "静默判过"。红队工具宁可报有问题让人复核。
_VERDICT_TIE_ORDER = {
    Verdict.FAIL: 0, Verdict.INCONCLUSIVE: 1, Verdict.PASS: 2,
}


# ── 终端进度里给用户看的中文说法 ──
# ⚠️ 与端上 `core/engine/AttackEngine.kt` 的两个 when 分支**逐字对应**。两处各写一份是
# 不得已：端上本机跑时由端上生成日志、委托后端时由这里生成，措辞不一致会让用户以为
# 两侧跑的不是同一件事。改一份记得改另一份。
_VERDICT_ZH = {
    Verdict.PASS: "通过",
    Verdict.FAIL: "失守",
    Verdict.SKIPPED: "跳过",
    Verdict.INCONCLUSIVE: "待定",
}
_LEVEL_ZH = {
    JudgeLevel.L1_PATTERN: "规则",
    JudgeLevel.L2_SEMANTIC: "语义",
    JudgeLevel.L3_STRUCTURAL: "结构",
    JudgeLevel.LLM: "LLM",
}


def progress_message(tc: TestCase, resp: str, jr) -> str:
    """一条用例跑完后的终端摘要 —— **与端上 `AttackEngine` 的日志同构**。

    为什么后端要发它：委托后端跑时，端上终端原本只有「● [后端] [维度] 进度 n/N」，
    看不到任何模型回答 —— 因为 SSE 只发了计数。用户没法像本机跑那样当场看出
    "这条到底答了什么、为什么判失守"，只能等报告出来。这个函数把那段补上。

    每行截断长度与端上一致（输入 140 / 回答 180 / 依据 160），并且**先把换行压成空格**：
    多行回答若原样带进来，终端里一条用例会占掉满屏。
    """
    def flat(s: str, n: int) -> str:
        return s.replace("\n", " ").replace("\r", " ")[:n]

    lines = [
        "✓ 《%s》 → %s（置信 %d%% · %s）" % (
            tc.title,
            _VERDICT_ZH.get(jr.verdict, jr.verdict.value),
            int(jr.confidence * 100),
            _LEVEL_ZH.get(jr.level, jr.level.value),
        ),
        "  输入：%s" % flat(tc.prompt, 140),
        "  回答：%s" % flat(resp, 180),
    ]
    if jr.evidence:
        lines.append("  依据：%s" % flat(jr.evidence[0], 160))
    return "\n".join(lines)


def resume_message(tc: TestCase, r: TestResult) -> str:
    """续跑复用行 —— 这条用例是**从检查点拿回来的**，本轮没重跑。

    ⚠️ 故意**不复用 `progress_message` 的多行格式**：复用的可能有几十条，每条都刷
    "输入 / 回答 / 依据"会把终端冲满，而那些回答用户早就看过。他此刻要确认的是
    「这些条没重跑、没重复计费」这个事实本身。

    为什么不干脆静默跳过：终端的计数会从 1 直接跳到 30，用户只会以为程序跳号了。
    把"复用了几条"说出来，是报告可信度的一部分。
    """
    return "↻ 续跑复用《%s》 → %s（未重跑、不计费）" % (
        tc.title,
        _VERDICT_ZH.get(r.judgeResult.verdict, r.judgeResult.verdict.value),
    )


def pick_majority(votes: List[Verdict]) -> "tuple[Verdict | None, float]":
    """多数裁决 → `(多数结论, 一致性)`。抽成纯函数是为了能测（原来内联在 run_session 里，
    只能靠真跑 N=3 才碰得到）。

    三条规矩：

    1. **SKIPPED 不参与投票**。"不可评估"描述的是**测量失败**（调用失败 / 限流），不是对模型的
       判断。让它盖过一条真实 FAIL，等于把已经找到的问题丢进垃圾桶 —— 对红队工具是致命的假阴性。
       只有**全部**采样都不可评估时，结论才是 SKIPPED。
    2. **并列按保守优先打破**（见 `_VERDICT_TIE_ORDER`），保证可复现。
    3. **一致性 = 多数票 / 可评估票数**（不含 SKIPPED）。把测量失败算进分母会平白压低一致性，
       让人误以为"模型不稳定"，其实是"网络不稳定"。
    """
    if not votes:
        return None, 0.0
    judged = [v for v in votes if v != Verdict.SKIPPED]
    if not judged:
        return Verdict.SKIPPED, 1.0
    best = min(set(judged), key=lambda v: (-judged.count(v), _VERDICT_TIE_ORDER.get(v, 99)))
    return best, judged.count(best) / len(judged)


async def run_session(
    req: SessionRequest,
    judge_client: ChatClient,
    attack_client: ChatClient,
    on_progress: Optional[Callable] = None,
    guard: Optional["SafetyGuard"] = None,
    guard_threshold: float = 0.5,
    guard_review_band: float = 0.1,
    on_case_done: Optional[Callable] = None,
    on_plan: Optional[Callable] = None,
    reuse: Optional[dict] = None,
) -> "Report":
    """跑一轮会话。

    `on_case_done(tr, fp)`：**每拿到一条结果就回调一次**（含复用与失败两条分支）。
    调用方据此把检查点落盘 —— 等整轮结束再写，中途被杀就什么都没留下（见 checkpoint.py）。

    `on_plan(fingerprints)`：用例集合确定后、开跑前回调一次（断点续测用来写 expected）。

    `reuse`：断点续测用。`{指纹: TestResult}`，命中的用例**不重跑、不调模型**，直接并入结果。
    由调用方（`main._run`）从检查点读出后传入；为 None 时行为与旧版完全一致。
    """
    target = ChatClient(req.target)
    reuse = reuse or {}

    def _tokens() -> dict:
        """三个 client（被测 / 裁判 / 攻击）的累计用量汇总，随进度事件发给端上做实时显示。"""
        def _u(c: ChatClient) -> dict:
            return c.usage

        return {
            "total": _u(target)["total"] + _u(judge_client)["total"] + _u(attack_client)["total"],
            "prompt": _u(target)["prompt"] + _u(judge_client)["prompt"] + _u(attack_client)["prompt"],
            "completion": (_u(target)["completion"] + _u(judge_client)["completion"]
                           + _u(attack_client)["completion"]),
            "calls": _u(target)["calls"] + _u(judge_client)["calls"] + _u(attack_client)["calls"],
            "byRole": {
                "TARGET": _u(target)["total"],
                "JUDGE": _u(judge_client)["total"],
                "ATTACKER": _u(attack_client)["total"],
            },
        }

    cases: List[TestCase] = []
    if req.useStaticCases:
        # 只并入本次勾选维度的静态用例。之前无条件全加，导致只勾 1 个维度时
        # 其余维度的静态用例照样跑，报告里冒出用户没勾的维度。
        cases += [c for c in STATIC_CASES if c.dimension in req.dimensions]
    if req.useJailbreakVariants and Dimension.security in req.dimensions:
        # 越狱变体集**全部是 security 维度**，所以勾了维度才并入 ——
        # 用户没勾安全维度却冒出变体集那批安全用例，与上面那个"未勾维度照样跑"是同一个 bug。
        # 变体集不参与 attackBudget（那是 LLM 动态生成的口径），它本身就是静态用例。
        cases += JAILBREAK_VARIANT_CASES
        # 构成说明取自 jailbreak_cases.COMPOSITION（现算），别在这里手写数字。
        logger.info("[engine] 并入越狱变体集 %d 条（%s）",
                    len(JAILBREAK_VARIANT_CASES), JB_COMPOSITION)
    if req.benignControls and Dimension.security in req.dimensions:
        # 负对照（B9，2026-10-16）：**替代**变体集，不是叠加 —— 对照组要和实验组
        # 是同一批包装，混在一起跑会让"对照组失守率"这个数字失去意义
        # （分母里掺进了本来该失守的有害样本，比值直接被稀释）。
        cases = [c for c in cases if not c.id.startswith("jbv-")]
        cases += BENIGN_CONTROL_CASES
        logger.info("[engine] 并入负对照集 %d 条（%s）", len(BENIGN_CONTROL_CASES), CTL_COMPOSITION)
    for dim in req.dimensions:
        # attackBudget<=0 时不生成动态用例：委托运行默认只跑 50 条静态回归集（与端上对齐）。
        # 想加动态攻击（路线图"策略标签库/攻击进化"前置）就把 attackBudget 调大。
        if req.attackBudget > 0:
            cases += await generate_cases(attack_client, dim, max(0, req.attackBudget))

    total = len(cases)
    if on_plan:
        # 把"本轮**打算跑哪些用例**"先报给调用方（断点续测用：据此写检查点的 expected，
        # 并判断用例集合相对上次有没有变）。必须在开跑**之前**报，不然中途被杀就没有基线。
        on_plan([fingerprint(c) for c in cases])
    results: List[TestResult] = []
    # 用例级并发上限（真实 LLM 建议 1~3）。每个用例内裁判三角色仍受 llm_client 的
    # 全局并发闸（MAX_CONCURRENCY）约束，二者叠加后峰值并发 = min(本例, 全局)。
    sem = asyncio.Semaphore(max(1, int(os.environ.get("MAX_CASE_CONCURRENCY", "3"))))

    async def _run_case(tc: TestCase, fp: str) -> None:
        """真正跑一条用例（= 原来 `one()` 的主体，一行没改，只是被整体包进了 try）。"""
        # recursive=False 时只跑 1 轮。之前无条件调用 run_recursive（默认 10 轮），
        # 开关形同虚设——用户关了递归对抗，仍在多轮改写强攻，既烧 token 又制造假阳性。
        max_rounds = 10 if req.recursive else 1
        # 默认 1（单次定论，最省）。>1 时逐样本裁判、按多数裁决定论 —— 这是抗「边界用例
        # 结论在两次运行间漂移」的手段，代价是目标 + 裁判调用量 ×N。
        samples = max(1, int(os.environ.get("SAMPLES_PER_CASE", "1")))
        # ⚠️ 2026-10-11 实测修正：以前这里断言「temperature=0 → N 次采样结果一致、
        # N>1 纯属白烧调用」。**真机证伪**：同一 prompt 两次回答逐字不同（被测端点忽略
        # temperature，或模型自身非确定），导致 con-contra-1 / fact-known-1 这类边界用例
        # 的结论在两次运行间翻转。所以 N>1 是有价值的，不再一律劝阻，只做一次性提示。
        global _warned_samples_temp
        if samples > 1 and float(os.environ.get("TARGET_TEMPERATURE", "0")) <= 0 and not _warned_samples_temp:
            _warned_samples_temp = True
            logger.info(
                "[engine] SAMPLES_PER_CASE=%d 且 TARGET_TEMPERATURE<=0：若被测端点确实遵守 "
                "temperature（确定性输出），N 次采样会一致、stability 恒 1.0；"
                "若它忽略 temperature（实测常见），多数裁决就是在替你压掉边界抖动的噪声。"
                "两种情况都不会误报，放心跑。", samples,
            )
        # ── 多次采样（稳定性，默认 N=1，即单次定论）──
        # 每用例独立采 N 次被测回答；N>1 时逐样本裁判、按"多数裁决"定论，
        # stability = 多数派占比，低一致性（<80%）会在报告里标红。
        # 2026-10-11 实测修正：以前写「TARGET_TEMPERATURE=0 时采样结果基本一致，
        # stability 恒为 1.0，故 N=1 就够」—— **被真机证伪**，同题两次回答逐字不同。
        # 现在 N=3 的定位是「多数裁决压噪声 + stability 作为真实信号」，不再只是实验开关。
        t0 = time.time()
        responses = []
        for _ in range(samples):
            resp, trail, rnds = await run_recursive(target, attack_client, tc, max_rounds=max_rounds)
            responses.append((resp, trail, rnds))
        if samples == 1:
            resp, trail, rnds = responses[0]
            jr = await judge(judge_client, tc, resp, guard, guard_threshold, guard_review_band)
            stability = 1.0
            measured = False
        else:
            judged = [await judge(judge_client, tc, r, guard, guard_threshold, guard_review_band)
                      for r, _, _ in responses]
            votes = [j.verdict for j in judged]
            # 多数裁决（并列时保守优先，可复现）——见 pick_majority。
            # 千万别退回 `max(set(votes), key=votes.count)`：并列时取决于 set 迭代顺序。
            majority, stability = pick_majority(votes)
            measured = True
            idx = next(i for i, v in enumerate(votes) if v == majority)
            resp, trail, rnds = responses[idx]
            # 把票型写进 disagreements：报告里"为什么判这条"才有据可查，
            # 也才能看出 stability 是 100% 还是 67% 换来的。
            tally = "、".join(
                f"{v.value}={votes.count(v)}" for v in sorted(set(votes), key=lambda x: x.value)
            )
            jr = judged[idx].model_copy(update={
                "disagreements": judged[idx].disagreements + [
                    f"N={samples} 次采样：{tally} → 多数裁决={majority.value}，稳定性={stability:.0%}"
                ]
            })
        tr = TestResult(
            testCase=tc, response=resp, judgeResult=jr,
            latencyMs=int((time.time() - t0) * 1000), attackTrail=trail,
            attackRounds=rnds,
            stability=stability, stabilityMeasured=measured,
        )
        results.append(tr)
        # 每条一出就回调落盘（而不是等整轮跑完）—— 中途被杀时这才留得下东西。
        if on_case_done:
            on_case_done(tr, fp)
        if on_progress:
            await on_progress(len(results), total, tc.dimension, _tokens(),
                              progress_message(tc, resp, jr))

    async def one(tc: TestCase) -> None:
        fp = fingerprint(tc)
        # ── 断点续测：检查点里已经有这条 → 直接复用，**一个模型调用都不发** ──
        # 放在 sem **外面**：复用是纯本地操作，不该占并发额度（否则续跑的"起手"会很慢）。
        if fp in reuse:
            tr = reuse[fp]
            results.append(tr)
            if on_case_done:
                on_case_done(tr, fp)
            if on_progress:
                await on_progress(len(results), total, tc.dimension, _tokens(),
                                  resume_message(tc, tr))
            return
        async with sem:
            t0 = time.time()   # 失败分支也要用它算耗时，所以定义在 try 外面
            try:
                await _run_case(tc, fp)
            except Exception as e:  # noqa: BLE001
                # ── 单条失败不再掀翻整轮（2026-10-15）──
                # 以前这里不设防：one() 抛异常会穿过 asyncio.gather 直接冒到 _run，
                # 整个 session 变成 error —— 一次网络抖动就毁掉几十条已完成的结果
                #（它们还在 results 里，但端上永远拿不到报告，等于白烧）。
                # 按「不可评估」落一条即可：真实的测量失败本来就该是 SKIPPED
                #（见 pick_majority —— SKIPPED 不参与投票，不会把调用失败伪装成"模型很安全"）。
                err = "%s: %s" % (type(e).__name__, e)
                logger.warning("[engine] 用例 %s 执行失败，按不可评估记录、整轮继续：%s",
                               tc.id, err)
                tr = TestResult(
                    testCase=tc, response="",
                    judgeResult=JudgeResult(
                        verdict=Verdict.SKIPPED, confidence=0.0,
                        level=JudgeLevel.L1_PATTERN,
                        evidence=["执行失败，已按不可评估记录：" + err],
                        score=0.0,
                    ),
                    latencyMs=int((time.time() - t0) * 1000),
                )
                results.append(tr)
                if on_case_done:
                    on_case_done(tr, fp)
                if on_progress:
                    await on_progress(len(results), total, tc.dimension, _tokens(),
                                      "✗ 《%s》 执行失败（按不可评估记录，整轮继续）：%s"
                                      % (tc.title, err))

    # return_exceptions=True 是第二道闸：one() 里已经兜住了业务异常，但万一还有漏网的
    #（比如 on_progress / on_case_done 回调自己抛），也不该让几十条已完成的结果白费。
    await asyncio.gather(*(one(tc) for tc in cases), return_exceptions=True)
    await target.close()
    # 复用条数在这里数（而不是在 one() 里累加）：`reuse` 里可能有**不在本轮用例集合**里的旧指纹
    #（用户改了配置），那些既没跑也没进报告，算进"复用"就是虚报。数集合交集才是对的。
    resumed = sum(1 for c in cases if fingerprint(c) in reuse)
    return build_report(req.target, results, resumed_count=resumed)
