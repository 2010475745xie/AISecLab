"""测试编排：并发跑用例，调被测模型 + 裁判，产出 TestResult，经回调推进度。"""
from __future__ import annotations

import asyncio
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
        cases += STATIC_CASES
    for dim in req.dimensions:
        cases += await generate_cases(attack_client, dim, max(0, req.attackBudget))

    total = len(cases)
    results: List[TestResult] = []
    sem = asyncio.Semaphore(4)  # 并发上限，避免打爆被测模型

    async def one(tc: TestCase) -> None:
        async with sem:
            resp, trail = await run_recursive(target, attack_client, tc)
            t0 = time.time()
            jr = await judge(judge_client, tc, resp)
            results.append(TestResult(
                testCase=tc, response=resp, judgeResult=jr,
                latencyMs=int((time.time() - t0) * 1000), attackTrail=trail,
            ))
            if on_progress:
                await on_progress(len(results), total, tc.dimension)

    await asyncio.gather(*(one(tc) for tc in cases))
    await target.close()
    return build_report(req.target, results)
