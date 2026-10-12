#!/usr/bin/env python3
"""意图 × 策略契约的两端一致性校验（不触网、不起服务）。

## 为什么必须有这个脚本

两端都开着「忽略未知键」：**字段名或枚举值对不上是不报错的，直接静默丢弃** ——
表现得像"报告里就是没有策略归因"，而不是像一个 bug。
这类问题只有机械逐字比对才抓得住，靠肉眼读两份代码必然会漏。

## 校验三件事

1. **枚举值集合逐字一致**：Kotlin `@SerialName` 值 ↔ Python 枚举值；
2. **TestCase 新增字段两端同名**：`intent` / `strategy`；
3. **往返不丢**：构造带标注的 TestCase → JSON → 反序列化，标注仍在。

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
PY_MODELS = ROOT / "backend" / "app" / "models.py"

sys.path.insert(0, str(ROOT / "backend"))

failures = []


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


def check_testcase_fields():
    kt = (KT_MODEL_DIR / "TestCase.kt").read_text(encoding="utf-8")
    py = PY_MODELS.read_text(encoding="utf-8")
    m = re.search(r"class TestCase\(BaseModel\):(.*?)(?=\nclass |\Z)", py, re.S)
    body = m.group(1) if m else ""

    missing = []
    for f in ("intent", "strategy"):
        if not re.search(rf"val {f}\s*:", kt):
            missing.append(f"Kotlin TestCase 缺字段 {f}")
        if not re.search(rf"^\s+{f}\s*:", body, re.M):
            missing.append(f"Python TestCase 缺字段 {f}")
    if missing:
        failures.extend(missing)
        return
    print("  ✓ TestCase.intent / TestCase.strategy 两端同名")


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
    }
    tc = PyTestCase(**wire)
    if tc.intent.value != "sysprompt_leak" or tc.strategy.value != "roleplay":
        failures.append("往返：TestCase 的 intent/strategy 没有被收下（疑似字段名不一致）")
        return
    dump = json.loads(tc.model_dump_json())
    if dump.get("intent") != "sysprompt_leak" or dump.get("strategy") != "roleplay":
        failures.append(f"往返：回传 JSON 里标注丢了 → {dump}")
        return
    print("  ✓ 往返：intent/strategy 在 传入→回传 两程都保留")

    # 旧数据（没有这两个字段）必须仍能反序列化，且落到默认值 —— 向后兼容是硬要求。
    legacy = {k: v for k, v in wire.items() if k not in ("intent", "strategy")}
    old = PyTestCase(**legacy)
    if old.intent.value != "unknown" or old.strategy.value != "none":
        failures.append(f"旧数据兼容：默认值不对 → {old.intent}/{old.strategy}")
        return
    print("  ✓ 旧数据：没有标注的用例落到 unknown/none，不报错")


def dump_kotlin_fingerprints():
    """让端上把指纹库写成 JSON（跑 JailbreakParityDumpTest），返回 (list, err)。"""
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
    return json.loads(tmp.read_text(encoding="utf-8")), None


def check_fingerprints():
    """逐条比对两端的指纹库（strategy / keywords / patterns / weight）。

    比的是**结构化数据**而不是源码文本：指纹里的正则本身含引号
    （前缀注入那两条的 `[「\"']`），按字符串字面量去抽必然断在引号上，
    会得出"两边不一致"的假警报 —— 比不校验更糟。
    """
    kt, err = dump_kotlin_fingerprints()
    if kt is None:
        failures.append(
            "拿不到端上指纹库 dump（先跑 "
            ":app:compileDebugUnitTestKotlin 再执行本脚本）\n    输出尾部："
            + (err or "")[-600:].replace("\n", "\n    ")
        )
        return

    from app.jailbreak import FINGERPRINTS as PY  # noqa: E402

    if len(kt) != len(PY):
        failures.append(f"指纹条数不一致：端上 {len(kt)} 条 vs 后端 {len(PY)} 条")
    bad = 0
    for i in range(max(len(kt), len(PY))):
        a = kt[i] if i < len(kt) else None
        b = PY[i].model_dump(mode="json") if i < len(PY) else None
        if a is None or b is None:
            failures.append(f"第 {i} 条只有一侧存在（端上={a is not None}，后端={b is not None}）")
            bad += 1
            continue
        for key in ("strategy", "keywords", "patterns"):
            if a.get(key) != b.get(key):
                failures.append(
                    f"第 {i} 条 [{a.get('strategy')}] 的 {key} 不一致\n"
                    f"      端上: {a.get(key)}\n      后端: {b.get(key)}"
                )
                bad += 1
        if abs(float(a.get("weight", 1.0)) - float(b.get("weight", 1.0))) > 1e-6:
            failures.append(
                f"第 {i} 条 [{a.get('strategy')}] 的 weight 不一致："
                f"端上 {a.get('weight')} vs 后端 {b.get('weight')}"
            )
            bad += 1
    if bad == 0:
        print(f"  ✓ 指纹库：{len(kt)} 条逐条一致（strategy / keywords / patterns / weight）")


def main():
    print("意图 × 策略契约 两端一致性校验")
    print(f"  Kotlin: {KT_MODEL_DIR}")
    print(f"  Python: {PY_MODELS}")
    print()
    check_enum("HarmIntent")
    check_enum("JailbreakStrategy")
    check_testcase_fields()
    check_roundtrip()
    check_fingerprints()
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
