// XGuard 端上护栏核心实现（不认识 JNI，见 xguard_core.h 的说明）。
#include "xguard_core.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr int32_t DEFAULT_TOP_K = 20;
constexpr int32_t DEFAULT_MAX_TOKENS = 320;
constexpr int32_t DEFAULT_N_CTX = 2048;
constexpr int32_t DEFAULT_N_THREADS = 4;
constexpr int32_t N_BATCH = 512;

// ---------------------------------------------------------------------------
// UTF-8 清洗 + JSON 转义
// ---------------------------------------------------------------------------

// 词表的 token piece 是 GPT-2 字节级 BPE 的产物，单个 piece 可能是某个多字节字符的**前半段**，
// 于是它不是合法 UTF-8。直接塞进 JSON 会让整个报文在 Kotlin 侧解析失败（或静默变乱码）。
// 这里按 UTF-8 规则逐字符走一遍，非法序列替换成 U+FFFD，保证输出一定是合法 UTF-8。
std::string utf8_sanitize(const std::string &in) {
    static const char REPL[] = "\xEF\xBF\xBD";
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    const size_t n = in.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        size_t len = 0;
        if (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;

        bool ok = (len != 0) && (i + len <= n);
        if (ok && len > 1) {
            for (size_t k = 1; k < len; ++k) {
                if ((static_cast<unsigned char>(in[i + k]) & 0xC0) != 0x80) { ok = false; break; }
            }
        }
        if (ok) {
            out.append(in, i, len);
            i += len;
        } else {
            out.append(REPL);
            i += 1;
        }
    }
    return out;
}

std::string json_escape(const std::string &in) {
    const std::string s = utf8_sanitize(in);
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

std::string token_piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    // lstrip=0 / special=false：我们要的是这个 token 的原样字节，
    // 不做"跳过前导空格"或"渲染特殊 token"的处理。
    const int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n <= 0) return {};
    const size_t len = std::min<size_t>(static_cast<size_t>(n), sizeof(buf));
    return std::string(buf, len);
}

bool tokenize_all(const llama_vocab *vocab, const std::string &text,
                  std::vector<llama_token> &out) {
    // parse_special=true 很关键：prompt 里的 <|im_start|> / <|im_end|> 必须被识别成特殊 token，
    // 否则会被当普通文本切碎，模板就白渲染了。
    // add_special=false 与 PC 侧对齐（实测服务端 prompt_tokens=345/405 与 add_bos=False 一致）。
    const int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                      nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    if (n <= 0) return false;
    out.resize(static_cast<size_t>(n));
    const int32_t m = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                     out.data(), n, false, true);
    if (m < 0) return false;
    out.resize(static_cast<size_t>(m));
    return !out.empty();
}

// 对全词表做 softmax，取概率最高的 k 个。
// 必须全词表归一化（而不是只在 29 个代号上归一化）—— 这样算出来的 P(sec)
// 才和 PC 侧 logprobs 的数值同分布，两条路径的危险度才可比。
//
// 名字带 softmax_ 前缀是为了避开 JNI 入口的参数名 `top_k`：C++ 里同名的内层
// 声明会遮蔽外层函数，导致 `top_k(...)` 被当成"调用一个 int"而编译失败。
std::vector<std::pair<int32_t, float>> softmax_top_k(const float *logits, int32_t n_vocab,
                                                     int32_t k) {
    float max_logit = -INFINITY;
    for (int32_t i = 0; i < n_vocab; ++i) max_logit = std::max(max_logit, logits[i]);

    std::vector<float> probs(static_cast<size_t>(n_vocab));
    double sum = 0.0;
    for (int32_t i = 0; i < n_vocab; ++i) {
        const float p = std::exp(logits[i] - max_logit);
        probs[static_cast<size_t>(i)] = p;
        sum += p;
    }
    if (sum <= 0.0) return {};

    std::vector<int32_t> idx(static_cast<size_t>(n_vocab));
    for (int32_t i = 0; i < n_vocab; ++i) idx[static_cast<size_t>(i)] = i;
    const int32_t kk = std::min(k, n_vocab);
    std::partial_sort(idx.begin(), idx.begin() + kk, idx.end(),
                      [&](int32_t a, int32_t b) { return probs[a] > probs[b]; });

    std::vector<std::pair<int32_t, float>> out;
    out.reserve(static_cast<size_t>(kk));
    for (int32_t i = 0; i < kk; ++i) {
        const int32_t t = idx[static_cast<size_t>(i)];
        out.emplace_back(t, static_cast<float>(probs[static_cast<size_t>(t)] / sum));
    }
    return out;
}

int32_t argmax(const float *logits, int32_t n_vocab) {
    int32_t best = 0;
    float bv = logits[0];
    for (int32_t i = 1; i < n_vocab; ++i) {
        if (logits[i] > bv) { bv = logits[i]; best = i; }
    }
    return best;
}

std::string json_error(const std::string &msg) {
    return "{\"error\":\"" + json_escape(msg) + "\"}";
}

}  // namespace

