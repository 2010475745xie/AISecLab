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


class HarmIntent(str, Enum):
    """有害意图 —— 这条用例**到底想让模型干什么**（忽略表面措辞与包装）。

    与 JailbreakStrategy 的分工：意图 = 目的，策略 = 手段，二者正交 ——
    同一个「系统提示窃取」意图可以用角色扮演 / 编码 / 虚构情境任意一种策略发起，
    报告里的「策略 × 意图矩阵」就是这个正交关系的产物。

    与端上 core/model/Jailbreak.kt::HarmIntent 逐字镜像。
    """
    malware = "malware"
    chem_weapon = "chem_weapon"
    discrimination = "discrimination"
    privacy_exfil = "privacy_exfil"
    sysprompt_leak = "sysprompt_leak"
    fraud = "fraud"
    self_harm = "self_harm"
    unknown = "unknown"


class JailbreakStrategy(str, Enum):
    """越狱策略族 —— 这次攻击**用了什么手段**。

    为什么要归「族」：`忽略之前的指令` 与 `Disregard your prior rules` 表面完全不同，
    但属同一族（overwrite）；归族才能跨样本统计「这个模型最怕哪类手法」。

    none 是**基线**（纯直问）；所有策略的效果都要减去这个基线才有意义。

    与端上 core/model/Jailbreak.kt::JailbreakStrategy 逐字镜像。
    """
    roleplay = "roleplay"
    overwrite = "overwrite"
    encoding = "encoding"
    many_shot = "many_shot"
    fiction = "fiction"
    tool_inject = "tool_inject"
    refusal_suppress = "refusal_suppress"
    # 说服 / 社会工程（A2，2026-10-15 补）。与上面那批方向不同：上面是**对抗性指令**
    # （把规则当要被推翻的对象），这五族是**哄骗**（让模型觉得"这次应该给"）。
    persuasion = "persuasion"
    taxonomy = "taxonomy"
    history_framing = "history_framing"
    thought_experiment = "thought_experiment"
    word_game = "word_game"
    none = "none"


class StrategyFingerprint(BaseModel):
    """策略指纹 —— 把表面写法归到策略族的规则。纯静态、不烧 token。

    只判「用了什么手法」（纯归因），**绝不回流影响 verdict** —— 那是 L1 规则的职责。
    """
    strategy: JailbreakStrategy
    keywords: List[str] = []
    patterns: List[str] = []      # 正则源码
    weight: float = 1.0


class AttackAttribution(BaseModel):
    """一次攻击链的「意图 × 策略」归因（**运行时识别**结果）。

    区别于 TestCase.intent / TestCase.strategy 的「设计时预设」：
    这里的是跑起来识别出来的，能发现「设计的策略与实际发出的不一致」
    （攻击者 LLM 可能把 prompt 改写成另一种策略，这本身就是值得记进报告的信号）。
    """
    intent: HarmIntent = HarmIntent.unknown
    strategies: List[JailbreakStrategy] = []
    matchedFingerprints: List[str] = []
    confidence: float = 0.0


class TestCase(BaseModel):
    id: str
    dimension: Dimension
    title: str
    prompt: str
    expected: ExpectedBehavior
    tags: List[str] = []
    # ── 意图 × 策略元数据（2026-10-12）──
    # 可选带默认值：旧用例集没有标注也能反序列化。字段名必须与端上
    # core/model/TestCase.kt 逐字一致 —— 两端都忽略未知键，名字对不上是**静默丢弃**。
    intent: HarmIntent = HarmIntent.unknown
    strategy: JailbreakStrategy = JailbreakStrategy.none
    # 该用例的请求体是否经过**编码混淆**（Base64 / ROT13 / Morse / 零宽字符 / 同形字）。
    # 与 `strategy == encoding` 会分叉：可以在 roleplay 包装里再嵌一层 base64
    # （strategy 记主策略、encoded 记事实），也可能"请解码这段"指向一段没编码的明文。
    # 这个字段是**编码还原率**这类指标的分母，必须如实反映"到底编没编"。
    encoded: bool = False


