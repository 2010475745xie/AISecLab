#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""预判一份历史报告在报告页**应该**显示成什么样 —— 先算期望值，再去真机核。

为什么先算：真机上看到哪一档、哪句话，必须能立刻判断"是渲染对了还是碰巧对了"。
没有期望值，看到的任何结果都能自我解释（这正是本项目一直在防的"事后合理化"）。

用法：
    python _shot/_probe/analyze-report.py _shot/_probe/last-report.json
"""

import json
import os
import sys
from collections import Counter

PROJECT = r"C:\AISecLab"


def expected_grade(levels, measured):
    if levels.get("LLM"):
        return "NON_DETERMINISTIC"
    if measured:
        return "SAMPLED"
    return "DETERMINISTIC"


def table(paths):
    print(f"{'文件':<34} {'条数':>5} {'层级':<28} {'采样':>4} {'多轮':>4} {'等级':<12} {'期望复现性':<18} externalCheck")
    print("-" * 140)
    for p in paths:
        try:
            r = json.load(open(p, encoding="utf-8"))
        except Exception as e:
            print(f"{p:<34} 读取失败：{e}")
            continue
        res = r.get("results", [])
        levels = Counter(x.get("judgeResult", {}).get("level", "?") for x in res)
        verdicts = Counter(x.get("judgeResult", {}).get("verdict", "?") for x in res)
        measured = sum(1 for x in res if x.get("stabilityMeasured"))
        multi = sum(1 for x in res if x.get("attackRounds"))
        name = p.replace("\\", "/").split("/")[-1]
        lv = ",".join(f"{k}:{v}" for k, v in sorted(levels.items()))
        print(f"{name:<34} {len(res):>5} {lv:<28} {measured:>4} {multi:>4} "
              f"{str(r.get('riskLevel')):<12} {expected_grade(levels, measured):<18} "
              f"{'有' if 'externalCheck' in r else '无'}")
        print(f"{'':<34} 判定：{dict(verdicts)}")


def main(path):
    if path in ("--table", "table"):
        import glob
        d = os.path.join(PROJECT, "_shot", "_probe", "reports")
        table(sorted(glob.glob(os.path.join(d, "*.json"))))
        return
    r = json.load(open(path, encoding="utf-8"))
    results = r.get("results", [])

    levels = Counter(x.get("judgeResult", {}).get("level", "?") for x in results)
    verdicts = Counter(x.get("judgeResult", {}).get("verdict", "?") for x in results)
    measured = [x for x in results if x.get("stabilityMeasured")]
    has_external = "externalCheck" in r

    print(f"文件          : {path}")
    print(f"riskLevel     : {r.get('riskLevel')}   overallScore={r.get('overallScore')}")
    print(f"results 条数  : {len(results)}")
    print(f"判定层级分布  : {dict(levels)}")
    print(f"判定分布      : {dict(verdicts)}")
    print(f"多次采样条数  : {len(measured)}")
    print(f"用过多轮(attackRounds 非空)条数: {sum(1 for x in results if x.get('attackRounds'))}")
    print(f"tokenUsage    : {r.get('tokenUsage')}")
    print()
    print("── 外部对照（B4）期望显示 ──")
    print(f"报告里有 externalCheck 字段吗 : {has_external}")
    print("  期望：False → 报告页必须显示「未接入」，**不能**出现一致率或 0%")
    print()
    print("── 复现性（B5）期望档 ──")
    if not results:
        print("  期望：results 为空 → 「本轮没有回答记录，无法评估复现性。」")
    else:
        g = expected_grade(levels, len(measured))
        why = ("有 LLM 裁判参与" if g == "NON_DETERMINISTIC"
               else "判定是规则层但目标侧多次采样" if g == "SAMPLED"
               else "全规则层 + 单样本")
        print(f"  期望：{g} —— {why}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "_shot/_probe/last-report.json")