namespace xguard {

void backend_init_once() {
    static std::once_flag flag;
    std::call_once(flag, [] { llama_backend_init(); });
}

std::string utf16_to_utf8(const char16_t *data, int32_t len) {
    std::string out;
    if (data == nullptr || len <= 0) return out;
    out.reserve(static_cast<size_t>(len) * 3);

    for (int32_t i = 0; i < len; ++i) {
        uint32_t cp = data[i];

        // 代理对：Java 的 String 用 UTF-16，非 BMP 字符是 high+low 两个 code unit。
        // 不合并的话会编出 CESU-8（6 字节），与 PC 的 tokenizer 就对不上了。
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = data[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }

        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return out;
}

Handle *load_model(const std::string &path, int32_t n_ctx, int32_t n_threads) {
    backend_init_once();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // 端上先只跑 CPU：GPU 后端要额外配 OpenCL/NNAPI，另行评估

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) return nullptr;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : DEFAULT_N_CTX);
    cparams.n_batch = N_BATCH;
    cparams.n_ubatch = N_BATCH;
    cparams.n_threads = n_threads > 0 ? n_threads : DEFAULT_N_THREADS;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        return nullptr;
    }

    auto *h = new Handle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = vocab;
    h->n_vocab = llama_vocab_n_tokens(vocab);
    h->n_ctx = static_cast<int32_t>(cparams.n_ctx);
    h->n_batch = static_cast<int32_t>(cparams.n_batch);
    return h;
}

void free_model(Handle *h) {
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(h->mtx);
        if (h->ctx != nullptr) llama_free(h->ctx);
        if (h->model != nullptr) llama_model_free(h->model);
        h->ctx = nullptr;
        h->model = nullptr;
    }
    delete h;
}

std::string classify(Handle *h, const std::string &prompt, int32_t top_k,
                     int32_t max_tokens) {
    if (h == nullptr) return json_error("handle 为空");

    std::lock_guard<std::mutex> lock(h->mtx);

    std::vector<llama_token> tokens;
    if (!tokenize_all(h->vocab, prompt, tokens)) {
        return json_error("prompt 分词失败");
    }
    if (static_cast<int32_t>(tokens.size()) >= h->n_ctx) {
        return json_error("prompt 超出上下文长度: " + std::to_string(tokens.size()) +
                          " >= " + std::to_string(h->n_ctx));
    }

    const int32_t prompt_tokens = static_cast<int32_t>(tokens.size());
    const int32_t k = top_k > 0 ? top_k : DEFAULT_TOP_K;
    const int32_t budget = std::min<int32_t>(
            max_tokens > 0 ? max_tokens : DEFAULT_MAX_TOKENS, h->n_ctx - prompt_tokens);

    // 每次调用都从位置 0 开始：不清 KV 缓存的话第二轮会把上一轮的
    // 上下文接在后面，判定结果完全不同（而且很难看出来）。
    llama_memory_clear(llama_get_memory(h->ctx), true);

    const int64_t t0 = ggml_time_us();

    // 分块喂 prompt：n_batch 是单次 decode 的上限，长 prompt 必须切开
    for (size_t off = 0; off < tokens.size();) {
        const int32_t chunk = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(h->n_batch), tokens.size() - off));
        llama_batch batch = llama_batch_get_one(tokens.data() + off, chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            return json_error("prompt decode 失败");
        }
        off += static_cast<size_t>(chunk);
    }

    const float *logits = llama_get_logits_ith(h->ctx, -1);
    if (logits == nullptr) return json_error("拿不到首位置 logits");

    // 首位置的分布就是结论（模板结尾停在 </think> 后的空行，
    // 模型紧接着吐的就是风险代号，实测不会先吐换行）
    const auto top = softmax_top_k(logits, h->n_vocab, k);
    int32_t next = argmax(logits, h->n_vocab);

    std::string text;
    int32_t generated = 0;
    bool truncated = false;
    while (generated < budget) {
        if (llama_vocab_is_eog(h->vocab, next)) break;
        text += token_piece(h->vocab, next);
        ++generated;
        if (generated >= budget) break;

        llama_batch one = llama_batch_get_one(&next, 1);
        if (llama_decode(h->ctx, one) != 0) {
            // 已经拿到的首位置 top-k 仍然有效（结论不依赖后续生成），
            // 所以这里不整体失败，只标记截断。
            truncated = true;
            break;
        }
        const float *l2 = llama_get_logits_ith(h->ctx, -1);
        if (l2 == nullptr) { truncated = true; break; }
        next = argmax(l2, h->n_vocab);
    }

    const int64_t t1 = ggml_time_us();
    std::string j = "{\"top\":[";
    for (size_t i = 0; i < top.size(); ++i) {
        if (i) j += ",";
        j += "{\"token\":\"" + json_escape(token_piece(h->vocab, top[i].first)) +
             "\",\"p\":" + std::to_string(top[i].second) + "}";
    }
    j += "],\"text\":\"" + json_escape(text) + "\"";
    j += ",\"promptTokens\":" + std::to_string(prompt_tokens);
    j += ",\"generated\":" + std::to_string(generated);
    j += ",\"ms\":" + std::to_string((t1 - t0) / 1000);
    if (truncated) j += ",\"truncated\":true";
    j += "}";
    return j;
}

}  // namespace xguard
