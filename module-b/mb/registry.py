"""模型注册表：`model_id` → 权重指纹 / 量化 / 参数量 / 最低内存 / 允许接单的档位。

草案 §5 把字段口径**先定死**，理由照抄在这儿：请求路由的每一步都靠这些字段，
字段一旦漂移（比如把 `weights_hash` 当可选），就会出现「同一个模型名、不同量化版本
混在一个池子里」的情况 —— 那时输出质量不一致且**无法归因**，排查成本远高于现在多写几行校验。

所以 `weights_hash` 是硬门槛：不是 64 位十六进制的 sha256 直接拒绝加载，
`load()` 时会抛错而不是静默降级。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from typing import Iterable, Mapping

_HEX64 = re.compile(r"^[0-9a-f]{64}$")


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_of_text(text: str) -> str:
    return sha256_of(text.encode("utf-8"))


@dataclass(frozen=True)
class ModelEntry:
    model_id: str
    weights_hash: str            # sha256，64 位小写十六进制
    quant: str                   # q4_k_m / q5_k_m / fp16 …
    params_b: float              # 参数量（十亿）
    min_ram_mb: int
    min_vram_mb: int             # 0 = 纯 CPU 可用
    node_tiers: tuple[str, ...]  # 允许接单的档位名，如 ("phone_full", "pc")
    label: str = ""
    file_name: str = ""          # 权重文件名（权重只下载一次并校验 hash）

    def validate(self) -> None:
        if not _HEX64.match(self.weights_hash):
            raise ValueError(
                f"模型 {self.model_id} 的 weights_hash 不是 sha256（64 位小写十六进制）："
                f"{self.weights_hash!r}。这是硬门槛，见设计草案 §5 —— "
                f"不同量化版本必须视为不同模型。"
            )
        if self.params_b <= 0:
            raise ValueError(f"模型 {self.model_id} 的 params_b 必须为正")
        if not self.node_tiers:
            raise ValueError(f"模型 {self.model_id} 未声明任何可接单档位")
        # `off`（档位 0 = 用户关闭）永远不能出现在 node_tiers 里：
        # 档位 0 是「这台机器此刻不接单」，不是模型的一种属性。
        # 写进来会变成一个永假的配置项，还会把拒绝理由污染成一句让人看不懂的话。
        bad = [t for t in self.node_tiers if t == "off"]
        if bad:
            raise ValueError(
                f"模型 {self.model_id} 的 node_tiers 里不能有 'off' —— "
                f"档位 0 表示节点关闭，是节点状态而不是模型属性"
            )

    @property
    def display(self) -> str:
        return self.label or self.model_id

    def fits(self, ram_mb: int, vram_mb: int) -> bool:
        """这台机器装得下吗。显存要求 > 0 时必须真有显存。"""
        if ram_mb < self.min_ram_mb:
            return False
        if self.min_vram_mb > 0 and vram_mb < self.min_vram_mb:
            return False
        return True

    def tier_allows(self, tier_name: str) -> bool:
        return tier_name in self.node_tiers

    @classmethod
    def from_dict(cls, d: Mapping[str, object]) -> "ModelEntry":
        return cls(
            model_id=str(d["model_id"]),
            weights_hash=str(d["weights_hash"]).lower(),
            quant=str(d.get("quant", "")),
            params_b=float(d["params_b"]),
            min_ram_mb=int(d.get("min_ram_mb", 0)),
            min_vram_mb=int(d.get("min_vram_mb", 0)),
            node_tiers=tuple(str(t) for t in d.get("node_tiers", ())),
            label=str(d.get("label", "")),
            file_name=str(d.get("file_name", "")),
        )

    def to_dict(self) -> dict:
        return {
            "model_id": self.model_id,
            "weights_hash": self.weights_hash,
            "quant": self.quant,
            "params_b": self.params_b,
            "min_ram_mb": self.min_ram_mb,
            "min_vram_mb": self.min_vram_mb,
            "node_tiers": list(self.node_tiers),
            "label": self.label,
            "file_name": self.file_name,
        }


@dataclass
class ModelRegistry:
    models: dict[str, ModelEntry] = field(default_factory=dict)

    def add(self, entry: ModelEntry) -> None:
        entry.validate()
        self.models[entry.model_id] = entry

    def get(self, model_id: str) -> ModelEntry | None:
        return self.models.get(model_id)

    def require(self, model_id: str) -> ModelEntry:
        entry = self.get(model_id)
        if entry is None:
            known = ", ".join(sorted(self.models)) or "（空）"
            raise KeyError(f"模型 {model_id!r} 不在注册表里。已注册：{known}")
        return entry

    def ids(self) -> list[str]:
        return sorted(self.models)

    def load(self, path: str) -> "ModelRegistry":
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        for d in raw.get("models", []):
            self.add(ModelEntry.from_dict(d))
        return self

    def save(self, path: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"models": [m.to_dict() for m in self.models.values()]},
                      f, ensure_ascii=False, indent=2)

    @classmethod
    def from_entries(cls, entries: Iterable[ModelEntry]) -> "ModelRegistry":
        r = cls()
        for e in entries:
            r.add(e)
        return r
