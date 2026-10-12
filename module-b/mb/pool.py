"""节点池：谁在线、谁能接这单、这一单派给谁。

草案 §5 的路由链在这里落地：

    模型注册表 → 能力过滤 → 候选集里按 负载 / 信任 / 延迟 排序 → 派单

一条刻意的设计（草案 §2 的结论）：**不写设备打分逻辑**。
「能装下多大的模型」本身就是能力标签 —— 模型注册表按参数量天然实现了能力分派，
再叠一套 `device_score = f(cpu, ram, benchmark)` 只会得到两个都在漂移的排序源。
所以 [NodePool.eligible] 用的全是**硬条件**（装得下吗 / hash 对得上吗 / 档位允许吗 / 触发条件满足吗），
只有「多个节点都能接」时才用软指标排序。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, replace
from typing import Iterable

from .registry import ModelEntry, ModelRegistry
from .tiers import Gate, gate, get_tier, tier_key


@dataclass
class NodeState:
    """一个节点**自报**的运行时状态。

    [models] 是 `model_id -> 该节点持有的权重 sha256`。必须由节点上报而不是查表：
    同一个 model_id 在不同节点上完全可能是不同量化版本，而那正是要拦的情况。
    """

    node_id: str
    name: str = ""
    tier_level: int = 0
    cores: int = 4
    ram_mb: int = 4096
    vram_mb: int = 0
    models: dict[str, str] = field(default_factory=dict)

    # ② 触发条件要用的自报状态
    charging: bool = False
    screen_off: bool = False
    wifi: bool = False
    foreground: bool = True

    daily_used_mb: float = 0.0
    in_flight: int = 0
    latency_ms: float = 0.0     # 指数滑动平均
    trust: float = 1.0          # 0..1，抽样复算不一致时下调
    last_seen: float = field(default_factory=time.time)

    @property
    def tier(self):
        return get_tier(self.tier_level)

    @property
    def load_ratio(self) -> float:
        cap = self.tier.concurrency_cap(self.cores)
        return self.in_flight / cap if cap else 1.0

    def holds(self, entry: ModelEntry) -> bool:
        """该节点确实持有**同一份权重**吗。"""
        return self.models.get(entry.model_id) == entry.weights_hash

    def gate_for(self, entry: ModelEntry) -> Gate:
        # 顺序有讲究：**档位 0（用户手动关闭）必须最先判**。
        # 放到 `tier_allows` 后面的话，拒绝理由会变成「注册表不允许档位 off 接单」——
        # 而 `off` 压根不该出现在模型的 node_tiers 里（入口 validate 已拦），
        # 于是用户看到的是一句莫名其妙的话，而不是他真正需要的「你把贡献关了」。
        if not self.tier.accepts_tasks:
            return Gate(False, f"档位 {self.tier_level}（{self.tier.name}）不接单")
        if entry.model_id not in self.models:
            return Gate(False, "该模型未加载")
        if not self.holds(entry):
            return Gate(False, f"权重指纹不一致（本地 {self.models[entry.model_id][:12]}… ≠ "
                               f"注册表 {entry.weights_hash[:12]}…）")
        if not entry.fits(self.ram_mb, self.vram_mb):
            return Gate(False, f"内存/显存不足（需 {entry.min_ram_mb}MB"
                               + (f" + {entry.min_vram_mb}MB 显存" if entry.min_vram_mb else "")
                               + f"，本机 {self.ram_mb}MB"
                               + (f" + {self.vram_mb}MB 显存" if self.vram_mb else "") + "）")
        key = tier_key(self.tier_level)
        if not entry.tier_allows(key):
            return Gate(False, f"注册表不允许档位 {key} 接单（允许：{'、'.join(entry.node_tiers)}）")
        return gate(
            self.tier,
            {"charging": self.charging, "screen_off": self.screen_off,
             "wifi": self.wifi, "foreground": self.foreground},
            params_b=entry.params_b,
            daily_used_mb=self.daily_used_mb,
            in_flight=self.in_flight,
            cores=self.cores,
        )

    def observe_latency(self, ms: float, alpha: float = 0.3) -> None:
        self.latency_ms = ms if self.latency_ms <= 0 else (1 - alpha) * self.latency_ms + alpha * ms

    def to_dict(self) -> dict:
        return {
            "node_id": self.node_id, "name": self.name,
            "tier": self.tier_level, "tier_key": tier_key(self.tier_level),
            "tier_name": self.tier.name, "cores": self.cores,
            "ram_mb": self.ram_mb, "vram_mb": self.vram_mb,
            "models": dict(self.models),
            "charging": self.charging, "screen_off": self.screen_off,
            "wifi": self.wifi, "foreground": self.foreground,
            "daily_used_mb": round(self.daily_used_mb, 1),
            "in_flight": self.in_flight, "latency_ms": round(self.latency_ms, 1),
            "trust": round(self.trust, 3),
            "last_seen_ms_ago": int((time.time() - self.last_seen) * 1000),
        }


@dataclass
class Pick:
    """派单结果。[explain] 是给面板/日志看的全量候选与理由 ——
    「为什么落到了这台」必须能答，否则池子一多就没法调。"""

    node: NodeState | None
    explain: list[str] = field(default_factory=list)


class NodePool:
    def __init__(self, registry: ModelRegistry, stale_after_s: float = 90.0):
        self.registry = registry
        self.stale_after_s = stale_after_s
        self.nodes: dict[str, NodeState] = {}

    # ── 注册 / 心跳 ────────────────────────────────────────────────
    def upsert(self, node: NodeState) -> NodeState:
        node.last_seen = time.time()
        self.nodes[node.node_id] = node
        return node

    def heartbeat(self, node_id: str, **fields) -> NodeState | None:
        n = self.nodes.get(node_id)
        if n is None:
            return None
        for k, v in fields.items():
            if v is not None:
                setattr(n, k, v)
        n.last_seen = time.time()
        return n

    def prune_stale(self, now: float | None = None) -> list[str]:
        now = now if now is not None else time.time()
        dead = [nid for nid, n in self.nodes.items() if now - n.last_seen > self.stale_after_s]
        for nid in dead:
            del self.nodes[nid]
        return dead

    def get(self, node_id: str) -> NodeState | None:
        return self.nodes.get(node_id)

    # ── 能力过滤 ──────────────────────────────────────────────────
    def candidates(self, entry: ModelEntry) -> tuple[list[NodeState], list[str]]:
        ok: list[NodeState] = []
        explain: list[str] = []
        for n in sorted(self.nodes.values(), key=lambda x: x.node_id):
            g = n.gate_for(entry)
            explain.append(f"{n.node_id}（档位 {n.tier_level}）: {'可接单' if g.ok else '不可 —— ' + g.reason}")
            if g.ok:
                ok.append(n)
        return ok, explain

    def pick(self, model_id: str) -> Pick:
        """为一次请求挑节点。

        排序键（草案 §5 的顺序）：负载率 → 信任分 → 延迟。
        负载放第一位是因为「排队」比「挑个快但已经在忙的」更伤体验；
        信任分是抽样复算的产物，只在负载相同时才用来拆平局。
        """
        entry = self.registry.require(model_id)
        ok, explain = self.candidates(entry)
        if not ok:
            return Pick(None, explain)
        ok.sort(key=lambda n: (round(n.load_ratio, 3), -n.trust, n.latency_ms, n.node_id))
        return Pick(ok[0], explain)

    def verifier_for(self, entry: ModelEntry, requester_id: str) -> NodeState | None:
        """找一个**别的**节点来复算（草案 §7 的抽样复算）。

        一期只有单节点 → 必然返回 None，credit 记 pending 不发放。
        这不是 bug，是草案 §11 风险 2「冷启动空池」在数据上的样子：
        单节点池没有互相验证的可能，界面必须把这件事显示出来而不是假装算过了。
        """
        ok, _ = self.candidates(entry)
        others = [n for n in ok if n.node_id != requester_id]
        if not others:
            return None
        others.sort(key=lambda n: (-n.tier_level, n.load_ratio, -n.trust))
        return others[0]

    def snapshot(self) -> list[dict]:
        return [n.to_dict() for n in sorted(self.nodes.values(), key=lambda x: x.node_id)]

    def take_slot(self, node_id: str) -> None:
        n = self.nodes.get(node_id)
        if n:
            n.in_flight += 1

    def release_slot(self, node_id: str, latency_ms: float | None = None,
                     traffic_mb: float = 0.0) -> None:
        n = self.nodes.get(node_id)
        if not n:
            return
        n.in_flight = max(0, n.in_flight - 1)
        n.daily_used_mb += traffic_mb
        if latency_ms is not None:
            n.observe_latency(latency_ms)

    @staticmethod
    def downgrade_target(registry: ModelRegistry, exclude: Iterable[str] = ()) -> ModelEntry | None:
        """软上限后的降级目标：池子里**最小**的那个模型（草案 §6「降级到小模型或排队」）。"""
        avail = [e for k, e in registry.models.items() if k not in set(exclude)]
        if not avail:
            return None
        return min(avail, key=lambda e: e.params_b)
