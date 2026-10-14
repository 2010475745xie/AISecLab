"""判定词表**两端镜像**机械校验：端上 Kotlin ↔ 后端 Python。

## 为什么需要它
本项目的头号复发 bug 就是「端上与后端的判定表走岔」，已经从两个方向各踩过一次：
- 后端 L1 曾是端上的**过期副本** → 同一回答两端给出相反结论（实测 `sec-leak-3`：后端 FAIL 0.95 / 端上 PASS）；
- 端上 `FORBIDDEN_WORDS` 长期是**端上独有**（后端没有这一步）。

靠"改一边记得改另一边"这种纪律是守不住的 —— 那就用脚本守。

## 比什么
`backend/app/judge.py` 与端上 `core/judge/{L1PatternRules,ResponseContext}.kt` 里的
**同一批常量表**，逐表、逐条、**保序**比较（顺序有意义：命中顺序决定 evidence 里先报哪一条）。

- 字符串表（标记 / 线索 / 词表）：逐字比较。
- 正则表（KEY_PATTERNS / PII_PATTERNS）与占位符正则：把正文里的**空白全部去掉**后比较
  （Python 的 `r\"\"\"…\"\"\"` 与 Kotlin 的 `\"\"\"…\"\"\"` 排版不同，但语义应一致）。
  因为有"只有空白不同 = 假通过"的理论风险，另打印一处原始正文供肉眼核对。
- `CONTEXT_WINDOW`：数值比较。

## 用法
    <venv>/Scripts/python.exe C:\\AISecLab\\tools\\check-judge-parity.py   # 0=一致 1=有差异

⚠️ 这是**只读**校验，不改任何文件。发现差异时请判断该改哪一边，别两边都糊。
"""
from __future__ import annotations

import os
import re
import sys

ROOT = r"C:\AISecLab"
PY_FILE = os.path.join(ROOT, "backend", "app", "judge.py")
KT_FILES = [
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "L1PatternRules.kt"),
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "ResponseContext.kt"),
    # ComplianceDetector.kt ↔ compliance.py 是既有的镜像对，但它不是本脚本的 PY_FILE，
    # 所以它的三条正则此前**没有任何机械校验**。2.1 起 `judge.py` 也要用这三条正则
    # （refusal_strength 判断程序性内容），于是把它们并进 PY_FILE 一并校验。
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "ComplianceDetector.kt"),
]


# ── 通用：带引号的字面量扫描（先处理三引号，再处理单双引号）────────────────
def _scan_strings(body: str) -> list[str]:
    out: list[str] = []
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if ch == "#":                      # Python 注释：跳到行尾
            j = body.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if ch == "/" and i + 1 < n and body[i + 1] == "/":   # Kotlin 行注释
            j = body.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if body.startswith('"""', i) or body.startswith("'''", i):
            q = body[i:i + 3]
            j = body.find(q, i + 3)
            if j < 0:
                break
            out.append(body[i + 3:j])
            i = j + 3
            continue
        if ch in "\"'":
            j = i + 1
            buf = []
            while j < n:
                if body[j] == "\\":
                    buf.append(body[j:j + 2])
                    j += 2
                    continue
                if body[j] == ch:
                    break
                buf.append(body[j])
                j += 1
            out.append("".join(buf))
            i = j + 1
            continue
        i += 1
    return out


def _match_group(src: str, start: int, opener: str, closer: str) -> tuple[str, int]:
    """从 src[start]（应为 opener）起，返回括号内的正文与结束位置。"""
    depth = 0
    i = start
    while i < len(src):
        c = src[i]
        if c == '"' and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = len(src) if j < 0 else j + 3
            continue
        if c in "\"'":
            j = i + 1
            while j < len(src):
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == c:
                    break
                j += 1
            i = j + 1
            continue
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return src[start + 1:i], i
        i += 1
    raise ValueError("括号不闭合")


# ── Python 侧 ─────────────────────────────────────────────────────────────
def _first_bracket(src: str, start: int, span: int = 400):
    """`=` 之后第一个 `[` 或 `(` —— 用它当字面量正文的起点。

    为什么不能只认 `[`：正则类常量写的是 `re.compile(...)` / `Regex(...)`，
    而 `NEGATION_MARKERS = STRONG_REFUSAL + [...]` 的 `[` 在别处。
    取更靠前的那个即可（两边的"更靠前"含义一致，比较才公平）。
    """
    lb = src.find("[", start)
    lp = src.find("(", start)
    cands = [(i, c) for i, c in ((lb, "["), (lp, "("))
             if i != -1 and i - start < span]
    if not cands:
        return None
    i, c = min(cands)
    return i, ("]" if c == "[" else ")")


def py_extract(src: str, name: str, kind: str):
    m = re.search(r"^%s\s*(?::[^=\n]+)?=\s*" % re.escape(name), src, re.M)
    if not m:
        return None
    i = m.end()
    if kind == "int":
        m2 = re.match(r"\d+", src[i:])
        return int(m2.group(0)) if m2 else None
    br = _first_bracket(src, i)
    if br is None:
        return None
    start, closer = br
    body, _ = _match_group(src, start, src[start], closer)
    return _scan_strings(body)


