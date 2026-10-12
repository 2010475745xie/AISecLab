"""冻结包字节校验：确认 dist 里的 exe 真的包含了当前源码（逐字节比 code object）。

为什么不用字符串探针：函数名/参数名在 `co_names`/`co_varnames` 里，不在 `co_consts`，
只扫常量会误报"缺失"（`_record_usage`/`guard_judge` 就这么被误判过）。

已知例外：空的 `app/__init__.py` 在包里只差 `co_flags` 一位（源 0x1000000 / 包 0）——
PyInstaller 对空包的规范化，不是代码差异，按 WARN 处理。

用法：<venv>\\Scripts\\python.exe C:\\AISecLab\\backend\\_verify_frozen.py
"""
from __future__ import annotations

import os
import sys
import types

BACKEND = r"C:\AISecLab\backend"
EXE = os.path.join(BACKEND, "dist", "aiseclab-backend", "aiseclab-backend.exe")
TMP_PYZ = r"C:\AISecLab\_shot\_frozen.pyz"

from PyInstaller.archive.readers import CArchiveReader, ZlibArchiveReader  # noqa: E402

FLAGS_IDX = 6   # canon() 元组里 co_flags 的位置


def canon_const(c):
    if isinstance(c, types.CodeType):
        return canon(c)
    if isinstance(c, tuple):
        return tuple(canon_const(x) for x in c)
    return c


def canon(co: types.CodeType):
    """归一化（丢掉 co_filename / 行号表），只比"逻辑内容"。"""
    return (
        co.co_code,
        co.co_names,
        co.co_varnames,
        co.co_argcount,
        co.co_posonlyargcount,
        co.co_kwonlyargcount,
        co.co_flags,
        co.co_stacksize,
        tuple(canon_const(c) for c in co.co_consts),
    )


def load_frozen(zar, mod: str) -> types.CodeType:
    """ZlibArchiveReader.extract 直接返回 code object（不是 bytes）。"""
    return zar.extract(mod)


def main() -> int:
    if not os.path.exists(EXE):
        print("[X] exe 不存在：", EXE)
        return 1

    car = CArchiveReader(EXE)
    pyz_name = next((n for n in car.toc if str(n).lower().endswith(".pyz")), None)
    print("PYZ 条目：", pyz_name)
    if pyz_name is None:
        print("[X] 找不到 PYZ 条目；toc 前 20 项：", list(car.toc)[:20])
        return 1

    with open(TMP_PYZ, "wb") as f:
        f.write(car.extract(pyz_name))

    zar = ZlibArchiveReader(TMP_PYZ)
    names = sorted(n for n in zar.toc if n == "app" or str(n).startswith("app."))
    print("包内 app.* 模块（%d）：%s" % (len(names), names))

    bad = []
    warns = []
    for mod in names:
        if mod == "app":
            src_path = os.path.join(BACKEND, "app", "__init__.py")
        else:
            src_path = os.path.join(BACKEND, mod.replace(".", os.sep) + ".py")
        if not os.path.exists(src_path):
            print("  [??] 源码不存在：", src_path)
            continue

        src = open(src_path, encoding="utf-8").read()
        want = canon(compile(src, "<src>", "exec"))
        got = canon(load_frozen(zar, mod))
        diffs = [(i, x, y) for i, (x, y) in enumerate(zip(want, got)) if x != y]

        if not diffs:
            print("  [OK  ] %s" % mod)
        elif src == "" and all(i == FLAGS_IDX for i, _, _ in diffs):
            print("  [WARN] %s：空 __init__.py 仅 co_flags 不同（%r vs %r）= 打包规范化"
                  % (mod, diffs[0][1], diffs[0][2]))
            warns.append(mod)
        else:
            print("  [BAD ] %s  src=%s srclen=%d" % (mod, src_path, len(src)))
            for i, x, y in diffs:
                print("         DIFF[%d] src=%r frozen=%r" % (i, x, y))
            bad.append(mod)

    # 额外：关键新符号必须在包里。
    # ⚠️ 必须**递归**收集嵌套 code object —— 只看模块顶层的 co_names/co_varnames/co_consts
    # 会漏掉 class body 与函数体里的名字（`stabilityMeasured` 是 TestResult 的类属性、
    # 也是 build_report 里的字符串字面量，两层都在嵌套 code 里，顶层扫不到 → 误报 BAD）。
    def _collect(co: types.CodeType, names_acc: list, consts_acc: list) -> None:
        names_acc.extend(co.co_names)
        names_acc.extend(co.co_varnames)
        for c in co.co_consts:
            if isinstance(c, types.CodeType):
                _collect(c, names_acc, consts_acc)
            else:
                consts_acc.append(c)

    frozen = {}
    for m in names:
        nm, cs = [], []
        _collect(load_frozen(zar, m), nm, cs)
        frozen[m] = repr([nm, cs])

    probes = {
        "app.judge": ["is_placeholder", "is_being_output", "STRONG_REFUSAL",
                      "SYSTEM_PROMPT_MARKERS", "_MENTION_LIKE_PROBLEMS",
                      # 2026-10-12 新增：违禁词表两端对齐（含一条只可能来自新表的中文短语，
                      # 用来证明「清掉 12 个裸词后剩下的短语级表」确实进了包）
                      "FORBIDDEN_WORDS", "诈骗话术"],
        "app.guard": ["guard_judge"],
        # 2026-10-12 新增：多数裁决抽成纯函数（并列可复现 + SKIPPED 不冒充结论）
        "app.engine": ["pick_majority", "_VERDICT_TIE_ORDER"],
        # 2026-10-12 新增：report_gen 要判 stabilityMeasured 才报"不稳定"
        "app.report_gen": ["build_report", "stabilityMeasured"],
        "app.models": ["stabilityMeasured"],
        "app.llm_client": ["resolve_proxy"],
    }
    print("\n-- 关键符号探针 --")
    for mod, syms in probes.items():
        blob = frozen.get(mod, "")
        for s in syms:
            hit = s in blob
            print("  [%s] %s :: %s" % ("OK  " if hit else "BAD ", mod, s))
            if not hit:
                bad.append("%s::%s" % (mod, s))

    print()
    if bad:
        print("!! 不一致项：", bad)
        return 1
    print("[verify-frozen] ALL OK" + ("（WARN: %s）" % ",".join(warns) if warns else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
