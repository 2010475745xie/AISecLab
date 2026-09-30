"""契约验证 1/3 —— 元数据与分词器（修正版）。"""
import llama_cpp

MODEL = r"D:/models/YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf"

llm = llama_cpp.Llama(model_path=MODEL, n_ctx=2048, n_threads=8,
                      n_gpu_layers=0, verbose=False)
meta = llm.metadata
print("n_vocab =", llm.n_vocab())
print("qwen3.context_length =", meta.get("qwen3.context_length"))
print("qwen3.block_count =", meta.get("qwen3.block_count"))
print("qwen3.embedding_length =", meta.get("qwen3.embedding_length"))

# 官方模板里给出的**真实** 29 类代号
REAL = ["sec", "pc", "dc", "dw", "pi", "ec", "ac", "def", "ti", "cy",
        "ph", "mh", "se", "sci", "pp", "cs", "acc", "mc", "ha", "ps",
        "ter", "sd", "ext", "fin", "med", "law", "cm", "ma", "md"]

print(f"\n=== 代号单 token 检查（共 {len(REAL)} 类）===")
single, multi = [], []
for c in REAL:
    ids = llm.tokenize(c.encode(), add_bos=False, special=False)
    ids_sp = llm.tokenize((" " + c).encode(), add_bos=False, special=False)
    if len(ids) == 1:
        single.append(c)
        print(f"  {c:<4} id={ids[0]:<7} 空格变体={ids_sp}")
    else:
        pieces = [llm.detokenize([i]).decode('utf-8', 'replace') for i in ids]
        multi.append(c)
        print(f"  {c:<4} 多token {ids} {pieces}  空格变体={ids_sp}")

print(f"\n单 token {len(single)}/{len(REAL)}；多 token: {multi}")

# 输出格式探针：模板要求形如  sec\n<explanation>...</explanation>
print("\n=== 结构 probing ===")
for probe in ["sec\n<explanation>test</explanation>",
              "\nsec",
              "sec",
              "\n\nsec"]:
    ids = llm.tokenize(probe.encode(), add_bos=False, special=False)
    print(f"  {probe!r:<42} -> {ids}  {[llm.detokenize([i]).decode('utf-8','replace') for i in ids]}")
