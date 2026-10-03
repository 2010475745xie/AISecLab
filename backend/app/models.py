"""
AISecLab 后端 —— 端侧共享契约（与 Android 端 Report / JudgeResult / TestCase 同构）。

设计约束（见 docs/端后端架构方案.md §3）：
- 判定口径单一来源：Verdict / Dimension / RiskLevel / JudgeLevel 取值与端侧完全一致。
- 后端产出的 Report JSON 直接回传端侧 report 渲染，不二次翻译。
"""
from __future__ import annotations

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel


class Dimension(str, Enum):
    security = "security"
    consistency = "consistency"
    factuality = "factuality"
    robustness = "robustness"


class RiskLevel(str, Enum):
    low = "low"
    medium = "medium"
    high = "high"
    critical = "critical"
    unknown = "unknown"   # 一条样本都没评上：与 critical 分开


class Verdict(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    SKIPPED = "SKIPPED"      # 不可评估（接口失败/限流/超时），不计入失守率与总分
    INCONCLUSIVE = "INCONCLUSIVE"  # 规则拿不准（后端按 needsReview 处理，scoring 剔分母）


class JudgeLevel(str, Enum):
    L1_PATTERN = "L1_PATTERN"
    L2_SEMANTIC = "L2_SEMANTIC"
    L3_STRUCTURAL = "L3_STRUCTURAL"
    LLM = "LLM"


class ExpectedBehavior(str, Enum):
    SHOULD_REFUSE = "SHOULD_REFUSE"
    SHOULD_ANSWER_CORRECTLY = "SHOULD_ANSWER_CORRECTLY"
    SHOULD_STAY_CONSISTENT = "SHOULD_STAY_CONSISTENT"
    SHOULD_SAY_UNKNOWN = "SHOULD_SAY_UNKNOWN"


class JudgeResult(BaseModel):
    verdict: Verdict
    confidence: float                  # 0..1
    level: JudgeLevel
    evidence: List[str] = []
    score: float                       # 0..1（本用例得分）
    needsReview: bool = False
    disagreements: List[str] = []


class TestCase(BaseModel):
    id: str
    dimension: Dimension
    title: str
    prompt: str
    expected: ExpectedBehavior
    tags: List[str] = []


class TestResult(BaseModel):
    testCase: TestCase
    response: str
    judgeResult: JudgeResult
    latencyMs: int = 0
    attackTrail: List[str] = []
    stability: float = 1.0     # 多次采样一致性（默认 1.0，单样本恒为 1.0）
    stabilityMeasured: bool = True  # False=单样本/温度0，stability 是占位值、不代表真实波动（优化点⑥）


class DimensionScore(BaseModel):
    dimension: Dimension
    score: float
    passed: int
    total: int
    failRate: float
    notableFindings: List[str] = []


class ModelProfile(BaseModel):
    id: str = "target"
    name: str = ""
    baseUrl: str
    apiKey: str = ""           # 仅任务生命周期内存放，不持久化（见 docs 方案 §4 方案2）
    model: str


class Report(BaseModel):
    modelProfile: ModelProfile
    timestamp: int
    overallScore: float
    riskLevel: RiskLevel
    dimensions: dict                  # {Dimension: DimensionScore}，pydantic 用 dict 保持 JSON 兼容
    results: List[TestResult]
    summary: str


class SessionRequest(BaseModel):
    target: ModelProfile
    aux: Optional[ModelProfile] = None
    # 裁判模型（BYOK 委托场景由客户端带来；比赛/共享服务器通常不预置裁判）。
    # 有则优先于环境变量 JUDGE_*；两者皆无 → 503。
    judge: Optional[ModelProfile] = None
    guardMode: str = "none"          # none / http_service / on_device / user_llm
    dimensions: List[Dimension] = list(Dimension)
    recursive: bool = False
    attackBudget: int = 0            # 每维度 LLM 动态生成攻击条数；0 = 只跑静态回归集（与端上对齐）
    useStaticCases: bool = True      # 是否并入内置静态回归用例集
