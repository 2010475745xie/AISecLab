"""推理后端：真正「出算力」的那一层，做成可插拔。

三个实现，对应三种现实：

- [StubBackend]    —— 没有真实模型时用它打通协议与全链路。**确定性输出**（同样的
  输入必得同样的输出），所以能直接拿来测抽样复算的「一致/不一致」两条分支。
- [LlamaServerBackend] —— 本地 llama.cpp `llama-server`。这是**一期真正要用的那个**：
  起一个 llama-server，本模块把请求转过去，算力留在本机/本网段。
- [OpenAiHttpBackend] —— 任何 OpenAI 兼容端点的通用转发（含带 Key 的云端点）。

⚠️ 关于代理：`urllib` 默认会读环境变量 + Windows 注册表里的系统代理，
本机代理软件没开时表现为「所有请求 ConnectError」，和 AISecLab 后端踩过的是同一个坑。
所以这里**默认直连**，只认显式配置的 `MB_PROXY` / `HTTPS_PROXY` / `HTTP_PROXY`，
并在启动时把当前模式打印出来。
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from .protocol import ProtocolError, estimate_tokens

PROXY_ENV_KEYS = ("MB_PROXY", "ALL_PROXY", "HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy")


def resolve_proxy() -> str | None:
    """显式配置的代理；没配就是 None（直连）。"""
    for k in PROXY_ENV_KEYS:
        v = os.environ.get(k)
        if v:
            return v
    return None


def build_opener(proxy: str | None = None):
    """构造 urllib opener。**默认禁用代理**（空的 ProxyHandler），
    避免 urllib 去读注册表里的系统代理 —— 那正是本机那个失效代理的来源。"""
    handlers = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    else:
        handlers.append(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener(*handlers)


def proxy_banner() -> str:
    p = resolve_proxy()
    return f"模型出网：{'代理 ' + p if p else '直连'}"


@dataclass
class InferenceResult:
    content: str
    prompt_tokens: int
    completion_tokens: int
    backend: str
    latency_ms: float
    usage_estimated: bool = False


class Backend:
    name = "abstract"
    is_local = False

    def complete(self, model_id: str, messages: list[dict],
                 temperature: float, max_tokens: int) -> InferenceResult:
        raise NotImplementedError

    def describe(self) -> dict:
        return {"backend": self.name, "local": self.is_local}


class StubBackend(Backend):
    """无算力的确定性后端：只为打通协议、调度与记账。"""

    name = "stub"
    is_local = True

    def complete(self, model_id, messages, temperature, max_tokens):
        t0 = time.perf_counter()
        last = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                last = str(m.get("content", ""))
                break
        n_user = sum(1 for m in messages if m.get("role") == "user")
        # 确定性：内容只由入参决定，不含时间/随机数 → 抽样复算能给出稳定结论。
        content = (
            f"[stub:{model_id}] 模块 B 的确定性桩后端（未接真实权重）。"
            f"共收到 {len(messages)} 条消息（其中 user {n_user} 条）。"
            f"最后一条用户输入为：「{last[:160]}」"
        )
        return InferenceResult(
            content=content,
            prompt_tokens=sum(estimate_tokens(str(m.get("content", ""))) for m in messages),
            completion_tokens=estimate_tokens(content),
            backend=self.name,
            latency_ms=(time.perf_counter() - t0) * 1000,
            usage_estimated=True,
        )


class OpenAiHttpBackend(Backend):
    """转发到任意 OpenAI 兼容端点。`is_local=False` 时面板会标明「算力不在本机」。"""

    def __init__(self, base_url: str, api_key: str = "", *, is_local: bool = False,
                 timeout: float = 120.0, name: str | None = None, upstream_model: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.is_local = is_local
        self.timeout = timeout
        self.name = name or ("llama-server" if is_local else "upstream")
        self.upstream_model = upstream_model  # 空 = 原样透传请求里的 model
        self._opener = build_opener(resolve_proxy())

    def complete(self, model_id, messages, temperature, max_tokens):
        url = self.base_url
        if not url.endswith("/chat/completions"):
            url = url + "/chat/completions"
        payload = {
            "model": self.upstream_model or model_id,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
        req = urllib.request.Request(
            url, data=json.dumps(payload).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json"},
        )
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")

        t0 = time.perf_counter()
        try:
            with self._opener.open(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            raise ProtocolError(f"上游 {self.name} 返回 HTTP {e.code}：{detail}",
                                status=502, code="upstream_error") from e
        except Exception as e:  # URLError / timeout / JSON 解析
            raise ProtocolError(f"上游 {self.name} 调用失败：{type(e).__name__}: {e}",
                                status=502, code="upstream_error") from e

        ms = (time.perf_counter() - t0) * 1000
        choices = body.get("choices") or []
        content = ""
        if choices:
            content = str(((choices[0] or {}).get("message") or {}).get("content") or "")
        usage = body.get("usage") or {}
        prompt_t = int(usage.get("prompt_tokens") or 0)
        comp_t = int(usage.get("completion_tokens") or 0)
        estimated = False
        if not prompt_t and not comp_t:
            prompt_t = sum(estimate_tokens(str(m.get("content", ""))) for m in messages)
            comp_t = estimate_tokens(content)
            estimated = True
        return InferenceResult(content=content, prompt_tokens=prompt_t,
                               completion_tokens=comp_t, backend=self.name,
                               latency_ms=ms, usage_estimated=estimated)

    def describe(self) -> dict:
        return {"backend": self.name, "local": self.is_local, "base_url": self.base_url,
                "has_key": bool(self.api_key)}


def llama_server(base_url: str, api_key: str = "") -> OpenAiHttpBackend:
    """本地 llama-server（一期的主力后端）。"""
    return OpenAiHttpBackend(base_url, api_key, is_local=True, name="llama-server")


def upstream(base_url: str, api_key: str = "", model: str = "") -> OpenAiHttpBackend:
    """开发期借用外部端点。**面板会标 `算力不在本机`** —— 别让用户以为这是共享算力。"""
    return OpenAiHttpBackend(base_url, api_key, is_local=False, name="upstream",
                             upstream_model=model)