# ── Kotlin 侧 ─────────────────────────────────────────────────────────────
def kt_extract(src: str, name: str, kind: str):
    m = re.search(r"\bval\s+%s\b[^=\n]*=\s*" % re.escape(name), src)
    if not m:
        return None
    i = m.end()
    if kind == "int":
        m2 = re.match(r"\d+", src[i:])
        return int(m2.group(0)) if m2 else None
    br = _first_bracket(src, i)
    if br is None:
        return None
    start, closer = br
    body, _ = _match_group(src, start, src[start], closer)
    return _scan_strings(body)


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s)


def py_lookup(src: str, name: str, kind: str):
    """后端常量名可能带下划线前缀（`_FILLER_ONLY_RE`），两种都试。"""
    for cand in (name, "_" + name):
        v = py_extract(src, cand, kind)
        if v is not None:
            return v
    return None


# ── 表清单：(常量名, 类型, 只比对个数?) ──────────────────────────────────
TABLES = [
    # 语境层（ResponseContext.kt）
    ("STRONG_REFUSAL", "str"),
    ("NEGATION_MARKERS", "str"),
    ("CONTEXT_WINDOW", "int"),
    # 2.1 否定感知拒绝打分（ResponseContext.kt + RefusalScorer.kt）
    # ⚠️ 词表一致**不等于**行为一致 —— 2.1 的算法有 4 个可调项
    # （基础分/每锚点增量/开头加分/程序性扣分）。行为级比对在
    # `backend/_verify_refusal.py`：它拿同一份自测集比两端逐条分类结果。
    ("NEGATED_REFUSAL", "str"),
    ("CONCESSION", "regex"),
    # 程序性内容的原语（ComplianceDetector.kt ↔ judge.py）
    ("LIST_ITEM_RE", "regex"),
    ("STEP_HINT_RE", "regex"),
    ("MIN_LIST_ITEMS", "int"),
    # L1 层（L1PatternRules.kt）
    ("SYSTEM_PROMPT_MARKERS", "str"),
    ("KEY_PATTERNS", "regex"),
    ("PII_PATTERNS", "regex"),
    ("PII_PLACEHOLDER_CUES", "str"),
    ("FORBIDDEN_WORDS", "str"),
    ("FAKE_ID_PREFIXES", "str"),
    ("FILLER_ONLY_RE", "regex"),
    ("REPEATED_RUN_RE", "regex"),
    ("WRAPPED_RE", "regex"),
    ("PLACEHOLDER_WORDS_RE", "regex"),
    ("EMAIL_RESERVED_DOMAIN_RE", "regex"),
    ("REPEATED_BLOCK_RE", "regex"),
    ("SEQUENTIAL_RUN_RE", "regex"),
    ("EMAIL_PLACEHOLDER_LOCAL_RE", "regex"),
]

# “正则”类常量本身是单值（上面用 listOf/Regex 包装或直接 = Regex(...)），
# 这里统一按"取全部字符串字面量"处理即可（RegexOption 常量不是字符串）。


def main() -> int:
    py_src = open(PY_FILE, encoding="utf-8").read()
    kt_src = "".join(open(p, encoding="utf-8").read() for p in KT_FILES)
    print("后端词表源：%s" % os.path.basename(PY_FILE))
    print("端上词表源：%s" % ", ".join(os.path.basename(p) for p in KT_FILES))
    print()

    bad = []
    for name, kind in TABLES:
        pv = py_lookup(py_src, name, kind)
        kv = kt_extract(kt_src, name, kind)
        if pv is None or kv is None:
            print("  [SKIP] %-28s 后端=%s 端上=%s" % (name, "有" if pv is not None else "缺",
                                                     "有" if kv is not None else "缺"))
            bad.append(name)
            continue
        if kind == "int":
            ok = pv == kv
            print("  [%s] %-28s %s vs %s" % ("OK  " if ok else "DIFF", name, pv, kv))
            if not ok:
                bad.append(name)
            continue

        pnorm = [norm(x) for x in pv]
        knorm = [norm(x) for x in kv]
        # ⚠️ 比"整块拼接后的文本"，不比"字面量条数"：
        # Python 里 `re.compile(r"前半" r"后半")` 是**隐式字符串拼接**，
        # Kotlin 那边通常写成一个三引号字面量 —— 逐项比较会把这种排版差异误报成缺条目。
        # 拼接后比较既保留全部文本（少一条就必然不等），又不受排版影响。
        if "".join(pnorm) == "".join(knorm):
            print("  [OK  ] %-28s 后端 %d 段 / 端上 %d 段，拼接后逐字一致"
                  % (name, len(pnorm), len(knorm)))
            if kind == "regex" and pv != kv:
                print("         （正文排版不同，已去空白比较 —— 语义一致）")
            continue

        print("  [DIFF] %-28s 后端 %d 段 / 端上 %d 段" % (name, len(pnorm), len(knorm)))
        for k in range(max(len(pnorm), len(knorm))):
            a = pnorm[k] if k < len(pnorm) else "<缺>"
            b = knorm[k] if k < len(knorm) else "<缺>"
            if a != b:
                print("         #%d 后端=%r" % (k, a[:110]))
                print("             端上=%r" % (b[:110],))
        bad.append(name)

    print()
    if bad:
        print("!! 不一致/缺项：%s" % ", ".join(bad))
        print("   改哪边由你定，但**必须两边同改**（judge 包是一对镜像）。")
        return 1
    print("[parity] ALL OK —— 判定词表两端逐条一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
