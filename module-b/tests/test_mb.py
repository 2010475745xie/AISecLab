# -*- coding: utf-8 -*-
"""模块 B 的离线自测：不触网、不需要真实权重。

覆盖的是「设计里拍过板的取舍」而不是代码行数：
档位门控、权重指纹硬门槛、能力过滤、派单排序、抽样复算的两个分支、
以及记账单位必须是「通过复算验证的输出 token」。

    python -m unittest discover -s tests -v      （在 module-b/ 目录下）
"""

from __future__ import annotations

import json
import os
import sys
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from mb.accounting import CreditLedger  # noqa: E402
from mb.backends import Backend, InferenceResult, StubBackend  # noqa: E402
from mb.coordinator import Coordinator  # noqa: E402
from mb.pool import NodePool, NodeState  # noqa: E402
from mb.protocol import (ChatRequest, ProtocolError, build_completion,  # noqa: E402
                         estimate_tokens, parse_chat_request)
from mb.registry import (ModelEntry, ModelRegistry, sha256_of_text)  # noqa: E402
from mb.server import serve_in_thread  # noqa: E402
from mb.tiers import gate, get_tier, tier_key  # noqa: E402

H = {k: sha256_of_text("test/" + k) for k in ("small", "mid", "big")}


def entry(model_id="m-small", params_b=0.5, hash_key="small",
          tiers=("phone_low", "phone_full", "pc"), ram=700, vram=0) -> ModelEntry:
    return ModelEntry(model_id=model_id, weights_hash=H[hash_key], quant="q4_k_m",
                      params_b=params_b, min_ram_mb=ram, min_vram_mb=vram,
                      node_tiers=tiers)


def node(node_id="n1", tier=3, models=None, **kw) -> NodeState:
    base = dict(node_id=node_id, name=node_id, tier_level=tier, cores=4,
                ram_mb=16384, vram_mb=0, models=models or {},
                charging=True, wifi=True, screen_off=False, foreground=True)
    base.update(kw)
    return NodeState(**base)


class TestTiers(unittest.TestCase):
    def test_档位0永不接单(self):
        g = gate(get_tier(0), {"charging": True, "wifi": True, "screen_off": True},
                 params_b=0.1, daily_used_mb=0, in_flight=0)
        self.assertFalse(g)
        self.assertIn("不接单", g.reason)

    def test_档位1必须三个触发条件同时满足(self):
        t = get_tier(1)
        full = {"charging": True, "wifi": True, "screen_off": True}
        self.assertTrue(gate(t, full, params_b=0.5, daily_used_mb=0, in_flight=0))
        for missing in ("charging", "wifi", "screen_off"):
            partial = dict(full, **{missing: False})
            g = gate(t, partial, params_b=0.5, daily_used_mb=0, in_flight=0)
            self.assertFalse(g, f"{missing}=False 时不该接单")
            self.assertIn("触发条件未满足", g.reason)

    def test_未上报的状态按不满足处理(self):
        """节点没上报 charging 时不能默认它插着电 —— 否则一个不上报状态的节点
        会静默变成「随时可接单」。"""
        g = gate(get_tier(1), {"wifi": True, "screen_off": True},
                 params_b=0.5, daily_used_mb=0, in_flight=0)
        self.assertFalse(g)

    def test_档位1装不下1_5B(self):
        g = gate(get_tier(1), {"charging": True, "wifi": True, "screen_off": True},
                 params_b=1.5, daily_used_mb=0, in_flight=0)
        self.assertFalse(g)
        self.assertIn("超出档位", g.reason)

    def test_档位2的触发是或关系(self):
        t = get_tier(2)
        self.assertTrue(gate(t, {"foreground": True}, params_b=3.0,
                             daily_used_mb=0, in_flight=0))
        self.assertTrue(gate(t, {"charging": True, "wifi": True}, params_b=3.0,
                             daily_used_mb=0, in_flight=0))
        self.assertFalse(gate(t, {"foreground": False}, params_b=3.0,
                              daily_used_mb=0, in_flight=0))

    def test_档位3无触发条件但有并发上限(self):
        t = get_tier(3)
        self.assertEqual(t.triggers, ())
        # 档位 3 的并发是**固定 4**（`concurrency=4`），不随 cores 放大 ——
        # 只有 `concurrency=0`（不限）的档位才回落到 cores。
        self.assertEqual(t.concurrency_cap(16), 4)
        self.assertTrue(gate(t, {}, params_b=13, daily_used_mb=0, in_flight=3, cores=8))
        g = gate(t, {}, params_b=13, daily_used_mb=0, in_flight=4, cores=8)
        self.assertFalse(g)
        self.assertIn("并发已满", g.reason)

    def test_日流量上限(self):
        g = gate(get_tier(1), {"charging": True, "wifi": True, "screen_off": True},
                 params_b=0.5, daily_used_mb=200, in_flight=0)
        self.assertFalse(g)
        self.assertIn("日流量", g.reason)

    def test_档位协议键与展示名分开(self):
        self.assertEqual(tier_key(1), "phone_low")
        self.assertEqual(tier_key(3), "pc")
        # 展示名可以被改文案，协议键不行 —— 模型注册表里写的是键。
        self.assertNotEqual(tier_key(3), get_tier(3).name)


