# -*- coding: utf-8 -*-
"""对**已启动**的模块 B 节点做一次协议冒烟（不产生任何模型费用）。

    python tools/smoke.py --base http://127.0.0.1:8100

它按模块 A 真实会发出的请求形状打一发（非流式、无 Authorization），
再把几条边界一次性验掉：未知模型 404、装不下的模型 503（附拒绝理由）、
流式 501。最后打印账本 —— 一期单节点时「待复算」必然增长而「已验证贡献」恒为 0，
这不是 bug，是设计草案 §11 风险 2「冷启动空池」在数据上的样子。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def call(base: str, path: str, body=None, headers=None):
    req = urllib.request.Request(
        base.rstrip("/") + path, method="POST" if body is not None else "GET",
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8100")
    a = ap.parse_args()
    base = a.base.rstrip("/")
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        ok = ok and bool(cond)
        print(f"  [{'OK' if cond else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))

    print("== 模块 B 冒烟 ==")
    st, h = call(base, "/healthz")
    print(f"healthz  {st} {h}")
    check("健康检查 200 且带节点数", st == 200 and h.get("ok"))

    st, lst = call(base, "/v1/models")
    ids = [m["id"] for m in lst.get("data", [])]
    print(f"模型列表 {ids}")
    check("OpenAI /v1/models 形状正确", st == 200 and lst.get("object") == "list" and ids)

    st, state = call(base, "/admin/state")
    held = []
    for n in state.get("pool", []):
        held += list(n["models"])
    print(f"本节点已加载模型 {held}")
    served = held[0] if held else ""

    if served:
        st, resp = call(base, "/v1/chat/completions", {
            "model": served,
            "messages": [{"role": "user", "content": "请用一句话解释什么是提示词注入"}],
            "temperature": 0.0, "max_tokens": 128,
        })
        print(f"对话     {st}")
        if st == 200:
            msg = resp["choices"][0]["message"]
            print(f"  回答   {msg['content'][:120]}")
            print(f"  usage  {resp['usage']}")
            print(f"  mb     served_by={resp['mb']['served_by']} backend={resp['mb']['backend']} "
                  f"credit={resp['mb']['credit_state']}")
        check("模块 A 形状的请求返回可解析的 choices/usage",
              st == 200 and resp.get("choices") and resp.get("usage", {}).get("total_tokens", 0) > 0)

    # ── 边界 ──────────────────────────────────────────────────────
    st, err = call(base, "/v1/chat/completions", {
        "model": "不存在的模型", "messages": [{"role": "user", "content": "hi"}]})
    check("未知模型 404", st == 404, f"得到 {st}")

    not_held = [m for m in ids if m not in held]
    if not_held:
        st, err = call(base, "/v1/chat/completions", {
            "model": not_held[0], "messages": [{"role": "user", "content": "hi"}]})
        reason = err.get("error", {}).get("message", "")
        print(f"装不下的模型 → {st}")
        print(f"  {reason[:200]}")
        check("没加载的模型 503 且给出拒绝理由", st == 503 and "未加载" in reason)

    st, err = call(base, "/v1/chat/completions", {
        "model": served or "x", "stream": True,
        "messages": [{"role": "user", "content": "hi"}]})
    check("流式明确 501（不静默按非流式处理）", st == 501, f"得到 {st}")

    st, state = call(base, "/admin/state")
    led = state["ledger"]["nodes"]
    print("== 账本 ==")
    for nid, v in led.items():
        print(f"  {nid}: 已验证贡献 {v['verified_tokens']} · 待复算 {v['pending_tokens']} "
              f"· 已消费 {v['consumed_tokens']} · 信任 {v['trust']}")
    if led and all(v["verified_tokens"] == 0 for v in led.values()):
        print(f"  ℹ 已验证贡献全为 0、待复算在涨 —— 池里只有 {len(state['pool'])} 个节点，"
              f"没有第二台机器能复算（设计草案 §11 风险 2）。")
    print("== 结论：%s ==" % ("全部通过" if ok else "有失败项"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
