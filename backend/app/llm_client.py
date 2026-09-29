"""OpenAI 兼容异步客户端：被测模型 / 攻击模型 / 裁判模型复用同一份协议。"""
from __future__ import annotations

import httpx

from .models import ModelProfile


class ChatClient:
    def __init__(self, profile: ModelProfile, timeout: float = 90.0):
        self.profile = profile
        self._client = httpx.AsyncClient(timeout=timeout)

    async def chat(self, messages: list, temperature: float = 0.7) -> str:
        url = self.profile.baseUrl.rstrip("/") + "/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.profile.apiKey:
            headers["Authorization"] = f"Bearer {self.profile.apiKey}"
        resp = await self._client.post(
            url,
            headers=headers,
            json={"model": self.profile.model, "messages": messages, "temperature": temperature},
        )
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    async def close(self) -> None:
        await self._client.aclose()