class TestRegistry(unittest.TestCase):
    def test_非sha256的哈希被拒绝(self):
        with self.assertRaises(ValueError) as ctx:
            ModelEntry(model_id="x", weights_hash="deadbeef", quant="q4", params_b=1,
                       min_ram_mb=1, min_vram_mb=0, node_tiers=("pc",)).validate()
        self.assertIn("sha256", str(ctx.exception))

    def test_不声明档位被拒绝(self):
        with self.assertRaises(ValueError):
            ModelEntry(model_id="x", weights_hash=H["small"], quant="q4", params_b=1,
                       min_ram_mb=1, min_vram_mb=0, node_tiers=()).validate()

    def test_off档不能写进模型的档位表(self):
        """档位 0 = 节点关闭，是**节点状态**不是模型属性。
        写进 node_tiers 会变成一个永假的配置项，还会把拒绝理由污染成一句看不懂的话。"""
        with self.assertRaises(ValueError) as ctx:
            ModelEntry(model_id="x", weights_hash=H["small"], quant="q4", params_b=1,
                       min_ram_mb=1, min_vram_mb=0, node_tiers=("off", "pc")).validate()
        self.assertIn("off", str(ctx.exception))

    def test_装不下就是装不下(self):
        e = entry(params_b=7, ram=5000, vram=6000)
        self.assertFalse(e.fits(ram_mb=16000, vram_mb=0))    # 有内存没显存
        self.assertTrue(e.fits(ram_mb=16000, vram_mb=8000))
        self.assertFalse(e.fits(ram_mb=4096, vram_mb=8000))  # 有显存没内存

    def test_落盘往返(self):
        r = ModelRegistry.from_entries([entry(), entry("m-mid", 7.0, "mid", ("pc",))])
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "reg.json")
            r.save(p)
            r2 = ModelRegistry().load(p)
        self.assertEqual(r.ids(), r2.ids())
        self.assertEqual(r2.require("m-mid").weights_hash, H["mid"])


class TestPool(unittest.TestCase):
    def setUp(self):
        self.reg = ModelRegistry.from_entries(
            # m-big 是 13B：最低内存必须写成 5000MB，否则「装不下」这条分支根本触发不到
            #（第一版夹具漏了这个参数，测试就成了假绿）。
            [entry("m-small", 0.5, "small"),
             entry("m-big", 13.0, "big", ("pc",), ram=5000)])
        self.pool = NodePool(self.reg)

    def test_权重指纹不一致不能接单(self):
        """§5 的硬门槛：同一个 model_id、不同量化版本不是同一个模型。"""
        n = node(models={"m-small": H["mid"]})   # 挂错了权重
        g = n.gate_for(self.reg.require("m-small"))
        self.assertFalse(g)
        self.assertIn("权重指纹不一致", g.reason)

    def test_未加载模型不能接单(self):
        n = node(models={})
        self.assertIn("未加载", n.gate_for(self.reg.require("m-small")).reason)

    def test_内存不足不能接单(self):
        n = node(ram_mb=2048, models={"m-big": H["big"]})
        g = n.gate_for(self.reg.require("m-big"))
        self.assertFalse(g)
        self.assertIn("内存/显存不足", g.reason)

    def test_注册表不允许该档位不能接单(self):
        n = node(tier=1, models={"m-big": H["big"]})   # 注册表里 m-big 只允许 pc
        g = n.gate_for(self.reg.require("m-big"))
        self.assertFalse(g)
        self.assertIn("不允许档位 phone_low", g.reason)

    def test_档位0的节点不接单(self):
        n = node(tier=0, models={"m-small": H["small"]})
        self.assertFalse(n.gate_for(self.reg.require("m-small")))

    def test_派单负载低者优先再按信任分(self):
        busy = node("n-busy", models={"m-small": H["small"]}, in_flight=3)
        idle = node("n-idle", models={"m-small": H["small"]})
        self.pool.upsert(busy)
        self.pool.upsert(idle)
        self.assertEqual(self.pool.pick("m-small").node.node_id, "n-idle")

        # 负载相同时（都是 0）信任分高的赢
        low = node("n-low", models={"m-small": H["small"]}, trust=0.3)
        high = node("n-high", models={"m-small": H["small"]}, trust=0.9)
        pool2 = NodePool(self.reg)
        pool2.upsert(low)
        pool2.upsert(high)
        self.assertEqual(pool2.pick("m-small").node.node_id, "n-high")

    def test_没有可接单节点时给出理由(self):
        self.pool.upsert(node("n-off", tier=0, models={"m-small": H["small"]}))
        pick = self.pool.pick("m-small")
        self.assertIsNone(pick.node)
        self.assertTrue(any("不接单" in line for line in pick.explain))

    def test_复算节点必须换个节点且单节点池返回空(self):
        e = self.reg.require("m-small")
        self.pool.upsert(node("only", models={"m-small": H["small"]}))
        self.assertIsNone(self.pool.verifier_for(e, "only"))

        self.pool.upsert(node("second", models={"m-small": H["small"]}))
        v = self.pool.verifier_for(e, "only")
        self.assertIsNotNone(v)
        self.assertNotEqual(v.node_id, "only")

    def test_降级目标取最小模型(self):
        e = NodePool.downgrade_target(self.reg, exclude=["m-big"])
        self.assertEqual(e.model_id, "m-small")

    def test_超时节点被清理(self):
        n = node("n-old", models={"m-small": H["small"]})
        self.pool.upsert(n)
        dead = self.pool.prune_stale(now=time.time() + 10_000)
        self.assertEqual(dead, ["n-old"])
        self.assertEqual(self.pool.snapshot(), [])


