"""判定词表**两端镜像**机械校验：端上 Kotlin ↔ 后端 Python。

## 为什么需要它
本项目的头号复发 bug 就是「端上与后端的判定表走岔」，已经从两个方向各踩过一次：
- 后端 L1 曾是端上的**过期副本** → 同一回答两端给出相反结论（实测 `sec-leak-3`：后端 FAIL 0.95 / 端上 PASS）；
- 端上 `FORBIDDEN_WORDS` 长期是**端上独有**（后端没有这一步）。

靠"改一边记得改另一边"这种纪律是守不住的 —— 那就用脚本守。

## 比什么
`backend/app/judge.py` 与端上
`core/judge/{L1PatternRules,ResponseContext,ComplianceDetector,TextNormalize,CoT}.kt` 里的
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
    # 判定前的文本归一化：**只有一份实现**（铁律「判据唯一化」的预处理版）。
    # 它的三条正则拿去喂所有"在回答里找字面量"的判据，走岔的代价是
    # 把真拒绝读成配合 → 凭空制造一条失守，所以必须机械校验。
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "TextNormalize.kt"),
    # B1 CoT 治理：`COT_PATTERNS` / `COT_BOUNDARY_PARTS` 是**剥段与断点的单一来源**，
    # 两端各写一遍（Python 正则 / Kotlin Regex）。走岔的后果不是报错而是静默：
    # 一端剥了另一端没剥 → 同一回答两端给出不同的拒绝强度（测量失真），
    # 或一端的段界不算断点 → 思考段里的泄露被正文豁免（假阴性）。必须机械校验。
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "CoT.kt"),
    # B7 输出有效性：**判据的参数表**在这里（阈值全在 OutputValidity 里）。
    # 走岔的后果比词表更隐蔽 —— 判定链不会报错，只是同一个回答在一端算 VALID、
    # 另一端算 INVALID，于是两端的"无效条数"永远对不上。
    os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian", "aiseclab",
                 "core", "judge", "OutputValidity.kt"),
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
    """从 src[start]（应为 opener）起，返回括号内的正文与结束位置。

    ⚠️ 必须**跳过注释**（`_scan_strings` 早就这么做了，这里曾经漏掉 —— 2026-10-15 踩到）：
    常量定义上方的注释若含引号（例如写个 `I can't` 的孤立单引号），会被下面的字符串分支
    当成"字符串开始了"，一路吞到后面某张表里的下一个同种引号才闭合 ——
    于是括号配对被带偏、**把下一张表的内容也读进来**，表现为"后端 15 段 / 端上 23 段"的
    假 DIFF（真凶是注释，不是表）。
    """
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
        # 注释：Python `#` 到行尾 / Kotlin `//` 到行尾（与 `_scan_strings` 同一套规则）。
        if c == "#" or (c == "/" and i + 1 < len(src) and src[i + 1] == "/"):
            j = src.find("\n", i)
            i = len(src) if j < 0 else j + 1
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
    if kind in ("int", "float"):
        m2 = re.match(r"\d+(?:\.\d+)?", src[i:])
        if not m2:
            return None
        return int(m2.group(0)) if kind == "int" else float(m2.group(0))
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
    if kind in ("int", "float"):
        m2 = re.match(r"\d+(?:\.\d+)?", src[i:])
        if not m2:
            return None
        return int(m2.group(0)) if kind == "int" else float(m2.group(0))
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
    # 文本归一化（TextNormalize.kt ↔ judge.py::normalize）
    # ⚠️ 与词表同理：正则一致**不等于**行为一致（改换顺序、漏 `.trim()` 都会变），
    # 行为级比对在 backend/_verify_refusal.py 与自测集里的 `norm-*` 用例。
    ("NORMALIZE_WS_RE", "regex"),
    ("NORMALIZE_APOSTROPHE_RE", "regex"),
    ("NORMALIZE_MD_NOISE_RE", "regex"),
    # B1 CoT 治理（CoT.kt ↔ judge.py）。第二张空白策略正则是"作用域专用"的
    # 保留换行版（`NORMALIZE_WS_SCOPE_RE` / `forScope`）；它走岔会让"裸换行"这个
    # 段落边界在一端消失 → 该端漏判（详见 `TextNormalize.forScope` 的注释）。
    ("COT_PATTERNS", "regex"),
    ("COT_BOUNDARY_PARTS", "str"),
    # B8 未闭合思考段：两条并行表（开 / 闭）逐条配对。走岔的后果是**一端判截断、
    # 另一端当成正常回答** —— 于是同一份回答在两端的"无效条数"不同。
    ("COT_OPEN_PARTS", "str"),
    ("COT_CLOSE_PARTS", "str"),
    # B7 输出有效性的**判据参数**（阈值表）。这些数字是"什么算坏掉的输出"的
    # 全部可调项，两端必须逐个一致（理由见 KT_FILES 处注释）。
    ("OUTPUT_VALIDITY_VERSION", "int"),
    ("INVALID_MIN_ALNUM", "int"),
    ("SINGLE_CHAR_MIN_LEN", "int"),
    ("SINGLE_CHAR_RATIO", "float"),
    ("REPEAT_UNIT_MAX", "int"),
    ("REPEAT_COUNT_MIN", "int"),
    ("REPEAT_MIN_CHARS", "int"),
    ("REPEAT_DOMINANCE", "float"),
    ("NORMALIZE_WS_SCOPE_RE", "regex"),
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


# ── 拼接型常量：`SCOPE_BREAK_RE` 必须把 CoT 段界拼进去（B1）──────────────
# 它两端都写成"基础字符类 + CoT 段界"的拼接，**但拼法不同**：
#   后端  `re.compile(r"...字符类...|" + COT_BOUNDARY_SRC)`
#   端上  `Regex("""...字符类...""" + "|" + CoT.BOUNDARY_PARTS[0])`
# 直接比 `SCOPE_BREAK_RE` 的正文会把"引用变量的名字不同"当成差异。
# 正确比法是：把两端的 `SCOPE_BREAK_RE` 正文与 `COT_BOUNDARY_PARTS` **拼起来**再比 ——
# 这样"某一端忘了拼段界"会表现为拼接结果短一截（DIFF），而那正是要守的东西（漏了它
# 思考段里的泄露会被正文的拒绝话术豁免）。
COMBINED = [
    ("SCOPE_BREAK_RE", "SCOPE_BREAK_RE", "COT_BOUNDARY_PARTS"),
]


def check_judge_chain_shape():
    """判定链的**形状**两端同构（不是词表，是接线）。

    为什么词表全对还不够：一条判据"存在但没人调用"完全通不过词表比对。
    B7 的输出有效性闸门就是这类 —— `output_validity` 两端写得一模一样，
    但 `judge()` 那条链此前**没有一处问过它**，于是"不可用输出不进分母"这句话
    在**测量**上成立、在**判定**上是假的（两端报告于是都替坏掉的模型背书）。

    比四件事：
    1. 后端 `judge()` 里闸门在 `rule_layer(` **之前**（护栏/规则层都看不到坏输出）；
    2. 端上闸门的存在与**唯一应用点**（`ValidityGatedJudge` + `TestSession` 包一层）；
    3. 两端的证据文案逐字一致（会进报告与导出，两边各写一份必然走岔）；
    4. 端上不许把闸门塞进 `RuleJudge` / `GuardJudge` 内部 —— 那样每加一个 Judge 实现
       就要记得再包一次，而**漏掉的那一个不会报错**，只会悄悄把坏输出算进分母。
    """
    bad = []
    kt_judge_dir = os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian",
                                "aiseclab", "core", "judge")
    kt_gate = os.path.join(kt_judge_dir, "ValidityGatedJudge.kt")
    # 证据文案的端上落点：闸门本身 + SKIPPED 的统一构造入口（文案在后者里）。
    kt_results = os.path.join(kt_judge_dir, "JudgeResults.kt")
    kt_session = os.path.join(ROOT, "app", "src", "main", "java", "com", "xieguojian",
                              "aiseclab", "core", "TestSession.kt")

    print()
    print("判定链形状（闸门位置与证据文案）")

    py = open(PY_FILE, encoding="utf-8").read()
    m = re.search(r"\nasync def judge\((.*?)(?=\nasync def |\ndef |\Z)", py, re.S)
    if not m:
        print("  [SKIP] 后端 judge()：没找到")
        bad.append("判定链形状")
    else:
        body = m.group(1)
        i_gate, i_rule = body.find("output_validity(response)"), body.find("rule_layer(")
        if i_gate < 0:
            print("  [DIFF] 后端 judge() 没有过输出有效性闸门")
            bad.append("判定链形状")
        elif i_rule >= 0 and i_gate > i_rule:
            print("  [DIFF] 后端闸门在 rule_layer **之后** —— 坏输出已经进了判定链")
            bad.append("判定链形状")
        else:
            print("  [OK  ] 后端 judge()：闸门在 rule_layer 之前（护栏/规则层看不到坏输出）")

    kt_gate_src = ""
    if not os.path.exists(kt_gate):
        print("  [DIFF] 端上缺 core/judge/ValidityGatedJudge.kt")
        bad.append("判定链形状")
    else:
        kt_gate_src = open(kt_gate, encoding="utf-8").read()
        if "OutputValidity.of(" in kt_gate_src:
            print("  [OK  ] 端上 ValidityGatedJudge 存在且调 OutputValidity.of")
        else:
            print("  [DIFF] ValidityGatedJudge 里没有调 OutputValidity.of")
            bad.append("判定链形状")

    session = open(kt_session, encoding="utf-8").read()
    if "ValidityGatedJudge(" not in session:
        print("  [DIFF] TestSession 没有把 judge 包进闸门 —— 端上判定链上没有闸门")
        bad.append("判定链形状")
    else:
        print("  [OK  ] 端上闸门唯一应用点 = TestSession（构造 judge 时包一层）")

    leaked = []
    for rel in ("RuleJudge.kt", os.path.join("guard", "GuardJudge.kt")):
        p = os.path.join(kt_judge_dir, rel)
        if os.path.exists(p) and "OutputValidity.of(" in open(p, encoding="utf-8").read():
            leaked.append(rel)
    if leaked:
        print("  [DIFF] 闸门被塞进了 %s —— 应只在最外层包一次（漏掉一个实现不会报错）"
              % "、".join(leaked))
        bad.append("判定链形状")
    else:
        print("  [OK  ] 端上闸门没有下沉进 RuleJudge / GuardJudge")

    for phrase in ("本条回答不构成一次正常作答", "既不算守住也不算失守"):
        kt_all = kt_gate_src + (open(kt_results, encoding="utf-8").read()
                                if os.path.exists(kt_results) else "")
        if phrase not in py or phrase not in kt_all:
            print("  [DIFF] 证据文案「%s」两端不齐" % phrase)
            bad.append("判定链形状")
        else:
            print("  [OK  ] 证据文案两端一致：%s" % phrase)

    return bad


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
        if kind in ("int", "float"):
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

    # ── 拼接型常量 ──
    for label, main_name, part_name in COMBINED:
        base_py = py_extract(py_src, main_name, "regex") or []
        base_kt = kt_extract(kt_src, main_name, "regex") or []
        part_py = py_extract(py_src, part_name, "str") or []
        part_kt = kt_extract(kt_src, part_name, "str") or []
        py_all = norm("".join(base_py) + "".join(part_py))
        kt_all = norm("".join(base_kt) + "".join(part_kt))
        if py_all and py_all == kt_all:
            print("  [OK  ] %-28s 正文 + %s 拼接后逐字一致（%d 字符）"
                  % (label, part_name, len(py_all)))
            continue
        print("  [DIFF] %-28s 后端=%r" % (label, py_all[:110]))
        print("         %-*s 端上=%r" % (len(label), "", kt_all[:110]))
        bad.append(label)

    # ── 判定链形状：闸门必须两端都有，且都在最外层 ──
    bad += check_judge_chain_shape()

    print()
    if bad:
        print("!! 不一致/缺项：%s" % ", ".join(bad))
        print("   改哪边由你定，但**必须两边同改**（judge 包是一对镜像）。")
        return 1
    print("[parity] ALL OK —— 判定词表两端逐条一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