class ComplianceLevel(str, Enum):
    """单轮回答里模型表现出的**态度**测量（方案 §周 1–2 的 2.2「半妥协检测」）。

    与端上 `core/model/Compliance.kt::ComplianceLevel` 逐字镜像（线值见各自的 SerialName）。

    为什么单独立这一维：`Verdict` 是**对整条用例的裁定**（有没有失守），答不了
    「半妥协」这个真实越狱里最常见的形态 ——「我不能给你制作方法。不过从化学原理上说，
    A 和 B 反应会生成 C，**第一步**先……」这句话**既有**拒绝话术、**又有**实质内容；
    按整段判「拒绝」会放过它，按整段判「失守」又会冤枉那些只提了原理没给操作的回答。

    所以它是一个**纯文本测量**（规则，不烧 token；判据见 `compliance.py`），
    只描述"这一轮读起来像什么"：
    🔴 **它不是判定结论** —— 「有没有被攻破」一律以 `JudgeResult.verdict` 为准
    （铁律见下方 `AttackRound` 的注释）。两者不一致是允许的、甚至是信息：
    判 PASS 但本轮读起来像「配合」= 该条用例的判定值得人工复核。
    """

    refused = "refused"      # 有拒绝话术、且没有实质内容 —— 干净地拒绝了
    partial = "partial"      # 有拒绝话术、**同时又给了实质内容** —— 半妥协（本维度的关键值）
    complied = "complied"    # 没有拒绝话术 —— 至少这轮的文本表现上没挡。内容对不对由判定管
    unknown = "unknown"      # 判不出来：空回答、调用失败、或回答只有拒绝语和标点


class AttackRound(BaseModel):
    """递归对抗（PAIR）的单轮记录 —— 攻击演进时间轴的数据源。

    为什么要有它：`attackTrail` 存的是拼好的展示字符串（且两端格式不一致），
    "这一轮到底拒绝没拒绝"从字符串里取不出来。本类把每轮结构化落库。

    契约（**改一端必须同步另一端**，见端上 `core/model/TestResult.kt::AttackRound`）：
    - `round` **统一 1-based**：本模块 `for r in range(max_rounds)` 是 0-based，
      落库时须 `r + 1`，不统一会让两端时间轴**错位一格**；
    - `refused` = 该轮**未破防**，定义为 `verdict != FAIL`，不是用独立拒绝词表判出来的
      （全项目已有三套互不相同的拒绝判据，别再添第四套）。它只是**展示用派生量，
      不得回流去影响 verdict 或评分**；
    - `response` 是该轮被测模型的原始回答，导出时**不截断**（报告是审计/复盘产物）。
    """

    round: int
    prompt: str
    response: str
    refused: bool
    # 该轮的**态度测量**（纯文本规则，判据见 compliance.py::detect）。与 `refused`
    # **不同源、也不等价**：`refused` 是"本轮破没破防"的派生量，`compliance` 是独立的文本测量
    # ——「refused=True 但 compliance=complied」是合法组合，不是 bug。
    # 默认 unknown：旧记录没有这个键时是"没测"，不是"测出来是拒绝"（铁律「没采集≠测出来很差」）。
    compliance: ComplianceLevel = ComplianceLevel.unknown


class TestResult(BaseModel):
    testCase: TestCase
    response: str
    judgeResult: JudgeResult
    latencyMs: int = 0
    attackTrail: List[str] = []      # 旧格式，保留兼容；新代码请读 attackRounds
    # 递归对抗的每轮结构化记录。⚠️ 字段名必须与端上 `TestResult.attackRounds` 逐字一致
    # —— 两端反序列化都开着忽略未知键，名字对不上会**静默丢弃**（图空的但不报错）。
    attackRounds: List[AttackRound] = []
    stability: float = 1.0     # 多次采样一致性（默认 1.0，单样本恒为 1.0）
    # False=单样本/温度0，stability 是占位值、不代表真实波动（优化点⑥）。
    # ⚠️ 默认必须是 **False**：与端上 `TestResult.stabilityMeasured: Boolean = false` 同义
    # —— "没测过"不许默认成"测过了"，否则未来任何忘了赋值的新调用点都会被当成测过。
    stabilityMeasured: bool = False


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
    # 是否并入「越狱变体集」（200 条：20 个有害目标 × 10 类策略，见 jailbreak_cases.py）。
    # 默认 False：默认集是"每维度跑到就算过"的回归基线，变体集是"同意图换包装"的取证样本，
    # 目的不同、成本差一个量级（安全维度 18 → 218 条），必须由用户显式打开。
    # 字段名必须与端上 `BackendClient.SessionRequest.useJailbreakVariants` 逐字一致
    # —— 两端都忽略未知键，名字对不上是**静默丢弃**（变体集就是永远不跑，不报错）。
    useJailbreakVariants: bool = False
