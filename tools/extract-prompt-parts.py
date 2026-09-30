"""从 GGUF 里取出 chat_template，用 jinja2 渲染，导出逐字节精确的前缀/后缀。

为什么这么做：模板里 {% if policy %}、{% if reason_first %} 这些分支周围的换行
在渲染时会被消费掉，手抄一定错。而前缀只要错一个字节，
端上（native 自己拼 prompt）和 PC（服务端拼 prompt）的输入就不一致 ——
判定结果对不上时你根本无从下手。

产出：xguard-prompt-parts.json —— 供 Kotlin 常量与端上 C++ 共用。
"""
import hashlib
import json

import jinja2
import llama_cpp

MODEL = r"D:/models/YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"

llm = llama_cpp.Llama(model_path=MODEL, n_ctx=2048, n_threads=4,
                      n_gpu_layers=0, vocab_only=True, verbose=False)
tmpl = llama_cpp.llama_model_chat_template(llm.model, None).decode("utf-8")
print("template sha256:", hashlib.sha256(tmpl.encode()).hexdigest())

env = jinja2.Environment(trim_blocks=False, lstrip_blocks=False, keep_trailing_newline=True)
jt = env.from_string(tmpl)

QUERY = "@@QUERY@@"
RESP = "@@RESP@@"

# 与 llama.cpp 的 chat handler 等价：只给 2 条消息，不传 policy / reason_first
rendered = jt.render(
    messages=[{"role": "user", "content": QUERY},
              {"role": "assistant", "content": RESP}],
)

i = rendered.index(QUERY)
j = rendered.index(RESP)
PREFIX = rendered[:i]
MID = rendered[i + len(QUERY):j]
SUFFIX = rendered[j + len(RESP):]

print("\n=== PREFIX (repr) ===")
print(repr(PREFIX))
print("\n=== MID (repr) ===")
print(repr(MID))
print("\n=== SUFFIX (repr) ===")
print(repr(SUFFIX))

print("\n=== 定界符检查 ===")
print("PREFIX 以 'Input Text: ' 结尾:", PREFIX.endswith("Input Text: "))
print("SUFFIX 以两个换行结尾:", SUFFIX.endswith("\n\n"))

# 单消息分支也确认一下（不该被用到，但要确认差异）
single = jt.render(messages=[{"role": "user", "content": QUERY}])
print("单消息分支长度:", len(single), " 双消息长度:", len(rendered))

data = {"prefix": PREFIX, "mid": MID, "suffix": SUFFIX,
        "template_sha256": hashlib.sha256(tmpl.encode()).hexdigest()}
with open("C:/AISecLab/tools/xguard-prompt-parts.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("\n已写出 tools/xguard-prompt-parts.json")
