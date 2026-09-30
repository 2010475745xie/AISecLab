"""OpenAI 兼容异步客户端：被测模型 / 攻击模型 / 裁判模型复用同一份协议。

带**全局并发闸 + 429/5xx 指数退避重试**。真实 LLM（尤其 DeepSeek 等 RPM 限额严格的）
在"每用例 1 次被测 + 3 次裁判角色"的高并发下极易 429，原先 `raise_for_status()` 一撞限流
就整轮失败。这里：① 所有模型共享一个全局信号量，限制同时打向上游的请求数；
② 命中限流/上游抖动时退避重试，优先遵从上墙给的 `Retry-After`。
"""
from __future__ import annotations

import asyncio
import os

import httpx

from .models import ModelProfile

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


class ChatClient:
    def __init__(self, profile: ModelProfile, timeout: float = 90.0, max_retries: int = 5):
        self.profile = profile
        self.max_retries = max_retries
        self._client = httpx.AsyncClient(timeout=timeout)

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
                return resp.json()["choices"][0]["message"]["content"]
            except httpx.TransportError as e:  # 连接失败 / 读写超时 / 网络抖动
                last_exc = e
                if attempt >= self.max_retries:
                    raise
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

    async def close(self) -> None:
        await self._client.aclose()
