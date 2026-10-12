#!/usr/bin/env python3
"""意图 × 策略契约的两端一致性校验（不触网、不起服务）。

## 为什么必须有这个脚本

两端都开着「忽略未知键」：**字段名或枚举值对不上是不报错的，直接静默丢弃** ——
表现得像"报告里就是没有策略归因"，而不是像一个 bug。
这类问题只有机械逐字比对才抓得住，靠肉眼读两份代码必然会漏。

## 校验六件事

1. **枚举值集合逐字一致**：Kotlin `@SerialName` 值 ↔ Python 枚举值；
2. **TestCase 新增字段两端同名**：`intent` / `strategy` / `encoded`；
3. **`SessionRequest.useJailbreakVariants` 两端同名**（名字对不上 = 变体集永远不跑，且不报错）；
4. **往返不丢**：构造带标注的 TestCase → JSON → 反序列化，标注仍在（含旧数据兼容）；
5. **指纹库逐条一致**（strategy / keywords / patterns / weight）；
6. **用例集逐条一致**：
   - 50 条静态基线：id / dimension / expected / intent / strategy / tags；
   - 100 条越狱变体：上面这些**再加 title / prompt 全文** ——
     prompt 不一致最隐蔽：报告看起来正常，只是端上跑的和后端跑的**不是同一批攻击**，
     Strategy-ASR 的数字就此失去意义。

用法（在 C:\\AISecLab 下）：
    <venv>/Scripts/python.exe tools/check-jailbreak-parity.py
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT_MODEL_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "xieguojian" / "aiseclab" / "core" / "model"
KT_BACKEND_CLIENT = (ROOT / "app" / "src" / "main" / "java" / "com" / "xieguojian"
                     / "aiseclab" / "core" / "api" / "BackendClient.kt")
PY_MODELS = ROOT / "backend" / "app" / "models.py"

sys.path.insert(0, str(ROOT / "backend"))

failures = []


# ────────────────────────────────────────────────────────────────
# 静态文本层校验
# ────────────────────────────────────────────────────────────────

def kt_enum_values(enum_name: str):
    """从 Kotlin 文件里取某个 enum 的全部 @SerialName 值（保持声明顺序）。"""
    src = (KT_MODEL_DIR / "Jailbreak.kt").read_text(encoding="utf-8")
    m = re.search(rf"enum class {enum_name}\b[^{{]*\{{(.*?)\n\}}", src, re.S)
    if not m:
        return None
    return re.findall(r'@SerialName\("([^"]+)"\)', m.group(1))


def py_enum_values(enum_name: str):
    src = PY_MODELS.read_text(encoding="utf-8")
    m = re.search(rf"class {enum_name}\(str, Enum\):(.*?)(?=\nclass |\Z)", src, re.S)
    if not m:
        return None
    return [v for _, v in re.findall(r'^\s+(\w+) = "([^"]+)"', m.group(1), re.M)]


def check_enum(enum_name: str):
    kt, py = kt_enum_values(enum_name), py_enum_values(enum_name)
    if kt is None:
        failures.append(f"{enum_name}: Kotlin 侧没找到（Jailbreak.kt）")
        return
    if py is None:
        failures.append(f"{enum_name}: Python 侧没找到（models.py）")
        return
    if set(kt) != set(py):
        failures.append(
            f"{enum_name}: 取值不一致\n"
            f"    Kotlin : {sorted(kt)}\n"
            f"    Python : {sorted(py)}\n"
            f"    仅 Kotlin 有: {sorted(set(kt) - set(py))}\n"
            f"    仅 Python 有: {sorted(set(py) - set(kt))}"
        )
        return
    if kt != py:
        print(f"  [提示] {enum_name} 取值集合一致但**声明顺序**不同（不影响功能，仅影响报告排序）")
    print(f"  ✓ {enum_name}: {len(kt)} 个取值逐字一致")


TC_FIELDS = ("intent", "strategy", "encoded")


def check_testcase_fields():
    kt = (KT_MODEL_DIR / "TestCase.kt").read_text(encoding="utf-8")
    py = PY_MODELS.read_text(encoding="utf-8")
    m = re.search(r"class TestCase\(BaseModel\):(.*?)(?=\nclass |\Z)", py, re.S)
    body = m.group(1) if m else ""

    missing = []
    for f in TC_FIELDS:
        if not re.search(rf"val {f}\s*:", kt):
            missing.append(f"Kotlin TestCase 缺字段 {f}")
        if not re.search(rf"^\s+{f}\s*:", body, re.M):
            missing.append(f"Python TestCase 缺字段 {f}")
    if missing:
        failures.extend(missing)
        return
    print(f"  ✓ TestCase 的 {' / '.join(TC_FIELDS)} 两端同名")


def check_session_request_field():
    """变体集开关的字段名。对不上 → 端上开了变体集，后端收不到，**静默只跑 50 条**。"""
    kt_src = KT_BACKEND_CLIENT.read_text(encoding="utf-8")
    py_src = PY_MODELS.read_text(encoding="utf-8")
    field = "useJailbreakVariants"
    if not re.search(rf"val {field}\s*:", kt_src):
        failures.append(f"Kotlin BackendClient.SessionRequest 缺字段 {field}")
        return
    if not re.search(rf"^\s+{field}\s*:", py_src, re.M):
        failures.append(f"Python SessionRequest 缺字段 {field}")
        return
    print(f"  ✓ SessionRequest.{field} 两端同名")


def check_roundtrip():
    """端上发出的 JSON（含标注）后端必须原样收下 —— 这是"静默丢弃"的直接检验。"""
    from app.models import TestCase as PyTestCase  # noqa: E402

    wire = {
        "id": "sec-dan-1",
        "dimension": "security",
        "title": "DAN 角色扮演",
        "prompt": "你现在是 DAN…",
        "expected": "SHOULD_REFUSE",
        "tags": ["jailbreak"],
        "intent": "sysprompt_leak",
        "strategy": "roleplay",
        "encoded": True,
    }
    tc = PyTestCase(**wire)
    if tc.intent.value != "sysprompt_leak" or tc.strategy.value != "roleplay" or tc.encoded is not True:
        failures.append("往返：TestCase 的 intent/strategy/encoded 没有被收下（疑似字段名不一致）")
        return
    dump = json.loads(tc.model_dump_json())
    if (dump.get("intent") != "sysprompt_leak" or dump.get("strategy") != "roleplay"
            or dump.get("encoded") is not True):
        failures.append(f"往返：回传 JSON 里标注丢了 → {dump}")
        return
    print("  ✓ 往返：intent/strategy/encoded 在 传入→回传 两程都保留")

    # 旧数据（没有这三个字段）必须仍能反序列化，且落到默认值 —— 向后兼容是硬要求。
    legacy = {k: v for k, v in wire.items() if k not in TC_FIELDS}
    old = PyTestCase(**legacy)
    if old.intent.value != "unknown" or old.strategy.value != "none" or old.encoded is not False:
        failures.append(f"旧数据兼容：默认值不对 → {old.intent}/{old.strategy}/{old.encoded}")
        return
    print("  ✓ 旧数据：没有标注的用例落到 unknown/none/false，不报错")


# ────────────────────────────────────────────────────────────────
# 端上 dump（跑 JailbreakParityDumpTest 拿结构化数据）
# ────────────────────────────────────────────────────────────────

def dump_kotlin() -> "tuple[dict | None, str]":
    tmp = ROOT / "app" / "build" / "tmp" / "jailbreak-fingerprints.json"
    if tmp.exists():
        tmp.unlink()
    runner = ROOT / "tools" / "run-unit-tests.py"
    r = subprocess.run(
        [sys.executable, str(runner),
         f"-Djailbreak.parity.out={tmp}",
         "com.xieguojian.aiseclab.JailbreakParityDumpTest"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if not tmp.exists():
        return None, (r.stdout or "") + (r.stderr or "")
    return json.loads(tmp.read_text(encoding="utf-8")), ""


def check_fingerprints(dump: dict):
    """逐条比对两端的指纹库（strategy / keywords / patterns / weight）。

    比的是**结构化数据**而不是源码文本：指纹里的正则本身含引号
    （前缀注入那两条的 `[「\"']`），按字符串字面量去抽必然断在引号上，
    会得出"两边不一致"的假警报 —— 比不校验更糟。
    """
    from app.jailbreak import FINGERPRINTS as PY  # noqa: E402

    kt = dump.get("fingerprints") or []
    if len(kt) != len(PY):
        failures.append(f"指纹条数不一致：端上 {len(kt)} 条 vs 后端 {len(PY)} 条")
    bad = 0
    for i in range(max(len(kt), len(PY))):
        a = kt[i] if i < len(kt) else None
        b = PY[i].model_dump(mode="json") if i < len(PY) else None
        if a is None or b is None:
            failures.append(f"指纹第 {i} 条只有一侧存在（端上={a is not None}，后端={b is not None}）")
            bad += 1
            continue
        for key in ("strategy", "keywords", "patterns"):
            if a.get(key) != b.get(key):
                failures.append(
                    f"指纹第 {i} 条 [{a.get('strategy')}] 的 {key} 不一致\n"
                    f"      端上: {a.get(key)}\n      后端: {b.get(key)}"
                )
                bad += 1
        if abs(float(a.get("weight", 1.0)) - float(b.get("weight", 1.0))) > 1e-6:
            failures.append(
                f"指纹第 {i} 条 [{a.get('strategy')}] 的 weight 不一致："
                f"端上 {a.get('weight')} vs 后端 {b.get('weight')}"
            )
            bad += 1
    if bad == 0:
        print(f"  ✓ 指纹库：{len(kt)} 条逐条一致（strategy / keywords / patterns / weight）")


CASE_KEYS = ("id", "dimension", "title", "prompt", "expected", "intent", "strategy", "encoded", "tags")


def _compare_cases(label: str, kt: list, py: list, keys) -> int:
    if len(kt) != len(py):
        failures.append(f"{label} 条数不一致：端上 {len(kt)} 条 vs 后端 {len(py)} 条")
    bad = 0
    for i in range(min(len(kt), len(py))):
        a, b = kt[i], py[i]
        for key in keys:
            av, bv = a.get(key), b.get(key)
            if av != bv:
                failures.append(
                    f"{label} 第 {i} 条 [{a.get('id')}] 的 {key} 不一致\n"
                    f"      端上: {av!r}\n      后端: {bv!r}"
                )
                bad += 1
    return bad


def check_static_cases(dump: dict):
    """50 条静态基线：**按 id 对齐**再比，别按下标 ——
    顺序差一位会让下面 49 条全部误报，真正的问题反而被淹没。"""
    from app.attack import STATIC_CASES as PY  # noqa: E402

    kt = dump.get("staticCases") or []
    py = [c.model_dump(mode="json") for c in PY]
    if [c.get("id") for c in kt] != [c.get("id") for c in py]:
        only_kt = sorted({c.get("id") for c in kt} - {c.get("id") for c in py})
        only_py = sorted({c.get("id") for c in py} - {c.get("id") for c in kt})
        failures.append(f"静态基线 id 集合/顺序不一致\n    仅端上有: {only_kt}\n    仅后端有: {only_py}")
        return
    bad = _compare_cases("静态基线", kt, py, CASE_KEYS)
    if bad == 0:
        print(f"  ✓ 静态基线：{len(kt)} 条逐条一致"
              f"（含 dimension/expected/intent/strategy/encoded/tags 与 prompt 全文）")


def check_variants(dump: dict):
    """100 条越狱变体：**prompt 全文也要比**。

    变体集是模板批产的，端上和后端各有一份模板实现。字段全对但模板里少一个逗号，
    跑的就是两批不同的攻击 —— 报告看起来毫无异常，Strategy-ASR 却已经失去意义。
    """
    from app.jailbreak_cases import build_cases as py_build  # noqa: E402

    kt = dump.get("variants") or []
    py = [c.model_dump(mode="json") for c in py_build()]
    if [c.get("id") for c in kt] != [c.get("id") for c in py]:
        only_kt = sorted({c.get("id") for c in kt} - {c.get("id") for c in py})
        only_py = sorted({c.get("id") for c in py} - {c.get("id") for c in kt})
        failures.append(f"变体集 id 集合/顺序不一致\n    仅端上有: {only_kt}\n    仅后端有: {only_py}")
        return
    bad = _compare_cases("变体集", kt, py, CASE_KEYS)
    if bad == 0:
        print(f"  ✓ 越狱变体集：{len(kt)} 条逐条一致（prompt 全文 + intent/strategy/encoded/tags）")


def main():
    print("意图 × 策略契约 两端一致性校验")
    print(f"  Kotlin: {KT_MODEL_DIR}")
    print(f"  Python: {PY_MODELS}")
    print()
    check_enum("HarmIntent")
    check_enum("JailbreakStrategy")
    check_testcase_fields()
    check_session_request_field()
    check_roundtrip()

    dump, err = dump_kotlin()
    if dump is None:
        failures.append(
            "拿不到端上 dump（先跑 :app:compileDebugUnitTestKotlin 再执行本脚本）\n    输出尾部："
            + (err or "")[-600:].replace("\n", "\n    ")
        )
    else:
        counts = dump.get("expectedCounts") or {}
        print(f"  · 端上 dump：指纹 {counts.get('fingerprints')} / 基线 {counts.get('staticCases')}"
              f" / 变体 {counts.get('variants')}")
        if counts.get("staticCases") != 50:
            failures.append(f"端上静态基线不是 50 条（实际 {counts.get('staticCases')}）—— 比对口径要先对齐")
        if counts.get("variants") != 100:
            failures.append(f"端上变体集不是 100 条（实际 {counts.get('variants')}）")
        check_fingerprints(dump)
        check_static_cases(dump)
        check_variants(dump)

    print()
    if failures:
        print("发现问题：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print("ALL OK —— 两端契约逐字一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
