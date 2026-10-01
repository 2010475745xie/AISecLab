"""测试编排：并发跑用例，调被测模型 + 裁判，产出 TestResult，经回调推进度。"""
from __future__ import annotations

import asyncio
import os
import time
from typing import Callable, List, Optional

from .attack import STATIC_CASES, generate_cases, run_recursive
from .judge import judge
from .llm_client import ChatClient
from .models import Dimension, ModelProfile, SessionRequest, TestCase, TestResult
from .report_gen import build_report


async def run_session(
    req: SessionRequest,
    judge_client: ChatClient,
    attack_client: ChatClient,
    on_progress: Optional[Callable] = None,
) -> "Report":
    target = ChatClient(req.target)
    cases: List[TestCase] = []
    if req.useStaticCases:
        # 只并入本次勾选维度的静态用例。之前无条件全加，导致只勾 1 个维度时
        # 其余维度的静态用例照样跑，报告里冒出用户没勾的维度。
        cases += [c for c in STATIC_CASES if c.dimension in req.dimensions]
    for dim in req.dimensions:
        # attackBudget<=0 时不生成动态用例：委托运行默认只跑 50 条静态回归集（与端上对齐）。
        # 想加动态攻击（路线图"策略标签库/攻击进化"前置）就把 attackBudget 调大。
        if req.attackBudget > 0:
            cases += await generate_cases(attack_client, dim, max(0, req.attackBudget))

    total = len(cases)
    results: List[TestResult] = []
    # 用例级并发上限（真实 LLM 建议 1~3）。每个用例内裁判三角色仍受 llm_client 的
    # 全局并发闸（MAX_CONCURRENCY）约束，二者叠加后峰值并发 = min(本例, 全局)。
    sem = asyncio.Semaphore(max(1, int(os.environ.get("MAX_CASE_CONCURRENCY", "3"))))

    async def one(tc: TestCase) -> None:
        async with sem:
            # recursive=False 时只跑 1 轮。之前无条件调用 run_recursive（默认 10 轮），
            # 开关形同虚设——用户关了递归对抗，仍在多轮改写强攻，既烧 token 又制造假阳性。
            rounds = 10 if req.recursive else 1
            # 默认 1：与默认 TARGET_TEMPERATURE=0 自洽（温度 0 下多次采样结果一致，
            # stability 恒 1.0，N>1 只是白烧 N 倍调用）。要做稳定性实验时再调大，
            # 并同时把 TARGET_TEMPERATURE 设为 0.6~0.8。
            samples = max(1, int(os.environ.get("SAMPLES_PER_CASE", "1")))
            # 提醒：temperature=0 时 N 次采样高度一致、stability 恒≈1.0，别让人白烧 N 倍调用
            if samples > 1 and float(os.environ.get("TARGET_TEMPERATURE", "0")) <= 0:
                print(
                    "[engine] WARN: SAMPLES_PER_CASE>1 但 TARGET_TEMPERATURE<=0 —— 被测模型无随机性，"
                    "N 次采样结果会高度一致（stability 恒≈1.0），多花的调用换不到有效信息。"
                    "要观察真实波动请把 TARGET_TEMPERATURE 调到 0.6~0.8。",
                    flush=True,
                )
            # ── 多次采样（稳定性，默认 N=1，即单次定论）──
            # 每用例独立采 N 次被测回答；N>1 时逐样本裁判、按"多数裁决"定论，
            # stability = 多数派占比，低一致性（<80%）会在报告里标红。
            # 要观察到方差需被测模型 temperature>0（用 TARGET_TEMPERATURE 覆盖）；
            # 默认 TARGET_TEMPERATURE=0 时采样结果基本一致，stability 恒为 1.0（可复现），
            # 故默认 N=1——N>1 只有在同时调高温度时才有信息量。
            t0 = time.time()
            responses = []
            for _ in range(samples):
                resp, trail = await run_recursive(target, attack_client, tc, max_rounds=rounds)
                responses.append((resp, trail))
            if samples == 1:
                resp, trail = responses[0]
                jr = await judge(judge_client, tc, resp)
                stability = 1.0
            else:
                judged = [await judge(judge_client, tc, r) for r, _ in responses]
                votes = [j.verdict for j in judged]
                majority = max(set(votes), key=votes.count)
                stability = votes.count(majority) / len(votes)
                idx = next(i for i, v in enumerate(votes) if v == majority)
                resp, trail = responses[idx]
                jr = judged[idx].model_copy(update={
                    "disagreements": judged[idx].disagreements + [
                        f"N={samples} 采样多数裁决={majority.value}，稳定性={stability:.0%}"
                    ]
                })
            results.append(TestResult(
                testCase=tc, response=resp, judgeResult=jr,
                latencyMs=int((time.time() - t0) * 1000), attackTrail=trail,
                stability=stability,
            ))
            if on_progress:
                await on_progress(len(results), total, tc.dimension)

    await asyncio.gather(*(one(tc) for tc in cases))
    await target.close()
    return build_report(req.target, results)
