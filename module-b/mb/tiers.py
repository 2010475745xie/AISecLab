"""贡献档位：用户「愿意出多少算力」的三个旋钮 + 预置组合。

草案 §3 的拍板：**不是一个滑块，而是三个独立旋钮**（速率 / 触发条件 / 资源上限），
然后给出几个预置组合供用户一键选。这里的 [Tier] 就是那几个预置组合。

为什么手机默认必须是档位 1 而不是 2（草案 §3 的取舍，照抄过来免得后人再改回去）：
默认给全速，第一次发热降频 + 流量提醒就会把用户赶走。手机默认档必须是
「用户感觉不到」的那一档，升档由用户主动做。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence


@dataclass(frozen=True)
class Tier:
    """一个预置档位。

    [triggers] 是 **「与或」结构**：外层是「或」，内层是「与」。
    例如档位 2 的 `(("foreground",), ("charging", "wifi"))` 表示
    「前台时」**或**「充电且有 Wi-Fi 时」。空的外层 = 无触发条件（PC 档）。
    """

    level: int
    name: str
    concurrency: int            # ① 速率：可同时接几个任务；0 表示不限
    max_params_b: float         # ③ 资源上限：可常驻的最大参数量（十亿）
    daily_mb: int               # ③ 资源上限：日流量上限（MB）；0 表示不限
    triggers: tuple[tuple[str, ...], ...] = ()
    note: str = ""

    @property
    def accepts_tasks(self) -> bool:
        """档位 0 = 关闭，永远不接单。"""
        return self.level > 0

    def concurrency_cap(self, cores: int) -> int:
        """把「不限」解析成一个具体上限，避免上游把节点打爆。"""
        return cores if self.concurrency == 0 else self.concurrency


TIERS: dict[int, Tier] = {
    0: Tier(0, "关闭", 0, 0.0, 0, (), "不接单；模块 B 完全静默"),
    1: Tier(1, "零打扰", 1, 0.5, 200,
            (("charging", "wifi", "screen_off"),), "手机默认：充电 + Wi-Fi + 息屏"),
    2: Tier(2, "全速", 0, 3.0, 0,
            (("foreground",), ("charging", "wifi")), "用户主动升：前台，或充电 + Wi-Fi"),
    3: Tier(3, "PC 档", 4, 13.0, 0, (), "PC 默认：不限条件，可做仲裁节点"),
}

# 一期只上线 0 / 3（草案 §10）。1 / 2 的定义先留着 —— 它们是在**设计**里拍过板的，
# 删掉会让后人以为「手机只能全速或关闭」，而真实约束是能力分层不是二值开关。
PHASE1_TIERS = (0, 3)

# 档位的**协议键**：模型注册表（registry.ModelEntry.node_tiers）里写的是这些键。
# 不直接拿 `name`（中文展示名）当键 —— 展示名会被改（文案会调），键一改就静默失配。
TIER_KEYS: dict[int, str] = {0: "off", 1: "phone_low", 2: "phone_full", 3: "pc"}


def tier_key(level: int) -> str:
    if level not in TIER_KEYS:
        raise ValueError(f"未定义的档位 {level}；可用：{sorted(TIER_KEYS)}")
    return TIER_KEYS[level]



def get_tier(level: int) -> Tier:
    if level not in TIERS:
        raise ValueError(f"未定义的档位 {level}；可用：{sorted(TIERS)}")
    return TIERS[level]


def triggers_satisfied(tier: Tier, node: Mapping[str, object]) -> bool:
    """节点的当前状态是否满足该档位的触发条件。

    [node] 是节点自报的状态（charging / wifi / screen_off / foreground）。
    字段缺失按 False 处理 —— 「不知道算不算充电」不能默认成立，
    否则一个不上报状态的节点会静默变成「随时可接单」。
    """
    if not tier.triggers:
        return True
    for group in tier.triggers:
        if all(bool(node.get(k, False)) for k in group):
            return True
    return False


@dataclass(frozen=True)
class Gate:
    """门控结论。带 [reason] 是为了能在面板上直接告诉用户「为什么现在不接单」——
    只说「不可用」会让人以为是 bug，而这里十有八九是他自己的档位设置。"""

    ok: bool
    reason: str = ""

    def __bool__(self) -> bool:  # 方便测试里直接 assert gate
        return self.ok


def gate(
    tier: Tier,
    node: Mapping[str, object],
    *,
    params_b: float,
    daily_used_mb: float,
    in_flight: int,
    cores: int = 4,
) -> Gate:
    """一个节点此刻能不能接「参数量 params_b 的这个模型」的活。"""
    if not tier.accepts_tasks:
        return Gate(False, f"档位 {tier.level}（{tier.name}）不接单")
    if params_b > tier.max_params_b:
        return Gate(False, f"模型 {params_b}B 超出档位 {tier.level} 的上限 {tier.max_params_b}B")
    if not triggers_satisfied(tier, node):
        want = " 或 ".join("+".join(g) for g in tier.triggers)
        return Gate(False, f"触发条件未满足（需要 {want}）")
    if tier.daily_mb and daily_used_mb >= tier.daily_mb:
        return Gate(False, f"日流量已用尽（{daily_used_mb:.0f}/{tier.daily_mb} MB）")
    cap = tier.concurrency_cap(cores)
    if in_flight >= cap:
        return Gate(False, f"并发已满（{in_flight}/{cap}）")
    return Gate(True)


def describe_tiers(levels: Iterable[int] = PHASE1_TIERS) -> Sequence[str]:
    return [f"{TIERS[l].level} · {TIERS[l].name}（{TIERS[l].note}）" for l in levels]
