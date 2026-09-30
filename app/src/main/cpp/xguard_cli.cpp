// xguard_cli —— 设备端推理验收工具。
//
// 存在的唯一理由：**让"端上判定 == PC 判定"这件事可以被脚本验证，而不是靠肉眼看界面。**
//
// 为什么不是"在 App 里点一下看看"：
//   那样只能验一次。之后每次改模板/改阈值/升级 llama.cpp，都没有人会再去点一遍，
//   于是端上路径会静静地腐烂，直到某天有人在真机上发现判定全错。
//   有了这个 CLI，端上推理可以被拉回 PC 逐条比对（见 tools/verify-ondevice.py）。
//
// 为什么由 PC 传"已渲染好的 prompt"进来：
//   模板渲染只允许有一份实现（Kotlin 的 XGuardPrompt）。CLI 不参与渲染，
//   否则模板就在两个语言里各写一遍，漂移只是时间问题。
//
// 用法：
//   xguard_cli <model.gguf> <prompts-file> [--topk N] [--maxtokens N]
//              [--ctx N] [--threads N] [--limit N]
//
//   prompts-file：多条 prompt 用 0x1E（ASCII RS，换页分隔符）连接。
//                 用 RS 而不是换行，是因为 prompt 本身含大量换行。
//                 文件末尾的 RS 与空记录会被忽略。
//
// 输出：stdout 每行一个 JSON 对象（与 JNI 的 nativeClassify 返回体同构、同序），
//       进度与耗时打到 stderr，方便 `> out.jsonl` 时不被污染。
//
// 注意：这个可执行文件**不参与 APK 打包**，只用于验收，所以可以放心地
//       依赖 stdout、退出码这些"不像 Android 组件"的东西。

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "xguard_core.h"

namespace {

constexpr char RS = '\x1e';

std::vector<std::string> split_records(const std::string &data) {
    std::vector<std::string> out;
    size_t start = 0;
    while (true) {
        size_t pos = data.find(RS, start);
        if (pos == std::string::npos) {
            std::string tail = data.substr(start);
            if (!tail.empty()) out.push_back(tail);
            break;
        }
        out.push_back(data.substr(start, pos - start));
        start = pos + 1;
    }
    // 丢掉纯空白记录：文件末尾常见一个多余的分隔符
    std::vector<std::string> cleaned;
    cleaned.reserve(out.size());
    for (auto &r : out) {
        const bool blank = r.find_first_not_of(" \t\r\n") == std::string::npos;
        if (!blank) cleaned.push_back(std::move(r));
    }
    return cleaned;
}

bool read_file(const char *path, std::string &out) {
    FILE *f = fopen(path, "rb");
    if (f == nullptr) return false;
    char buf[1 << 16];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    fclose(f);
    return true;
}

const char *arg_value(int argc, char **argv, const char *name, const char *fallback) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (strcmp(argv[i], name) == 0) return argv[i + 1];
    }
    return fallback;
}

int32_t arg_int(int argc, char **argv, const char *name, int32_t fallback) {
    const char *v = arg_value(argc, argv, name, nullptr);
    if (v == nullptr) return fallback;
    const int32_t parsed = atoi(v);
    return parsed > 0 ? parsed : fallback;
}

}  // namespace

// ---------------------------------------------------------------------------
// --selftest：验 UTF-16 → UTF-8 的转换
// ---------------------------------------------------------------------------
//
// 为什么这个自检必须存在：
//   JNI 侧用 `GetStringUTFChars` 会拿到 modified UTF-8（CESU-8），非 BMP 字符
//   （emoji）会被编成两个 3 字节代理对而不是标准 4 字节 —— 于是"用户在 prompt 里
//   打了个 emoji"就会让端上的 token 序列与 PC 不一致，危险度对不上，
//   而报告里完全看不出来。这类静默偏差查起来最费劲，所以必须有一条能自动跑的检查。
//
// 而这个转换函数恰恰是 **JNI 路径与 CLI 路径唯一不共享**的地方
// （CLI 的 prompt 从文件读，天然是标准 UTF-8），所以只能在设备上单独验它。

