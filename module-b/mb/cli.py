"""启动模块 B 节点：`python -m mb.cli --help`

一期形态＝独立节点程序（草案 §9）：CLI + 极简本地面板，不做 Windows 版 App。
默认只绑 `127.0.0.1`：
- 真机联调用 `adb reverse tcp:<port> tcp:<port>` 就够（adb 反连的就是宿主 127.0.0.1），
  不必把服务暴露到整个局域网；
- 需要别的机器直连时显式 `--host 0.0.0.0` —— 这是用户主动选择，不是默认。
"""

from __future__ import annotations

import argparse
import os
import socket
import sys

from .accounting import CreditLedger
from .backends import StubBackend, llama_server, proxy_banner, upstream
from .coordinator import Coordinator
from .pool import NodePool
from .registry import ModelEntry, ModelRegistry, sha256_of_text
from .server import serve_forever
from .tiers import describe_tiers, get_tier

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_REGISTRY = os.path.join(ROOT, "configs", "default.json")
DEFAULT_STATE = os.path.join(ROOT, "var", "ledger.json")


def builtin_registry() -> ModelRegistry:
    """没给 configs/default.json 时的兜底模型表。

    ⚠️ 这里的 `weights_hash` 只是 **stub 占位**（对同一段标记文本取 sha256），
    真接本地权重时必须用 `--model-path` 让程序去算真实文件的 sha256 ——
    哈希是硬门槛（草案 §5），占位值只能用于桩后端联调。
    """
    r = ModelRegistry()
    r.add(ModelEntry(
        model_id="mb-stub-0.5b-q4_k_m", weights_hash=sha256_of_text("module-b/stub/0.5b"),
        quant="q4_k_m", params_b=0.5, min_ram_mb=700, min_vram_mb=0,
        node_tiers=("phone_low", "phone_full", "pc"), label="桩模型 0.5B（无真实权重）"))
    r.add(ModelEntry(
        model_id="mb-stub-7b-q4_k_m", weights_hash=sha256_of_text("module-b/stub/7b"),
        quant="q4_k_m", params_b=7.0, min_ram_mb=5000, min_vram_mb=0,
        node_tiers=("pc",), label="桩模型 7B（无真实权重）"))
    return r


def build_backend(a):
    if a.backend == "stub":
        return StubBackend()
    if a.backend == "llama":
        return llama_server(a.llama_url)
    if a.backend == "upstream":
        if not a.upstream_url:
            sys.exit("--backend upstream 需要 --upstream-url")
        return upstream(a.upstream_url, a.upstream_key, a.upstream_model)
    sys.exit(f"未知后端 {a.backend}")


def parse_args(argv=None):
    p = argparse.ArgumentParser(prog="mb", description="模块 B · 算力共享节点")
    p.add_argument("--host", default="127.0.0.1", help="监听地址（局域网用 0.0.0.0）")
    p.add_argument("--port", type=int, default=8100)
    p.add_argument("--node-id", default=socket.gethostname())
    p.add_argument("--node-name", default="")
    p.add_argument("--tier", type=int, default=3, choices=sorted({0, 1, 2, 3}),
                   help="贡献档位（一期上线 0 / 3）")
    p.add_argument("--cores", type=int, default=max(2, (os.cpu_count() or 4)))
    p.add_argument("--ram-mb", type=int, default=8192)
    p.add_argument("--vram-mb", type=int, default=0)

    p.add_argument("--backend", default="stub", choices=["stub", "llama", "upstream"])
    p.add_argument("--llama-url", default="http://127.0.0.1:8080/v1",
                   help="本地 llama-server 的 OpenAI 兼容地址")
    p.add_argument("--upstream-url", default="", help="开发期借用的外部端点（算力不在本机）")
    p.add_argument("--upstream-key", default="")
    p.add_argument("--upstream-model", default="")

    p.add_argument("--registry", default=DEFAULT_REGISTRY)
    p.add_argument("--model-id", default="", help="本节点加载的模型 id（须在注册表里）")
    p.add_argument("--model-path", default="", help="权重文件；给了就取真实 sha256")
    p.add_argument("--weights-hash", default="", help="权重 sha256（与 --model-path 二选一）")

    p.add_argument("--api-key", default="", help="对外要求客户端携带的 Key；空=不校验")
    p.add_argument("--state", default=DEFAULT_STATE, help="账本落盘路径")
    p.add_argument("--no-state", action="store_true")
    return p.parse_args(argv)


def resolve_model(a, registry: ModelRegistry) -> tuple[str, str]:
    """确定这台节点「加载了哪个模型」并返回 (model_id, weights_hash)。"""
    model_id = a.model_id or (registry.ids()[0] if registry.ids() else "")
    if not model_id:
        sys.exit("注册表是空的：用 --model-id 或先准备 configs/default.json")
    entry = registry.get(model_id)
    if a.model_path:
        import hashlib
        h = hashlib.sha256()
        with open(a.model_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        got = h.hexdigest()
        if entry is not None and entry.weights_hash != got:
            print(f"[警告] {model_id} 的权重哈希与注册表不一致：\n"
                  f"        本机 {got}\n        注册表 {entry.weights_hash}\n"
                  f"        → 本节点将无法接这个模型的单（这正是 §5 要拦的情况）。")
        return model_id, got
    if a.weights_hash:
        return model_id, a.weights_hash.lower()
    if entry is not None:
        return model_id, entry.weights_hash
    sys.exit(f"模型 {model_id} 不在注册表里，且没给 --model-path / --weights-hash")


def main(argv=None) -> int:
    a = parse_args(argv)

    registry = ModelRegistry()
    if os.path.exists(a.registry):
        registry.load(a.registry)
    else:
        registry = builtin_registry()
        print(f"[提示] 未找到注册表 {a.registry}，使用内置桩模型表（仅用于联调）")

    model_id, weights_hash = resolve_model(a, registry)

    c = Coordinator(
        registry=registry,
        pool=NodePool(registry),
        ledger=CreditLedger() if a.no_state else CreditLedger.load(a.state),
        backend=build_backend(a),
    )
    c.register_node({
        "node_id": a.node_id, "name": a.node_name or a.node_id, "tier": a.tier,
        "cores": a.cores, "ram_mb": a.ram_mb, "vram_mb": a.vram_mb,
        "models": {model_id: weights_hash},
        "charging": True, "wifi": True, "screen_off": False, "foreground": True,
    })
    c.started_at = __import__("time").time()

    def persist():
        if not a.no_state:
            c.ledger.save(a.state)

    tier = get_tier(a.tier)
    base = f"http://{'127.0.0.1' if a.host == '0.0.0.0' else a.host}:{a.port}/v1"
    print("=" * 68)
    print("模块 B · 算力共享节点")
    print(f"  节点      {a.node_id}（档位 {tier.level} · {tier.name}）")
    print(f"  后端      {c.backend.name}" + ("" if c.backend.is_local else "  ⚠ 算力不在本机"))
    print(f"  {proxy_banner()}")
    print(f"  模型      {model_id}  hash={weights_hash[:12]}…")
    print(f"  端点      {base}          ← 填进模块 A 的 CONFIG 表单")
    print(f"  面板      http://127.0.0.1:{a.port}/admin/panel")
    print(f"  档位可选  " + " | ".join(describe_tiers()))
    print("=" * 68)

    try:
        serve_forever(c, a.host, a.port, api_key=a.api_key, on_change=persist)
    except KeyboardInterrupt:
        print("\n收到中断，落盘账本…")
        persist()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
