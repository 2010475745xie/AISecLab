"""OpenAI 兼容异步客户端：被测模型 / 攻击模型 / 裁判模型复用同一份协议。

带**全局并发闸 + 429/5xx 指数退避重试**。真实 LLM（尤其 DeepSeek 等 RPM 限额严格的）
在"每用例 1 次被测 + 3 次裁判角色"的高并发下极易 429，原先 `raise_for_status()` 一撞限流
就整轮失败。这里：① 所有模型共享一个全局信号量，限制同时打向上游的请求数；
② 命中限流/上游抖动时退避重试，优先遵从上墙给的 `Retry-After`。
"""
from __future__ import annotations

import asyncio
import logging
import math
import os
from typing import Optional
from urllib.parse import urlsplit

import httpx

from .models import ModelProfile

logger = logging.getLogger(__name__)

# 值得重试的状态码：限流 / 网关抖动 / 上游 5xx。
_RETRY_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}

# 全局并发闸：被测/裁判/攻击共享，避免对同一上游同时打太多请求。
# 可用环境变量 MAX_CONCURRENCY 调整；DeepSeek 等限额严格的建议 1~2。
_GLOBAL_SEM: "asyncio.Semaphore | None" = None


def _sem() -> asyncio.Semaphore:
    global _GLOBAL_SEM
    if _GLOBAL_SEM is None:
        _GLOBAL_SEM = asyncio.Semaphore(max(1, int(os.environ.get("MAX_CONCURRENCY", "3"))))
    return _GLOBAL_SEM


# ── 出网代理：**不信任系统代理** ───────────────────────────────────────
# 踩过的坑（2026-10-11）：httpx 默认 `trust_env=True`，在 Windows 上会去读**注册表**里的
# 系统代理（用户机器上 ProxyEnable=1 / ProxyServer=127.0.0.1:3067）。用户的代理软件没开时，
# 所有模型调用都退化成「连接被拒绝 → ConnectError: All connection attempts failed」，
# 而 deepseek 这类端点直连明明是通的 —— 报错还完全指不出是代理的锅。
# 现在：**默认直连**；只有显式设了代理环境变量才走代理。
_PROXY_ENV_KEYS = (
    "AISECLAB_PROXY",
    "HTTPS_PROXY", "https_proxy",
    "HTTP_PROXY", "http_proxy",
    "ALL_PROXY", "all_proxy",
)


def resolve_proxy() -> Optional[str]:
    """显式配置的出网代理；未配置返回 None（= 直连，不读系统代理）。"""
    for k in _PROXY_ENV_KEYS:
        v = (os.environ.get(k) or "").strip()
        if v:
            return v
    return None


def describe_proxy() -> str:
    """启动横幅里展示用。"""
    p = resolve_proxy()
    return ("代理 %s" % p) if p else "直连（未使用代理）"


def _env_num(key: str, default: float) -> float:
    """读一个数值型环境变量，缺失/空/非法一律回落默认值（不抛）。"""
    raw = os.environ.get(key)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError:
        return default