namespace {

std::string hex_of(const std::string &s) {
    std::string out;
    char buf[8];
    for (unsigned char c : s) {
        snprintf(buf, sizeof(buf), "%02X ", c);
        out += buf;
    }
    if (!out.empty()) out.pop_back();
    return out;
}

int check_one(const char *name, const char16_t *in, int32_t len,
              const std::string &expect) {
    const std::string got = xguard::utf16_to_utf8(in, len);
    const bool ok = (got == expect);
    printf("  %s %-28s ", ok ? "PASS" : "FAIL", name);
    if (!ok) {
        printf("expect=%s got=%s", hex_of(expect).c_str(), hex_of(got).c_str());
    }
    printf("\n");
    return ok ? 0 : 1;
}

int selftest() {
    printf("[xguard_cli] UTF-16 → UTF-8 自检\n");
    int fails = 0;

    // 纯 ASCII：逐字节相同
    {
        static const char16_t s[] = u"abc";
        fails += check_one("ascii", s, 3, std::string("abc"));
    }
    // BMP 中文：各 3 字节
    {
        static const char16_t s[] = u"中文";
        fails += check_one("bmp-中文", s, 2, std::string("\xE4\xB8\xAD\xE6\x96\x87", 6));
    }
    // 非 BMP（U+1F600）：标准 UTF-8 是 4 字节 F0 9F 98 80；
    // 如果用 GetStringUTFChars 会得到 6 字节的 CESU-8，这里就是防它。
    {
        static const char16_t s[] = u"\U0001F600";
        fails += check_one("nonbmp-😀", s, 2, std::string("\xF0\x9F\x98\x80", 4));
    }
    // 混合：占位符/中文/emoji 混排，模拟真实 prompt
    // 注意 `"..." "b"` 这种相邻字面量拼接不是凑格式：C++ 的 \x 转义会**贪婪地**吞掉后续
    // 十六进制字符，`"\x80b"` 会被解析成一个越界的转义（0x80b）而编译失败。
    // 长度 9 = "a"(1) + "中"(3) + "😀"(4) + "b"(1)。
    {
        static const char16_t s[] = u"a中\U0001F600b";
        fails += check_one("mixed", s, 5,
                           std::string("a\xE4\xB8\xAD\xF0\x9F\x98\x80" "b", 9));
    }
    // 空串
    fails += check_one("empty", u"", 0, std::string());
    // 孤立高位代理（畸形输入）：要求不崩、不越界，输出内容不作要求
    {
        static const char16_t s[] = u"\xD83D";
        const std::string got = xguard::utf16_to_utf8(s, 1);
        const bool ok = !got.empty();
        printf("  %s %-28s （孤立代理不崩）\n", ok ? "PASS" : "FAIL", "lone-surrogate");
        if (!ok) ++fails;
    }

    printf("[xguard_cli] 自检%s\n", fails == 0 ? "全部通过" : "有失败项");
    return fails == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--selftest") == 0) {
        return selftest();
    }

    if (argc < 3) {
        fprintf(stderr,
                "用法: xguard_cli <model.gguf> <prompts-file> "
                "[--topk N] [--maxtokens N] [--ctx N] [--threads N] [--limit N]\n"
                "      xguard_cli --selftest   # 验 UTF-16→UTF-8 转换（JNI 路径的共用函数）\n"
                "  prompts-file 里多条 prompt 用 0x1E 分隔；stdout 每行一个 JSON。\n");
        return 2;
    }

    const char *model_path = argv[1];
    const char *prompts_path = argv[2];

    std::string blob;
    if (!read_file(prompts_path, blob)) {
        fprintf(stderr, "无法读取 prompts 文件: %s\n", prompts_path);
        return 2;
    }
    std::vector<std::string> prompts = split_records(blob);
    const int32_t limit = arg_int(argc, argv, "--limit", 0);
    if (limit > 0 && static_cast<int32_t>(prompts.size()) > limit) {
        prompts.resize(static_cast<size_t>(limit));
    }
    if (prompts.empty()) {
        fprintf(stderr, "prompts 文件里没有有效记录\n");
        return 2;
    }

    const int32_t top_k = arg_int(argc, argv, "--topk", 20);
    const int32_t max_tokens = arg_int(argc, argv, "--maxtokens", 320);
    const int32_t n_ctx = arg_int(argc, argv, "--ctx", 2048);
    const int32_t n_threads = arg_int(argc, argv, "--threads", 4);

    fprintf(stderr, "[xguard_cli] 加载模型 %s (ctx=%d threads=%d)\n",
            model_path, n_ctx, n_threads);
    xguard::Handle *h = xguard::load_model(model_path, n_ctx, n_threads);
    if (h == nullptr) {
        fprintf(stderr, "[xguard_cli] 模型加载失败: %s\n", model_path);
        return 1;
    }
    fprintf(stderr, "[xguard_cli] 就绪 n_vocab=%d n_ctx=%d；共 %zu 条 prompt\n",
            h->n_vocab, h->n_ctx, prompts.size());

    for (size_t i = 0; i < prompts.size(); ++i) {
        const std::string json = xguard::classify(h, prompts[i], top_k, max_tokens);
        fputs(json.c_str(), stdout);
        fputc('\n', stdout);
        fflush(stdout);           // 被 adb shell 管道接住时，不 flush 会看起来像卡死
        fprintf(stderr, "[xguard_cli] %zu/%zu 完成\n", i + 1, prompts.size());
    }

    xguard::free_model(h);
    fprintf(stderr, "[xguard_cli] 全部完成\n");
    return 0;
}
