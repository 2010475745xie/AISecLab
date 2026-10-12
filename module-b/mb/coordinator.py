"""调度 + 记账 + 推理的编排层（不含 HTTP）。

单独一层是为了**能离线测**：路由、门控、抽样复算、记账这些逻辑一旦和 HTTP 绑死，
就只能靠起服务 + 手打请求来验，覆盖不到「档位 0 时应该拒绝」这种分支。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

from .accounting import CreditLedger
from .backends import Backend
from .pool import NodePool, NodeState
from .protocol import ChatRequest, ProtocolError, build_completion, normalize_messages_for_compare
from .registry import ModelRegistry


@dataclass
class Coordinator:
    registry: ModelRegistry
    pool: NodePool
    ledger: CreditLedger
    backend: Backend
    started_at: float = 0.0
    log: list[str] = field(default_factory=list)

    # ── 对外主入口 ────────────────────────────────────────────────
    def chat(self, req: ChatRequest, consumer_id: str | None = None) -> dict:
        """一次 OpenAI 兼容对话。返回可直接序列化的响应体。"""
        consumer_id = consumer_id or "anonymous"
        try:
            entry = self.registry.require(req.model)
        except KeyError as e:
            raise ProtocolError(str(e), status=404, code="model_not_found") from e

        # 软上限：允许「消费 > 贡献」，透支到软上限后**降级**而不是直接拒绝（草案 §6）。
        downgraded_from = ""
        if self.ledger.over_soft_cap(consumer_id):
            alt = NodePool.downgrade_target(self.registry, exclude=[req.model])
            if alt is not None and alt.params_b < entry.params_b:
                downgraded_from, entry = entry.model_id, alt
                self._note(f"{consumer_id} 触达软上限 → 降级 {downgraded_from} → {entry.model_id}")
            else:
                raise ProtocolError(
                    f"额度透支已达软上限（{self.ledger.ledger(consumer_id).deficit} token），"
                    f"且池内没有更小的可降级模型 → 请稍后重试或贡献算力",
                    status=429, code="quota_exceeded")

        pick = self.pool.pick(entry.model_id)
        if pick.node is None:
            raise ProtocolError(
                "池内没有此刻能接单的节点：" + "；".join(pick.explain or ["（无节点注册）"]),
                status=503, code="no_capacity")

        node = pick.node
        self.pool.take_slot(node.node_id)
        try:
            result = self.backend.complete(entry.model_id, req.messages,
                                           req.temperature, req.max_tokens)
        finally:
            # 只释放一次；延迟另外喂 —— release_slot 里混进 latency 会让
            # 「失败路径也要放槽」和「成功路径才更新延迟」这两件事互相污染
            #（失败时没有 result，硬塞一个 0 会把滑动平均拉低，让这台机器看起来更快）。
            self.pool.release_slot(node.node_id)
        node.observe_latency(result.latency_ms)

        task_id = "task-" + uuid.uuid4().hex[:12]
        verified, verify_note = self._verify(entry, req, node, result.content)
        state = self.ledger.record_production(
            node.node_id, task_id, entry.model_id, result.completion_tokens, verified)
        self.ledger.record_consumption(consumer_id,
                                       result.prompt_tokens + result.completion_tokens)

        self._note(
            f"{consumer_id} → {entry.model_id} 落 {node.node_id}（{result.backend}）"
            f" 出 {result.completion_tokens} token，结算={state}"
            + (f"，{verify_note}" if verify_note else "")
            + (f"，已从 {downgraded_from} 降级" if downgraded_from else ""))

        resp = build_completion(
            entry.model_id, result.content, result.prompt_tokens, result.completion_tokens,
            usage_estimated=result.usage_estimated, backend=result.backend, node_id=node.node_id)
        resp["mb"].update({
            "roundtrip_ms": round(result.latency_ms, 1),
            "credit_state": state,
            "credit_note": verify_note,
            "downgraded_from": downgraded_from,
            "queue_explain": pick.explain,
        })
        return resp

    # ── 抽样复算（草案 §7）────────────────────────────────────────
    def _verify(self, entry, req: ChatRequest, served_by: NodeState,
                content: str) -> tuple[bool | None, str]:
        """按 x% 抽样，派给**另一个**节点复算。

        返回 `(None, ...)` = 无人复算 → 记 pending 不发放额度。
        一期只有单节点，这条分支是常态而不是异常；面板会把它显示成「待复算」。
        """
        if not self.ledger.should_verify():
            return None, "未抽中复算"
        verifier = self.pool.verifier_for(entry, served_by.node_id)
        if verifier is None:
            return None, "抽中复算但池内没有第二个可接同模型的节点 → 待复算（不发放额度）"

        self.pool.take_slot(verifier.node_id)
        try:
            second = self.backend.complete(entry.model_id, req.messages,
                                           req.temperature, req.max_tokens)
        finally:
            self.pool.release_slot(verifier.node_id)
        verifier.observe_latency(second.latency_ms)
        self.ledger.record_verify_cost(verifier.node_id,
                                       second.prompt_tokens + second.completion_tokens)

        agree = (normalize_messages_for_compare(second.content)
                 == normalize_messages_for_compare(content))
        return agree, f"复算节点 {verifier.node_id}：{'一致' if agree else '不一致 → 不计并降信任'}"

    # ── 管理面 ────────────────────────────────────────────────────
    def register_node(self, spec: dict) -> NodeState:
        """节点注册 / 心跳。二期手机走的就是这个入口 —— 一期只有本机自注册。"""
        node_id = str(spec.get("node_id") or "").strip()
        if not node_id:
            raise ProtocolError("node_id 必填")
        known = self.registry.ids()
        models = {}
        for mid, wh in (spec.get("models") or {}).items():
            if mid in known:
                models[mid] = str(wh).lower()
        node = NodeState(
            node_id=node_id,
            name=str(spec.get("name", "")),
            tier_level=int(spec.get("tier", 0)),
            cores=int(spec.get("cores", 4)),
            ram_mb=int(spec.get("ram_mb", 4096)),
            vram_mb=int(spec.get("vram_mb", 0)),
            models=models,
            charging=bool(spec.get("charging", False)),
            screen_off=bool(spec.get("screen_off", False)),
            wifi=bool(spec.get("wifi", False)),
            foreground=bool(spec.get("foreground", True)),
            daily_used_mb=float(spec.get("daily_used_mb", 0.0)),
        )
        self.ledger.ledger(node_id, node.name)
        return self.pool.upsert(node)

    def set_tier(self, node_id: str, level: int) -> NodeState:
        from .tiers import get_tier
        get_tier(level)  # 非法档位直接抛
        n = self.pool.heartbeat(node_id, tier_level=level)
        if n is None:
            raise ProtocolError(f"节点 {node_id} 未注册", status=404, code="node_not_found")
        self._note(f"节点 {node_id} 档位 → {level}（{get_tier(level).name}）")
        return n

    def state(self) -> dict:
        return {
            "registry": {"models": [m.to_dict() for m in self.registry.models.values()]},
            "pool": self.pool.snapshot(),
            "ledger": self.ledger.snapshot(),
            "backend": self.backend.describe(),
            "log": self.log[-40:],
        }

    def _note(self, line: str) -> None:
        self.log.append(line)
        if len(self.log) > 400:
            del self.log[:200]