class ChatClient:
    def __init__(self, profile: ModelProfile, timeout: Optional[float] = None,
                 max_retries: Optional[int] = None):
        self.profile = profile
        # 可配：上游慢 / 走了代理链路长时调大 REQUEST_TIMEOUT；上游限流严格时调小 MAX_RETRIES。
        # 未显式传参则读环境变量（缺省 90s / 5 次）。
        if timeout is None:
            timeout = max(1.0, _env_num("REQUEST_TIMEOUT", 90.0))
        if max_retries is None:
            max_retries = int(max(0.0, _env_num("MAX_RETRIES", 5.0)))
        self.max_retries = int(max_retries)
        # trust_env=False：**不读系统代理/注册表**，只认显式配置的代理（见 resolve_proxy）。
        self._client = httpx.AsyncClient(
            timeout=timeout, trust_env=False, proxy=resolve_proxy()
        )
        # 本 client 的累计用量：**成功响应才计**。engine 汇总三个 client（被测/裁判/攻击）后
        # 随进度事件发给端上 —— 支撑"跑的时候实时显示消耗了多少 token"。
        self.usage = {"prompt": 0, "completion": 0, "total": 0, "calls": 0}

    def _record_usage(self, data: dict) -> None:
        """从响应体取 usage 并累加。端点不回 usage（本地 llama.cpp 默认不回）时静默跳过 —— 宁可不计也不估算。"""
        u = data.get("usage") or {}
        try:
            p = int(u.get("prompt_tokens") or 0)
            c = int(u.get("completion_tokens") or 0)
            t = int(u.get("total_tokens") or 0)
        except (TypeError, ValueError):
            return
        if t <= 0:
            t = p + c
        if p <= 0 and c <= 0 and t <= 0:
            return
        self.usage["prompt"] += p
        self.usage["completion"] += c
        self.usage["total"] += t
        self.usage["calls"] += 1

    def _endpoint_label(self) -> str:
        """错误信息里用的端点标识：`host (模型名)`，**不含 apiKey**。

        以前上游连不上时只抛 `All connection attempts failed`，既看不出是哪个端点、
        也看不出是哪个角色（被测/裁判/攻击），排查只能靠猜。这里把它补上。
        """
        base = self.profile.baseUrl or ""
        try:
            host = urlsplit(base).netloc or base
        except Exception:  # noqa: BLE001
            host = base
        return "%s (%s)" % (host or "?", self.profile.model or "?")

    async def chat(self, messages: list, temperature: float = 0.7) -> str:
        url = self.profile.baseUrl.rstrip("/") + "/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.profile.apiKey:
            headers["Authorization"] = f"Bearer {self.profile.apiKey}"
        payload = {"model": self.profile.model, "messages": messages, "temperature": temperature}

        last_exc: "Exception | None" = None
        for attempt in range(self.max_retries + 1):
            try:
                async with _sem():
                    resp = await self._client.post(url, headers=headers, json=payload)
                # 限流 / 上游抖动：未到上限则退避后重试
                if resp.status_code in _RETRY_STATUS and attempt < self.max_retries:
                    await asyncio.sleep(self._retry_delay(resp, attempt))
                    continue
                resp.raise_for_status()
                data = resp.json()
                self._record_usage(data)
                return data["choices"][0]["message"]["content"]
            except httpx.TransportError as e:  # 连接失败 / 读写超时 / 网络抖动
                last_exc = e
                logger.warning(
                    "上游不可达（%s）第 %d/%d 次：%s: %s",
                    self._endpoint_label(), attempt + 1, self.max_retries + 1, type(e).__name__, e,
                )
                if attempt >= self.max_retries:
                    # 把端点写进异常：端上/报告里能直接看出是哪个上游挂了
                    raise RuntimeError(
                        "连接上游失败：%s，已重试 %d 次仍不可达（%s: %s）"
                        % (self._endpoint_label(), self.max_retries, type(e).__name__, e)
                    ) from e
                await asyncio.sleep(min(2 ** attempt, 20))
        if last_exc is not None:
            raise last_exc
        raise RuntimeError("chat: exhausted retries without response")

    @staticmethod
    def _retry_delay(resp: httpx.Response, attempt: int) -> float:
        """优先遵从上墙给的 Retry-After，否则指数退避（1,2,4,8,16s，上限 30s）。"""
        ra = resp.headers.get("Retry-After")
        if ra:
            try:
                return min(float(ra), 30.0)
            except ValueError:
                pass
        return min(1.0 * (2 ** attempt), 30.0)

    async def chat_detailed(
        self,
        messages: list,
        temperature: float = 0.0,
        max_tokens: int = 320,
        top_logprobs: int = 20,
    ):
        """带 logprobs 的调用，供护栏取「首 token top-k 概率」。

        返回 `(content, first_top_tokens)`，其中 `first_top_tokens` 是
        `[(token文本, 概率)]`（概率 = exp(logprob)）。服务端不回 logprobs 时返回空表，
        护栏会退化为文字标签形态（`calibrated=False`，置信度打折）。
        """
        url = self.profile.baseUrl.rstrip("/") + "/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.profile.apiKey:
            headers["Authorization"] = f"Bearer {self.profile.apiKey}"
        payload = {
            "model": self.profile.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "logprobs": top_logprobs > 0,
            "top_logprobs": max(0, top_logprobs),
        }
        try:
            async with _sem():
                resp = await self._client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
        except httpx.TransportError as e:
            logger.warning("上游不可达（%s）：%s: %s", self._endpoint_label(), type(e).__name__, e)
            raise RuntimeError(
                "连接上游失败：%s（%s: %s）" % (self._endpoint_label(), type(e).__name__, e)
            ) from e
        data = resp.json()
        self._record_usage(data)
        choice = (data.get("choices") or [{}])[0]
        content = (choice.get("message") or {}).get("content", "") or ""
        top: list = []
        lp = choice.get("logprobs") or {}
        content_lp = lp.get("content") or []
        if content_lp:
            for entry in (content_lp[0].get("top_logprobs") or []):
                tok = entry.get("token")
                if tok is None:
                    continue
                try:
                    top.append((str(tok), float(math.exp(float(entry.get("logprob", -20.0))))))
                except (TypeError, ValueError):
                    continue
        return content, top

    async def close(self) -> None:
        await self._client.aclose()
