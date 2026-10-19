"""验证 baseUrl 规范化 + 3xx 重定向重发（2026-10-18 修的 302 坑）。

背景：baseUrl 填 `http://api.deepseek.com/v1`，DeepSeek 回 302 到 https，
httpx 默认不跟重定向 ⇒ 每个用例「执行失败（按不可评估记录）」，50 条全废。

跑法（仓库根）：
    python backend/_verify_redirect.py            # 只跑纯函数
    python backend/_verify_redirect.py --live     # 额外打一次真实上游（需 DEEPSEEK_API_KEY）
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))

from app.llm_client import _redirect_target, normalize_base_url  # noqa: E402

FAILS: list = []


def check(label: str, got, want) -> None:
    ok = got == want
    print("  %s %-46s got=%r" % ("OK  " if ok else "FAIL", label, got))
    if not ok:
        print("       want=%r" % (want,))
        FAILS.append(label)


def test_normalize() -> None:
    print("\n[1] normalize_base_url —— 公网 http 升级为 https")
    check("deepseek (无 s)", normalize_base_url("http://api.deepseek.com/v1"),
          "https://api.deepseek.com/v1")
    check("openai (无 s)", normalize_base_url("http://api.openai.com/v1"),
          "https://api.openai.com/v1")
    check("已 https 不动", normalize_base_url("https://api.deepseek.com/v1"),
          "https://api.deepseek.com/v1")
    check("漏写协议", normalize_base_url("api.deepseek.com/v1"),
          "https://api.deepseek.com/v1")
    check("尾斜杠保留", normalize_base_url("http://api.deepseek.com/v1/"),
          "https://api.deepseek.com/v1/")

    print("\n[2] 内网/本机必须保持 http（llama.cpp / ollama）")
    for raw in (
        "http://localhost:8080/v1",
        "http://127.0.0.1:8080/v1",
        "http://192.168.1.7:8080/v1",
        "http://10.0.0.5:8000/v1",
        "http://172.16.3.9:11434/v1",
        "http://my-box:11434/v1",
        "http://ollama.local/v1",
    ):
        check("保持 http " + raw, normalize_base_url(raw), raw)

    check("空串", normalize_base_url(""), "")


def test_redirect_target() -> None:
    print("\n[3] _redirect_target —— 只接受同 host 同 path")
    base = "http://api.deepseek.com/v1/chat/completions"
    check("协议升级（该跟）",
          _redirect_target(base, "https://api.deepseek.com/v1/chat/completions"),
          "https://api.deepseek.com/v1/chat/completions")
    check("相对路径同端点（该跟）",
          _redirect_target(base, "/v1/chat/completions"),
          "http://api.deepseek.com/v1/chat/completions")
    check("换域名（不该跟）",
          _redirect_target(base, "https://evil.example.com/v1/chat/completions"), None)
    check("换路径（不该跟）",
          _redirect_target(base, "https://api.deepseek.com/login"), None)
    check("空 Location", _redirect_target(base, ""), None)


async def test_mock_redirect() -> None:
    """不碰网络、不需要 key：用 httpx.MockTransport 真跑一遍 chat() 的重发分支。

    断言三件事（缺一不可）：
      ① 第一次 302 → 第二次真的重发了；
      ② 重发仍是 **POST 且 body 一模一样**（这就是不能用 follow_redirects 的原因，
         httpx 会把 302 的 POST 降级成 GET + 丢 body）；
      ③ 换域名的 302 必须拒绝（不能把 apiKey 送给别的域）。
    """
    import httpx

    from app.llm_client import ChatClient
    from app.models import ModelProfile

    print("\n[4] mock 上游：302 → POST 原样重发")

    # ── 场景 A：同端点 302，应自愈成功 ──
    seen: list = []

    def ok_handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, str(request.url), request.content))
        if len(seen) == 1:
            return httpx.Response(
                302, headers={"location": "http://localhost:9999/v1/chat/completions"}
            )
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "收到"}}],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}},
        )

    prof = ModelProfile(id="t", name="t", baseUrl="http://localhost:9999/v1",
                        apiKey="sk-test", model="m")
    c = ChatClient(prof, timeout=10, max_retries=0)      # max_retries=0：证明重发不占重试次数
    c._client = httpx.AsyncClient(transport=httpx.MockTransport(ok_handler))
    try:
        out = await c.chat([{"role": "user", "content": "hi"}], temperature=0.0)
    except Exception as e:  # noqa: BLE001
        print("  FAIL 抛出异常：%s: %s" % (type(e).__name__, e))
        FAILS.append("mock-302-raise")
        return
    print("  OK   回答 = %r" % (out,))
    check("发了几次", len(seen), 2)
    check("两次都是 POST", [m for m, _, _ in seen], ["POST", "POST"])
    check("body 完全一致", len({b for _, _, b in seen}) == 1 and len(seen[0][2]) > 0, True)
    check("usage 累加", c.usage["total"], 5)
    await c.close()

    # ── 场景 B：302 到别的域名，必须拒绝 ──
    print("\n[5] mock 上游：跨域 302 必须拒绝")
    hit: list = []

    def evil_handler(request: httpx.Request) -> httpx.Response:
        hit.append(str(request.url))
        return httpx.Response(302, headers={"location": "https://evil.example.com/steal"})

    c2 = ChatClient(prof, timeout=10, max_retries=0)
    c2._client = httpx.AsyncClient(transport=httpx.MockTransport(evil_handler))
    try:
        await c2.chat([{"role": "user", "content": "hi"}])
        print("  FAIL 跨域 302 没被拒绝")
        FAILS.append("mock-cross-domain-not-rejected")
    except RuntimeError as e:
        ok = "非同一端点" in str(e)
        print("  %s 被拒绝：%s" % ("OK  " if ok else "FAIL", str(e)[:70]))
        if not ok:
            FAILS.append("mock-cross-domain-msg")
    except Exception as e:  # noqa: BLE001
        print("  FAIL 抛了别的异常：%s: %s" % (type(e).__name__, e))
        FAILS.append("mock-cross-domain-type")
    check("跨域后没再发第二次", len(hit), 1)
    await c2.close()


async def test_live() -> None:
    """真实打一次上游：故意用 http:// 看是否自愈。"""
    key = (os.environ.get("DEEPSEEK_API_KEY") or "").strip()
    if not key:
        print("\n[4] 真实上游：跳过（未设 DEEPSEEK_API_KEY）")
        return
    print("\n[4] 真实上游：baseUrl 故意写 http://，看能否自愈成功")
    from app.llm_client import ChatClient
    from app.models import ModelProfile

    prof = ModelProfile(
        id="target", name="deepseek", baseUrl="http://api.deepseek.com/v1",
        apiKey=key, model="deepseek-chat",
    )
    c = ChatClient(prof, timeout=45, max_retries=0)
    print("  client.base_url =", c.base_url)
    try:
        out = await c.chat([{"role": "user", "content": "只回答两个字：收到"}], temperature=0.0)
        print("  OK   回答 = %r" % (out[:40],))
    except Exception as e:  # noqa: BLE001
        print("  FAIL %s: %s" % (type(e).__name__, e))
        FAILS.append("live")
    finally:
        await c.close()


def main() -> int:
    test_normalize()
    test_redirect_target()
    asyncio.run(test_mock_redirect())
    if "--live" in sys.argv:
        asyncio.run(test_live())
    print("\n" + "=" * 60)
    if FAILS:
        print("FAILED %d 项：%s" % (len(FAILS), FAILS))
        return 1
    print("ALL OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
