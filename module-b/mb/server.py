"""HTTP 层：OpenAI 兼容端点 + 极简本地面板（草案 §9：PC 侧做独立节点程序，不做 Windows 版 App）。

只做薄薄一层 —— 所有判断都在 [Coordinator] 里，因为它要能被离线测试覆盖。
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .coordinator import Coordinator
from .protocol import ProtocolError, error_body, parse_chat_request

ADMIN_TOKEN = ""  # 面板不鉴权：只绑本机/局域网，且面板只能改档位与看账本


class Handler(BaseHTTPRequestHandler):
    server_version = "ModuleB/0.1"
    coordinator: Coordinator = None  # type: ignore[assignment]
    api_key: str = ""
    on_change = None  # 档位变更后的回调（落盘用）

    # ── 工具 ─────────────────────────────────────────────────────
    def _json(self, code: int, obj) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _html(self, code: int, html: str) -> None:
        data = html.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception as e:
            raise ProtocolError(f"请求体不是合法 JSON：{e}") from e

    def _authorized(self) -> bool:
        """只保护 /v1/*。Key 未配置时不校验 —— 本地 llama-server 不带 Key，
        而模块 A 端在 Key 为空时**不发 Authorization 头**（见 OpenAiCompatibleClient）。"""
        if not self.api_key:
            return True
        got = (self.headers.get("Authorization") or "").strip()
        return got == f"Bearer {self.api_key}"

    def log_message(self, fmt, *args):  # 别把 BaseHTTPRequestHandler 的日志刷屏
        pass

    # ── GET ──────────────────────────────────────────────────────
    def do_GET(self):
        # 和 do_POST 一样必须有兜底：BaseHTTPRequestHandler 的 handler 抛异常时
        # 是**直接断连接**（客户端看到 RemoteDisconnected），不是 500。
        # 一个面板渲染的小错就会表现成「服务挂了」，排查成本极高。
        path = self.path.split("?")[0]
        try:
            if path in ("/healthz", "/"):
                node = self.coordinator.state()
                return self._json(200, {"ok": True, "service": "module-b",
                                        "nodes": len(node["pool"]),
                                        "models": len(node["registry"]["models"])})
            if path == "/v1/models":
                if not self._authorized():
                    return self._json(401, error_body("无效的 API Key", "invalid_api_key", 401))
                data = [{"id": m.model_id, "object": "model", "created": 0,
                         "owned_by": "module-b",
                         "mb": {"params_b": m.params_b, "quant": m.quant,
                                "weights_hash": m.weights_hash[:12]}}
                        for m in self.coordinator.registry.models.values()]
                return self._json(200, {"object": "list", "data": data})
            if path == "/admin/state":
                return self._json(200, self.coordinator.state())
            if path == "/admin/panel":
                return self._html(200, _panel_html(self.coordinator))
        except Exception as e:  # noqa: BLE001
            return self._json(500, error_body(f"{type(e).__name__}: {e}", "internal_error", 500))
        return self._json(404, error_body(f"未知路径 {path}", "not_found", 404))

    # ── POST ─────────────────────────────────────────────────────
    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            if path.endswith("/chat/completions"):
                if not self._authorized():
                    return self._json(401, error_body("无效的 API Key", "invalid_api_key", 401))
                req = parse_chat_request(self._body())
                consumer = (self.headers.get("X-MB-Node") or "anonymous").strip() or "anonymous"
                return self._json(200, self.coordinator.chat(req, consumer))
            if path == "/admin/nodes":
                node = self.coordinator.register_node(self._body())
                return self._json(200, node.to_dict())
            if path == "/admin/tier":
                b = self._body()
                node = self.coordinator.set_tier(str(b.get("node_id")), int(b.get("tier")))
                if self.on_change:
                    self.on_change()
                return self._json(200, node.to_dict())
        except ProtocolError as e:
            return self._json(e.status, error_body(str(e), e.code, e.status))
        except Exception as e:  # noqa: BLE001 —— 兜底成 500，别让线程静默死掉
            return self._json(500, error_body(f"{type(e).__name__}: {e}", "internal_error", 500))
        return self._json(404, error_body(f"未知路径 {path}", "not_found", 404))


def make_server(coordinator: Coordinator, host: str, port: int,
                api_key: str = "", on_change=None) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {
        "coordinator": coordinator, "api_key": api_key, "on_change": on_change})
    srv = ThreadingHTTPServer((host, port), handler)
    srv.daemon_threads = True
    return srv


def serve_forever(coordinator: Coordinator, host: str, port: int,
                  api_key: str = "", on_change=None) -> None:
    srv = make_server(coordinator, host, port, api_key, on_change)
    srv.serve_forever()


def serve_in_thread(coordinator: Coordinator, host: str, port: int,
                    api_key: str = "", on_change=None) -> ThreadingHTTPServer:
    """给测试/自检用：后台线程起服务，主线程继续跑。"""
    srv = make_server(coordinator, host, port, api_key, on_change)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ── 极简本地面板 ─────────────────────────────────────────────────
def _panel_html(c: Coordinator) -> str:
    st = c.state()
    pool = st["pool"]
    lg = st["ledger"]

    def node_rows() -> str:
        if not pool:
            return '<tr><td colspan="7" class="dim">（无节点注册）</td></tr>'
        out = []
        for n in pool:
            # 先复制再覆盖：模板里 `{models}` 要显示的是摘要串，而 `n` 里同名的
            # `models` 是原始字典 —— 直接 `.format(models=..., **n)` 会
            # `TypeError: got multiple values for keyword argument 'models'`。
            row = dict(n)
            row["models"] = "、".join(f"{k}({v[:8]})" for k, v in n["models"].items()) or "—"
            out.append(
                "<tr><td>{node_id}</td><td>{name}</td><td><b>{tier}</b> {tier_name}</td>"
                "<td>{models}</td><td>{ram_mb}/{vram_mb}MB</td>"
                "<td>{in_flight} 在跑 · {latency_ms:.0f}ms</td>"
                "<td>信任 {trust:.2f}</td></tr>".format(**row))
        return "\n".join(out)

    def ledger_rows() -> str:
        if not lg["nodes"]:
            return '<tr><td colspan="6" class="dim">（无账本）</td></tr>'
        out = []
        for nid, v in lg["nodes"].items():
            out.append(
                "<tr><td>{node_id}</td><td>{balance:,}</td><td>{verified_tokens:,}</td>"
                "<td class=\"warn\">{pending_tokens:,}</td><td>{consumed_tokens:,}</td>"
                "<td>{verify_cost_tokens:,}</td></tr>".format(**v))
        return "\n".join(out)

    log = "\n".join(f"<div>{ln}</div>" for ln in reversed(st["log"][-25:])) or '<div class="dim">—</div>'
    back = st["backend"]
    local_note = ("算力在本机" if back.get("local") else
                  '<span class="warn">⚠ 算力不在本机（转发到外部端点，仅供开发联调）</span>')
    pending_total = sum(v["pending_tokens"] for v in lg["nodes"].values())

    return f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>模块 B · 节点面板</title><style>
body{{font-family:-apple-system,"Microsoft YaHei",sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#1a1a1a;line-height:1.6}}
h1{{font-size:20px;border-bottom:2px solid #eee;padding-bottom:8px}}
h2{{font-size:15px;margin-top:24px;color:#444}}
table{{width:100%;border-collapse:collapse;margin:8px 0;font-size:13px}}
th,td{{border:1px solid #e5e5e5;padding:6px 8px;text-align:left}}
th{{background:#fafafa;font-weight:600}}
.dim{{color:#999}}.warn{{color:#C2410C;font-weight:600}}
.box{{background:#fafafa;border:1px solid #eee;border-radius:8px;padding:10px 14px;font-size:13px}}
#log div{{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:#444}}
button{{font-size:12px;padding:3px 10px;margin-right:6px;border:1px solid #ddd;background:#fff;border-radius:6px;cursor:pointer}}
button:hover{{background:#f0f0f0}}
</style></head><body>
<h1>模块 B · 算力共享节点面板</h1>
<div class="box">
后端：<b>{back.get("backend")}</b> · {local_note}<br>
记账单位：<b>{lg["unit"]}</b> · 抽样复算 {lg["sampling"]:.0%} · 软上限 {lg["soft_cap"]:,} token<br>
候选说明（每次请求都会记）见下方日志。
</div>

<h2>节点（档位 = 用户愿意出多少算力）</h2>
<table><tr><th>节点</th><th>名称</th><th>档位</th><th>已加载模型(权重前 8 位)</th>
<th>内存/显存</th><th>负载</th><th>信任</th></tr>{node_rows()}</table>
<div class="box">改档位：
<button onclick="setTier(0)">0 · 关闭</button>
<button onclick="setTier(1)">1 · 零打扰（手机默认）</button>
<button onclick="setTier(2)">2 · 全速</button>
<button onclick="setTier(3)">3 · PC 档</button>
<span class="dim">（一期只上线 0 / 3；1 / 2 的定义已在设计里拍板）</span></div>

<h2>账本</h2>
<table><tr><th>节点</th><th>可用额度</th><th>已验证贡献</th><th>待复算</th><th>已消费</th><th>验证开销</th></tr>
{ledger_rows()}</table>
{'<div class="box warn">待复算 ' + format(pending_total, ",") + ' token 未发放额度 —— 池内没有第二个能接同模型的节点可以复算。这是「冷启动空池」，不是缺陷。</div>' if pending_total else ''}

<h2>日志（最近 25 条）</h2><div class="box" id="log">{log}</div>

<script>
async function setTier(t){{
  const nodes = {json.dumps([n["node_id"] for n in pool], ensure_ascii=False)};
  for (const id of nodes) {{
    await fetch('/admin/tier', {{method:'POST', headers:{{'Content-Type':'application/json'}},
      body: JSON.stringify({{node_id:id, tier:t}})}});
  }}
  location.reload();
}}
</script></body></html>"""
