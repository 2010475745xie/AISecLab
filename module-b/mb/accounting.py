"""记账：**记账单位 = 通过抽样复算验证的输出 token 数**（草案 §6 拍板）。

为什么不能用自报 FLOPs / 自报时长：把档位拉到最大然后摸鱼，成本为零。
所以 credit 只认**别人复算过、结果一致**的产出 —— 而抽样复算本来就要做（§7），
一套机制两用，不额外增加开销。

一期必然出现 [pending_tokens] 持续上涨且 [verified_tokens] 恒为 0：
池子里只有一台机器，没有第二个节点能复算（草案 §11 风险 2「冷启动空池」）。
这是**如实呈现**而不是缺陷 —— 面板会把「待复算」单独列出来，
绝不把它算进已发放额度里假装验证过了。
"""

from __future__ import annotations

import json
import os
import random
from dataclasses import asdict, dataclass, field

# 新设备赠初始额度：解决「没贡献就没额度、没额度就没法用」的死锁（草案 §6）。
INITIAL_GRANT = 20_000
# 软上限：允许「消费 > 贡献」，但透支超过这个量就不再给大模型，降级到池内最小模型或排队。
SOFT_CAP = 20_000
# 抽样复算比例起步值（草案 §7）：代价 = 算力 ×(1+x)。
DEFAULT_SAMPLING = 0.08


@dataclass
class NodeLedger:
    node_id: str
    label: str = ""
    initial_grant: int = INITIAL_GRANT
    verified_tokens: int = 0     # 已发放：通过复算
    pending_tokens: int = 0      # 已产出但**没有**第二个节点复算 → 不发放
    consumed_tokens: int = 0     # 作为消费者用掉的
    verified_tasks: int = 0
    pending_tasks: int = 0
    rejected_tasks: int = 0      # 复算不一致 → 不计，且降信任
    verify_cost_tokens: int = 0  # 为「验别人」花掉的算力（草案 §7：代价 = 算力 ×(1+x)）
    trust: float = 1.0

    @property
    def balance(self) -> int:
        """可用额度（单位 token）。可以是负数 —— 那是「先欠着」，不是错误。"""
        return self.initial_grant + self.verified_tokens - self.consumed_tokens

    @property
    def deficit(self) -> int:
        """超出「初始额度 + 已验证贡献」的透支量。"""
        return max(0, self.consumed_tokens - self.verified_tokens - self.initial_grant)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["balance"] = self.balance
        d["deficit"] = self.deficit
        return d


@dataclass
class PendingTask:
    task_id: str
    node_id: str
    model_id: str
    tokens: int


@dataclass
class CreditLedger:
    sampling: float = DEFAULT_SAMPLING
    soft_cap: int = SOFT_CAP
    ledgers: dict[str, NodeLedger] = field(default_factory=dict)
    pending: dict[str, PendingTask] = field(default_factory=dict)
    rng: random.Random = field(default_factory=random.Random, repr=False)

    def ledger(self, node_id: str, label: str = "") -> NodeLedger:
        if node_id not in self.ledgers:
            self.ledgers[node_id] = NodeLedger(node_id=node_id, label=label)
        return self.ledgers[node_id]

    # ── 抽样 ──────────────────────────────────────────────────────
    def should_verify(self) -> bool:
        return self.rng.random() < self.sampling

    # ── 产出（供给侧）─────────────────────────────────────────────
    def record_production(self, node_id: str, task_id: str, model_id: str,
                          tokens: int, verified: bool | None) -> str:
        """记一次产出。返回结算状态：`verified` / `rejected` / `pending`。

        [verified] 三态：True 复算一致、False 复算不一致、None 无人复算（一期常态）。
        """
        lg = self.ledger(node_id)
        if verified is True:
            lg.verified_tokens += tokens
            lg.verified_tasks += 1
            lg.trust = min(1.0, lg.trust + 0.01)
            self.pending.pop(task_id, None)
            return "verified"
        if verified is False:
            lg.rejected_tasks += 1
            lg.trust = max(0.0, lg.trust - 0.25)
            self.pending.pop(task_id, None)
            return "rejected"
        lg.pending_tokens += tokens
        lg.pending_tasks += 1
        self.pending[task_id] = PendingTask(task_id, node_id, model_id, tokens)
        return "pending"

    def settle(self, task_id: str, agree: bool) -> str | None:
        """复算结果回来了：把一条 pending 结算掉。"""
        p = self.pending.get(task_id)
        if p is None:
            return None
        lg = self.ledger(p.node_id)
        lg.pending_tokens = max(0, lg.pending_tokens - p.tokens)
        lg.pending_tasks = max(0, lg.pending_tasks - 1)
        return self.record_production(p.node_id, task_id, p.model_id, p.tokens, agree)

    # ── 消费（需求侧）─────────────────────────────────────────────
    def record_consumption(self, node_id: str, tokens: int) -> None:
        self.ledger(node_id).consumed_tokens += tokens

    def record_verify_cost(self, node_id: str, tokens: int) -> None:
        """验别人花掉的算力。不计入 credit（它不是产出），但要让成本可见 ——
        否则「抽样复算的代价 = 算力 ×(1+x)」这句就只存在于文档里。"""
        self.ledger(node_id).verify_cost_tokens += tokens

    def over_soft_cap(self, node_id: str) -> bool:
        return self.ledger(node_id).deficit >= self.soft_cap

    # ── 展示 / 落盘 ───────────────────────────────────────────────
    def snapshot(self) -> dict:
        return {
            "unit": "通过抽样复算验证的输出 token",
            "sampling": self.sampling,
            "soft_cap": self.soft_cap,
            "nodes": {k: v.to_dict() for k, v in sorted(self.ledgers.items())},
            "pending_tasks": [asdict(p) for p in self.pending.values()],
        }

    def save(self, path: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"sampling": self.sampling, "soft_cap": self.soft_cap,
                       "ledgers": {k: asdict(v) for k, v in self.ledgers.items()}},
                      f, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: str) -> "CreditLedger":
        lg = cls()
        if not os.path.exists(path):
            return lg
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        lg.sampling = float(raw.get("sampling", DEFAULT_SAMPLING))
        lg.soft_cap = int(raw.get("soft_cap", SOFT_CAP))
        for k, d in raw.get("ledgers", {}).items():
            lg.ledgers[k] = NodeLedger(**d)
        return lg
