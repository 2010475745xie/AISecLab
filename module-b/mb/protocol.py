"""对外协议：OpenAI 兼容（子集）。

**字段口径必须与模块 A 端 `core/api/ChatModels.kt` 对齐**，因为模块 A 会把本服务
当成「一个普通被测模型端点」直接填进 CONFIG 表单。端上真正要求的只有三件事：

1. `POST {base_url}/chat/completions`（`base_url` 一般以 `/v1` 结尾）；
2. 请求体 `{model, messages:[{role,content}], temperature, max_tokens}`；
3. 响应有 `choices[0].message.{role,content}`，有 `usage` 更好（端上据此算 token 消耗，
   没有也不会报错，只会记一次「未返回用量」）。

未实现的部分**明确报错**而不静默降级：`stream=true` 返回 501。
静默按非流式处理会让端上收到一个不是 SSE 的响应体，在流式解析里表现成「模型返回空」——
那比一个明确的 501 难查得多。
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field


class ProtocolError(Exception):
    """带 HTTP 状态码的协议错误，交给 server 层翻译成 OpenAI 风格的错误体。"""

    def __init__(self, message: str, status: int = 400, code: str = "invalid_request_error"):
        super().__init__(message)
        self.status = status
        self.code = code


@dataclass
class ChatRequest:
    model: str
    messages: list[dict] = field(default_factory=list)
    temperature: float = 0.7
    max_tokens: int = 2048
    stream: bool = False
    raw: dict = field(default_factory=dict)

    @property
    def prompt_tokens_estimate(self) -> int:
        return sum(estimate_tokens(str(m.get("content", ""))) for m in self.messages)

    def last_user_text(self) -> str:
        for m in reversed(self.messages):
            if m.get("role") == "user":
                return str(m.get("content", ""))
        return str(self.messages[-1].get("content", "")) if self.messages else ""


def parse_chat_request(body: dict) -> ChatRequest:
    if not isinstance(body, dict):
        raise ProtocolError("请求体必须是 JSON 对象")
    model = body.get("model")
    if not model or not isinstance(model, str):
        raise ProtocolError("缺少 model 字段")
    msgs = body.get("messages")
    if not isinstance(msgs, list) or not msgs:
        raise ProtocolError("messages 必须是非空数组")
    for i, m in enumerate(msgs):
        if not isinstance(m, dict) or "role" not in m or "content" not in m:
            raise ProtocolError(f"messages[{i}] 必须含 role 与 content")
    if body.get("stream") is True:
        raise ProtocolError("流式（stream=true）尚未实现；请用非流式请求",
                            status=501, code="not_implemented")
    return ChatRequest(
        model=model,
        messages=msgs,
        temperature=float(body.get("temperature", 0.7)),
        max_tokens=int(body.get("max_tokens", 2048)),
        stream=False,
        raw=body,
    )


def build_completion(model: str, content: str, prompt_tokens: int, completion_tokens: int,
                     *, usage_estimated: bool = False, backend: str = "",
                     node_id: str = "") -> dict:
    """OpenAI 形状的响应体。

    `usage_estimated` / `backend` / `node_id` 挂在 `mb` 扩展字段里 —— 端上
    `ignoreUnknownKeys = true`，多出来的字段会被忽略，不会影响模块 A 解析。
    但排障时能一眼看出「这个 token 数是估的还是端点报的」「这一单落在哪个节点」。
    """
    return {
        "id": "chatcmpl-" + uuid.uuid4().hex[:24],
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": content},
            "finish_reason": "stop",
        }],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
        "mb": {
            "served_by": node_id,
            "backend": backend,
            "usage_estimated": usage_estimated,
        },
    }


def error_body(message: str, code: str, status: int) -> dict:
    return {"error": {"message": message, "type": "invalid_request_error",
                      "code": code, "status": status}}


def estimate_tokens(text: str) -> int:
    """粗略估 token 数，**只用于上游不回 usage 时的兜底**。

    中日韩字符按 1 token/字，其余按 ~4 字符/token。这个数字会随响应标
    `usage_estimated=true` —— 用户会把 token 数当账单看，估算值必须可见，
    不能混进正式用量里（和模块 A `TokenMeter` 里那条「宁可不计也不估算」的取舍一致）。
    """
    if not text:
        return 0
    cjk = sum(1 for ch in text if "\u3000" <= ch <= "\u9fff" or "\uac00" <= ch <= "\ud7af")
    rest = len(text) - cjk
    return max(1, cjk + round(rest / 4))


def normalize_messages_for_compare(text: str) -> str:
    """抽样复算比对前的归一化：只差空白/换行不算「不一致」，否则会把正常抖动当作弊。"""
    return " ".join(text.split()).strip().lower()
