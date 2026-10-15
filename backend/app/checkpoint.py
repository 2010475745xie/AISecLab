"""会话检查点：让被中断的测试能**接着跑**，而不是从头再来。

## 为什么需要它

`sessions` 是 `main.py` 里的内存 dict，进程一重启就全丢；SSE 只推进度、不留档。
一次 50 条用例的委托测试烧的是**真实的模型调用与 token**，中断后从头跑等于白烧一遍。
2026-10-15 实际发生过：服务被回收，端上跑了十几条的会话直接没了。

还有一个更隐蔽的中断源：**单条用例的调用失败会让整个 session 挂掉**
（`asyncio.gather` 里任何一条抛异常都会往外冒），一次网络抖动就能毁掉整轮。

## 存什么 / 不存什么

✅ 用例指纹 → `TestResult`（已完成的结果）+ 进度元信息
❌ **绝不落 API key**。`SessionRequest` 里带着被测模型与裁判的 key，落盘等于把密钥写到磁盘。
   所以续跑的语义是：**端上重新带一次完整 request**（key 只在内存里流转），
   后端只从检查点认 `sid` 与用例指纹。副作用是"后端重启后无法自己续跑"——这是刻意的取舍。

## 为什么用指纹而不是 `testCase.id`

动态生成的用例 id 形如 `gen-security-0`：**编号固定、内容每次都不同**。
只比 id 会把"上一轮那道题的结果"安到"这一轮另一道题"头上 —— 报告里就是查无实据的结论，
而且**不会报错**。指纹 = sha1(id + NUL + prompt)，内容变了指纹就变，续跑时自然重跑。
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import time
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional

from .models import TestCase

logger = logging.getLogger(__name__)

RUNNING = "running"
DONE = "done"
FAILED = "failed"
# 进程被外部杀掉时来不及写任何东西，磁盘上留下的是 RUNNING。
# 启动时把它扫成 INTERRUPTED，这样"可续跑"才认得出来（见 CheckpointStore.sweep_running）。
INTERRUPTED = "interrupted"

INCOMPLETE_STATES = (RUNNING, INTERRUPTED, FAILED)

# sid 来自请求体，直接拼路径会有穿越风险（../../ 之类）。只放行时间戳/十六进制那种字符。
_SID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def default_dir() -> str:
    """检查点目录。默认 <cwd>/checkpoints（服务从 backend/dist 起，所以落在 dist 下）。"""
    return os.environ.get("CHECKPOINT_DIR") or os.path.join(os.getcwd(), "checkpoints")


def fingerprint(tc: TestCase) -> str:
    """用例指纹 = sha1(id + NUL + prompt) 前 16 位十六进制。见模块 docstring 最后一段。"""
    h = hashlib.sha1()
    h.update(tc.id.encode("utf-8"))
    h.update(b"\x00")
    h.update(tc.prompt.encode("utf-8"))
    return h.hexdigest()[:16]


@dataclass
class Checkpoint:
    """一个会话的检查点。`results` 的键是**指纹**，值是 `TestResult.model_dump()`。"""

    sid: str
    created_at: float
    updated_at: float
    state: str = RUNNING
    total: int = 0
    # 本次要跑的**全部**指纹。用于回答"续跑时用例集合有没有变"（变了就不匹配的会重跑）。
    expected: List[str] = field(default_factory=list)
    results: Dict[str, dict] = field(default_factory=dict)
    # 失败的会话留一句原因 —— 端上"继续"时能顺带告诉用户上次是怎么断的。
    error: Optional[str] = None
    # 上一次跑的是哪个**被测 / 裁判模型**（只存模型名，**绝不存 key**）。
    # 用途：「续跑」时若端上已经换了被测模型，复用旧结果会把**两个模型的结论混进同一份报告** ——
    # 那是查无实据的假证据。所以换了模型必须重新跑（见 main.create_session 的 409 守卫）。
    # ⚠️ 只比 `model` 名、**不比 baseUrl**：同一个模型换地址（局域网 IP ↔ `adb reverse` 的
    # 127.0.0.1）是本项目的常规操作，拿 baseUrl 当真会把正常的续跑全挡掉。
    target_model: str = ""
    judge_model: str = ""

    @property
    def completed(self) -> int:
        return len(self.results)

    def to_json(self) -> dict:
        return asdict(self)

    @classmethod
    def from_json(cls, d: dict) -> "Checkpoint":
        return cls(
            sid=str(d.get("sid") or ""),
            created_at=float(d.get("created_at") or 0.0),
            updated_at=float(d.get("updated_at") or 0.0),
            state=str(d.get("state") or RUNNING),
            total=int(d.get("total") or 0),
            expected=list(d.get("expected") or []),
            results=dict(d.get("results") or {}),
            error=d.get("error"),
            target_model=str(d.get("target_model") or ""),
            judge_model=str(d.get("judge_model") or ""),
        )

    def summary(self) -> dict:
        """给端上的轻量描述（不含结果本身 —— 50 条结果有几百 KB，列表接口不该驮着它）。"""
        return {
            "sessionId": self.sid,
            "state": self.state,
            "completed": self.completed,
            "total": self.total,
            "createdAt": int(self.created_at * 1000),
            "updatedAt": int(self.updated_at * 1000),
            "error": self.error,
        }


class CheckpointStore:
    """检查点的读写。一会话一文件，**原子写**（先写 .tmp 再 os.replace）。"""

    def __init__(self, directory: Optional[str] = None):
        self.dir = directory or default_dir()
        # ⚠️ 故意**不在这里建目录**：`CheckpointStore()` 在 `main.py` 是模块级构造，
        # 一 import 就建目录会在任何 import 场景（单测、工具脚本、换 cwd）留下 `./checkpoints`
        # 垃圾目录。真正的需要只有"落盘那一刻"，所以 makedirs 挪进 save()。
        # 反过来说读路径必须能容忍目录不存在：load → exists() False → None；
        # list_incomplete → listdir 抛 OSError 被兜住 → 空列表。

    def path(self, sid: str) -> str:
        return os.path.join(self.dir, f"{sid}.json")

    # ── 读 ──

    def load(self, sid: str) -> Optional[Checkpoint]:
        """读检查点。**任何异常都返回 None**（损坏的文件不该让续跑请求 500）。"""
        if not sid or not _SID_RE.match(sid):
            logger.warning("[checkpoint] 非法 sid 被拒：%r", sid)
            return None
        p = self.path(sid)
        if not os.path.exists(p):
            return None
        try:
            with open(p, encoding="utf-8") as f:
                return Checkpoint.from_json(json.load(f))
        except Exception as e:  # noqa: BLE001
            logger.warning("[checkpoint] 读取 %s 失败（当作不存在）：%s", p, e)
            return None

    def list_incomplete(self) -> List[Checkpoint]:
        """列出可续跑的会话（running / interrupted / failed），新的排前面。

        ⚠️ 不含 `done`：跑完的会话没有"续"的必要，端上要的是"哪些没跑完"。
        损坏的文件跳过并记警告，不整体失败 —— 一个坏文件不该让整张列表打不开。
        """
        out: List[Checkpoint] = []
        try:
            names = os.listdir(self.dir)
        except OSError as e:
            logger.warning("[checkpoint] 列目录失败：%s", e)
            return out
        for n in names:
            if not n.endswith(".json"):
                continue
            cp = self.load(n[: -len(".json")])
            if cp is not None and cp.state in INCOMPLETE_STATES:
                out.append(cp)
        out.sort(key=lambda c: c.updated_at, reverse=True)
        return out

    # ── 写 ──

    def save(self, cp: Checkpoint) -> None:
        """原子写：先落 .tmp 再 replace。中途被杀也只会留下一个 .tmp，不会得到半个 JSON。"""
        if not _SID_RE.match(cp.sid):
            logger.warning("[checkpoint] 非法 sid，拒绝落盘：%r", cp.sid)
            return
        cp.updated_at = time.time()
        p = self.path(cp.sid)
        tmp = p + ".tmp"
        try:
            os.makedirs(self.dir, exist_ok=True)
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(cp.to_json(), f, ensure_ascii=False)
            os.replace(tmp, p)
        except Exception as e:  # noqa: BLE001
            logger.warning("[checkpoint] 落盘 %s 失败：%s", p, e)

    def delete(self, sid: str) -> None:
        for p in (self.path(sid), self.path(sid) + ".tmp"):
            try:
                if os.path.exists(p):
                    os.remove(p)
            except OSError as e:
                logger.warning("[checkpoint] 删除 %s 失败：%s", p, e)

    def new(self, sid: str, total: int, expected: List[str]) -> Checkpoint:
        now = time.time()
        cp = Checkpoint(sid=sid, created_at=now, updated_at=now, state=RUNNING,
                        total=total, expected=list(expected))
        self.save(cp)
        return cp

    def sweep_running(self) -> int:
        """启动时调：把磁盘上遗留的 `running` 标成 `interrupted`。

        为什么必须做：进程被外部杀掉时来不及写任何收尾，磁盘上留下的就是 `running`。
        不做这一步，重启后 `list_incomplete` 也能列出来（我们把它算进 INCOMPLETE_STATES），
        但端上会看到"还在跑"，而实际上那个进程早没了 —— 状态要对用户说真话。
        """
        n = 0
        for cp in self.list_incomplete():
            if cp.state == RUNNING:
                cp.state = INTERRUPTED
                cp.error = cp.error or "服务重启，上一轮被中断"
                self.save(cp)
                n += 1
        return n