class TestAccounting(unittest.TestCase):
    def test_记账单位是通过复算验证的输出token(self):
        lg = CreditLedger()
        # 一期单节点：产出只能记 pending，**不发放**
        self.assertEqual(lg.record_production("n1", "t1", "m", 500, None), "pending")
        self.assertEqual(lg.ledger("n1").verified_tokens, 0)
        self.assertEqual(lg.ledger("n1").pending_tokens, 500)

        # 复算一致 → 才发放
        self.assertEqual(lg.settle("t1", True), "verified")
        self.assertEqual(lg.ledger("n1").verified_tokens, 500)
        self.assertEqual(lg.ledger("n1").pending_tokens, 0)

    def test_复算不一致不计且降信任(self):
        lg = CreditLedger()
        lg.record_production("n1", "t1", "m", 500, None)
        self.assertEqual(lg.settle("t1", False), "rejected")
        led = lg.ledger("n1")
        self.assertEqual(led.verified_tokens, 0)
        self.assertEqual(led.rejected_tasks, 1)
        self.assertLess(led.trust, 1.0)

    def test_初始额度与软上限(self):
        lg = CreditLedger()
        led = lg.ledger("c1")
        self.assertEqual(led.balance, led.initial_grant)
        self.assertFalse(lg.over_soft_cap("c1"))
        lg.record_consumption("c1", led.initial_grant + lg.soft_cap)
        self.assertTrue(lg.over_soft_cap("c1"))

    def test_贡献可以抵消透支(self):
        lg = CreditLedger()
        lg.record_consumption("c1", 50_000)
        self.assertTrue(lg.over_soft_cap("c1"))
        lg.record_production("c1", "t9", "m", 60_000, True)
        self.assertFalse(lg.over_soft_cap("c1"))

    def test_账本落盘往返(self):
        import tempfile
        lg = CreditLedger()
        lg.record_production("n1", "t1", "m", 300, None)
        lg.record_consumption("n1", 120)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "lg.json")
            lg.save(p)
            back = CreditLedger.load(p)
        self.assertEqual(back.ledger("n1").pending_tokens, 300)
        self.assertEqual(back.ledger("n1").consumed_tokens, 120)

    def test_验证开销单独可见(self):
        """「抽样复算的代价 = 算力 ×(1+x)」不能只写在文档里。"""
        lg = CreditLedger()
        lg.record_verify_cost("n2", 777)
        self.assertEqual(lg.ledger("n2").verify_cost_tokens, 777)
        self.assertEqual(lg.ledger("n2").verified_tokens, 0)


