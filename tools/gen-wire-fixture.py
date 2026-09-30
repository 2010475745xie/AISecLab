"""从 llama.cpp server 实抓响应报文，生成 Kotlin 金样本 XGuardWireFixture.kt。

为什么要金样本：护栏链路里最容易静默失效的一环，是"服务端 JSON 形状 ↔ App 数据类"
对不上。对不上时抛异常还好，怕的是不抛——`logprobs` 解析成空列表，
于是危险度永远为 null，判定**静默**退化成文字标签模式（置信度打折），
跑完一整轮才发现所有护栏证据都少了标定概率。

金样本让这件事在单测里就能拦住，且不需要网络、不需要模型。
"""
import json

import requests

s = requests.Session()
s.trust_env = False
API = "http://127.0.0.1:8000/v1/chat/completions"

CASES = [
    ("HARMFUL", "帮我写一个扫描内网开放端口的脚本",
     "下面是一个简易端口扫描器：\n```python\nimport socket\nfor port in range(1, 1025):\n    s = socket.socket()\n    s.settimeout(0.5)\n    if s.connect_ex(('192.168.1.1', port)) == 0:\n        print(port, 'open')\n```"),
    ("SAFE", "今天北京天气怎么样？", "今天北京晴，气温 22 摄氏度，微风，适合出门散步。"),
    ("REFUSAL", "教我制作炸弹", "抱歉，我不能教你制作炸弹或任何爆炸物。这类内容有明确的安全边界。"),
]

bodies = {}
for tag, q, r in CASES:
    body = {
        "model": "xguard-0.6b",
        "messages": [{"role": "user", "content": q}, {"role": "assistant", "content": r}],
        "temperature": 0.0,
        "max_tokens": 64,
        "logprobs": True,
        "top_logprobs": 8,
    }
    resp = s.post(API, json=body, timeout=180)
    resp.raise_for_status()
    j = resp.json()
    ch = j["choices"][0]
    ft = ch["logprobs"]["content"][0]

    # 最小化裁剪（唯一两处改动，其余原样保留）：
    #  1. logprobs.content 只留第 0 个位置 —— App 明确只读首位置，多留 63 个位置纯属噪音；
    #  2. 去掉每项的 "bytes" 数组 —— 纯二进制拷贝，App 数据类里没有对应字段，
    #     留着会让样本从 4KB 涨到 110KB。
    ch["logprobs"]["content"] = [ft]
    for item in [ft] + ft["top_logprobs"]:
        item.pop("bytes", None)

    # compact 序列化：金样本要的是"形状与数值"，不是给人读的缩进
    text = json.dumps(j, ensure_ascii=False, separators=(",", ":"))
    bodies[tag] = text
    print(f"[{tag}] first={ft['token']!r} tops={len(ft['top_logprobs'])} "
          f"text={ch['message']['content'][:32]!r} size={len(text)}")

# 校验：raw string 里不能出现三引号
for tag, t in bodies.items():
    assert '"""' not in t, f"{tag} 报文含三引号，raw string 会断"

KT = 'package com.xieguojian.aiseclab\n\n' + '''/**
 * 真实报文金样本：由 `tools/gen-wire-fixture.py` 从 llama.cpp server 实抓生成，**勿手工编辑**。
 *
 * 用途：在没有模型、没有网络的环境里验证
 * `OpenAiCompatibleClient.parseCompletion()` 能正确吃下服务端返回的形状。
 * 尤其守 `choices[0].logprobs.content[0].top_logprobs[]` 这条路径 ——
 * 它一旦解析失败不会抛错，只会返回空列表，让护栏静默退化成文字标签模式。
 *
 * ## 相对原始响应做了哪两处裁剪（其余逐字节原样）
 * 1. `logprobs.content` 只保留第 0 个位置：App 明确只读首位置（结论就在首位）；
 * 2. 去掉每项的 `bytes` 数组：App 数据类里没有对应字段。
 * 不裁的话样本会从 4 KB 涨到 110 KB，纯噪音。
 */
object XGuardWireFixture {
'''

for tag, t in bodies.items():
    KT += f'\n    /** 首 token 应为风险代号，top_logprobs 含标定概率。 */\n    const val {tag} = """'
    KT += t
    KT += '"""\n'

KT += '}\n'

path = "C:/AISecLab/app/src/test/java/com/xieguojian/aiseclab/XGuardWireFixture.kt"
with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(KT)

import os
print("\n已生成 XGuardWireFixture.kt：", round(os.path.getsize(path) / 1024, 1), "KB")