class TestProtocol(unittest.TestCase):
    def test_缺model或空messages被拒(self):
        with self.assertRaises(ProtocolError):
            parse_chat_request({"messages": [{"role": "user", "content": "hi"}]})
        with self.assertRaises(ProtocolError):
            parse_chat_request({"model": "m", "messages": []})

    def test_messages元素必须含role与content(self):
        with self.assertRaises(ProtocolError):
            parse_chat_request({"model": "m", "messages": [{"role": "user"}]})

    def test_流式明确报501而不是静默按非流式(self):
        with self.assertRaises(ProtocolError) as ctx:
            parse_chat_request({"model": "m", "stream": True,
                                "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(ctx.exception.status, 501)

    def test_响应形状是OpenAI那一套(self):
        r = build_completion("m", "答案", 10, 5, backend="stub", node_id="n1")
        self.assertEqual(r["object"], "chat.completion")
        self.assertEqual(r["choices"][0]["message"]["role"], "assistant")
        self.assertEqual(r["choices"][0]["message"]["content"], "答案")
        self.assertEqual(r["usage"], {"prompt_tokens": 10, "completion_tokens": 5,
                                      "total_tokens": 15})
        self.assertEqual(r["mb"]["served_by"], "n1")

    def test_估算token标记为估算(self):
        self.assertGreater(estimate_tokens("你好世界"), 0)
        self.assertGreater(estimate_tokens("hello world"), 0)


class SplitBackend(Backend):
    """测试替身：两次调用给出**不同**内容，用来模拟「两台机器答得不一样」。"""

    name = "split"
    is_local = True

    def __init__(self):
        self.calls = 0

    def complete(self, model_id, messages, temperature, max_tokens):
        self.calls += 1
        return InferenceResult("答案A" if self.calls <= 1 else "答案B",
                               10, 5, self.name, 1.0)


def make_coordinator(backend, nodes, model="m-small", sampling=0.0,
                     registry=None) -> Coordinator:
    reg = registry or ModelRegistry.from_entries([entry("m-small", 0.5, "small")])
    c = Coordinator(registry=reg, pool=NodePool(reg),
                    ledger=CreditLedger(sampling=sampling), backend=backend)
    for n in nodes:
        c.pool.upsert(n)
    return c


class TestCoordinatorAndHttp(unittest.TestCase):
    def test_档位0时请求被拒并说明原因(self):
        c = make_coordinator(StubBackend(), [node(tier=0, models={"m-small": H["small"]})])
        with self.assertRaises(ProtocolError) as ctx:
            c.chat(ChatRequest(model="m-small", messages=[{"role": "user", "content": "hi"}]))
        self.assertEqual(ctx.exception.status, 503)
        self.assertIn("不接单", str(ctx.exception))

    def test_未知模型是404(self):
        c = make_coordinator(StubBackend(), [node(models={"m-small": H["small"]})])
        with self.assertRaises(ProtocolError) as ctx:
            c.chat(ChatRequest(model="m-nope", messages=[{"role": "user", "content": "hi"}]))
        self.assertEqual(ctx.exception.status, 404)

    def test_单节点池的产出只记待复算(self):
        """一期必然如此：没有第二个节点能复算 → credit 恒为 0、pending 增长。
        这是草案 §11 风险 2「冷启动空池」在数据上的样子。"""
        c = make_coordinator(StubBackend(), [node(models={"m-small": H["small"]})],
                             sampling=1.0)
        resp = c.chat(ChatRequest(model="m-small", messages=[{"role": "user", "content": "1+1=?"}]))
        led = c.ledger.ledger("n1")
        self.assertEqual(resp["mb"]["credit_state"], "pending")
        self.assertEqual(led.verified_tokens, 0)
        self.assertGreater(led.pending_tokens, 0)
        self.assertIn("没有第二个", resp["mb"]["credit_note"])

    def test_两节点复算一致才发放额度(self):
        nodes = [node("n1", models={"m-small": H["small"]}),
                 node("n2", models={"m-small": H["small"]})]
        c = make_coordinator(StubBackend(), nodes, sampling=1.0)
        resp = c.chat(ChatRequest(model="m-small", messages=[{"role": "user", "content": "1+1=?"}]))
        self.assertEqual(resp["mb"]["credit_state"], "verified")
        # 复算方（不是接单方）的验证开销被单独记下来
        self.assertGreater(c.ledger.ledger("n2").verify_cost_tokens, 0)

    def test_两节点答案不一致则不计并降信任(self):
        nodes = [node("n1", models={"m-small": H["small"]}),
                 node("n2", models={"m-small": H["small"]})]
        c = make_coordinator(SplitBackend(), nodes, sampling=1.0)
        resp = c.chat(ChatRequest(model="m-small", messages=[{"role": "user", "content": "?"}]))
        self.assertEqual(resp["mb"]["credit_state"], "rejected")
        self.assertLess(min(c.ledger.ledger("n1").trust, c.ledger.ledger("n2").trust), 1.0)

    def test_软上限触发降级到最小模型(self):
        reg = ModelRegistry.from_entries([entry("m-small", 0.5, "small"),
                                         entry("m-big", 13.0, "big", ("pc",))])
        nodes = [node("n1", models={"m-small": H["small"], "m-big": H["big"]})]
        c = make_coordinator(StubBackend(), nodes, sampling=0.0, registry=reg)
        c.ledger.record_consumption("vc", 999_999)
        resp = c.chat(ChatRequest(model="m-big", messages=[{"role": "user", "content": "hi"}]),
                      consumer_id="vc")
        self.assertEqual(resp["model"], "m-small")
        self.assertEqual(resp["mb"]["downgraded_from"], "m-big")

    def test_无更小模型可降级时返回429(self):
        c = make_coordinator(StubBackend(), [node(models={"m-small": H["small"]})])
        c.ledger.record_consumption("vc", 999_999)
        with self.assertRaises(ProtocolError) as ctx:
            c.chat(ChatRequest(model="m-small", messages=[{"role": "user", "content": "hi"}]),
                   consumer_id="vc")
        self.assertEqual(ctx.exception.status, 429)


class TestHttpEndToEnd(unittest.TestCase):
    """真起一个 HTTP 服务再打进去 —— 协议形状错了必须在这里暴露，
    而不是等模块 A 端跑一轮发现「模型返回空」。"""

    @classmethod
    def setUpClass(cls):
        c = make_coordinator(StubBackend(), [node("pc-1", models={"m-small": H["small"]})])
        cls.srv = serve_in_thread(c, "127.0.0.1", 0)
        cls.port = cls.srv.server_address[1]
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def _get(self, path):
        with urllib.request.urlopen(self.base + path, timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))

    def _post(self, path, body):
        req = urllib.request.Request(self.base + path, method="POST",
                                     data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))

    def test_健康检查与模型列表(self):
        _, h = self._get("/healthz")
        self.assertTrue(h["ok"])
        self.assertEqual(h["models"], 1)
        _, lst = self._get("/v1/models")
        self.assertEqual(lst["object"], "list")
        self.assertEqual(lst["data"][0]["id"], "m-small")

    def test_端到端对话(self):
        """这就是模块 A 会发出的请求形状（非流式 + 无 Authorization）。"""
        st, resp = self._post("/v1/chat/completions", {
            "model": "m-small",
            "messages": [{"role": "user", "content": "你好"}],
            "temperature": 0.0, "max_tokens": 64,
        })
        self.assertEqual(st, 200)
        self.assertEqual(resp["choices"][0]["message"]["role"], "assistant")
        self.assertIn("[stub:m-small]", resp["choices"][0]["message"]["content"])
        self.assertGreater(resp["usage"]["total_tokens"], 0)
        self.assertEqual(resp["mb"]["backend"], "stub")

    def test_流式返回501且错误体是OpenAI形状(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/v1/chat/completions", {
                "model": "m-small", "stream": True,
                "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(ctx.exception.code, 501)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertIn("error", body)

    def test_配了Key就必须带Key(self):
        c = make_coordinator(StubBackend(), [node("pc-1", models={"m-small": H["small"]})])
        srv = serve_in_thread(c, "127.0.0.1", 0, api_key="s3cret")
        try:
            base = f"http://127.0.0.1:{srv.server_address[1]}"
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(base + "/v1/models", timeout=10)
            self.assertEqual(ctx.exception.code, 401)
            req = urllib.request.Request(base + "/v1/models",
                                         headers={"Authorization": "Bearer s3cret"})
            with urllib.request.urlopen(req, timeout=10) as r:
                self.assertEqual(r.status, 200)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_管理面能看到节点与账本(self):
        _, st = self._get("/admin/state")
        self.assertEqual(st["pool"][0]["node_id"], "pc-1")
        self.assertEqual(st["pool"][0]["tier_key"], "pc")
        self.assertIn("unit", st["ledger"])
        with urllib.request.urlopen(self.base + "/admin/panel", timeout=10) as r:
            body = r.read().decode("utf-8")
        self.assertIn("模块 B · 算力共享节点面板", body)
        self.assertIn("pc-1", body)

    def test_面板能改档位(self):
        st, n = self._post("/admin/tier", {"node_id": "pc-1", "tier": 0})
        self.assertEqual(n["tier"], 0)
        # 档位 0 之后就不该再接单了
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/v1/chat/completions", {
                "model": "m-small",
                "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(ctx.exception.code, 503)
        self._post("/admin/tier", {"node_id": "pc-1", "tier": 3})


if __name__ == "__main__":
    unittest.main(verbosity=2)
