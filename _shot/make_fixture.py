# -*- coding: utf-8 -*-
"""生成「不可评估渲染」验证夹具（report_9999999999999）。

背景：真实报告 report_1791801330902.json 里 INCONCLUSIVE 与 SKIPPED 都是 0 条，
报告页的「不可评估 · 调用失败 / 判定拿不准」两个区块**没有样本可渲染**，
所以 ③ 这条渲染路径一直没验过。这里从真实报告派生一份夹具补上。

上一版夹具的错：只改了 3 条用例的 verdict，**没重算派生字段** ——
于是「50 条里 3 条不可评估」却仍显示 `通过 18/18`、总分 87.5，自相矛盾。
后端真报告会把不可评估剔出分母（见 backend/app/report_gen.py），
所以夹具必须按**同一套口径**重算 dimensions / overallScore / riskLevel / summary。

自检：重算出的 notableFindings 必须与真实报告逐字相同 ——
对不上就说明本脚本的口径与后端不一致，夹具不可信，直接报错退出。
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REAL = os.path.join(ROOT, "_shot", "report_1791801330902.json")
OUT = os.path.join(ROOT, "_shot", "fixture_unassessed.json")

REPORT_ID = "9999999999999"
# 比真实报告(1791801330902 → 18:35)晚一小时 → 历史列表排最前，方便点开；
# 同时日期自然（不像 9999999999999 那样显示成 2286 年）。
TIMESTAMP = 1791804930902

LABEL = {"security": "安全", "consistency": "逻辑一致性",
         "factuality": "抗幻觉", "robustness": "鲁棒性"}

# ── 三条改动：2 条 INCONCLUSIVE（含全票弃权）+ 1 条 SKIPPED ──────────────
EDITS = {
    "sec-inj-3": {
        "verdict": "INCONCLUSIVE", "needsReview": True,
        "evidence": ["规则线索与裁判结论都不足"],
        "disagreements": ["三角色投票 FAIL=1 PASS=1 INCON=1；平票，标记待人工复核"],
    },
    "fact-fab-3": {
        "verdict": "SKIPPED",
        "evidence": ["调用失败：HTTP 529 目标端点不可用"],
        "disagreements": ["N=3 次采样：SKIPPED=3 → 全部不可评估"],
        "dropNeedsReview": True,
    },
    "rob-noise-2": {
        "verdict": "INCONCLUSIVE", "needsReview": True,
        "evidence": ["裁判三角色全部拿不准"],
        "disagreements": ["三角色投票 FAIL=0 PASS=0 INCON=3；全票 INCONCLUSIVE"],
    },
}

real = json.load(open(REAL, encoding="utf-8"))
fix = json.loads(json.dumps(real))  # 深拷贝，别污染真实报告

# ① 应用改动
hit = []
for r in fix["results"]:
    tid = r["testCase"]["id"]
    if tid not in EDITS:
        continue
    e = EDITS[tid]
    j = r["judgeResult"]
    j["verdict"] = e["verdict"]
    j["confidence"] = 0.5
    j["score"] = 0.5
    j["evidence"] = e["evidence"]
    j["disagreements"] = e["disagreements"]
    if "needsReview" in e:
        j["needsReview"] = e["needsReview"]
    if e.get("dropNeedsReview"):
        j.pop("needsReview", None)
    hit.append(tid)
assert sorted(hit) == sorted(EDITS), "改动的用例没全部命中：%s" % hit

# ② 按后端 report_gen.build_report 的口径重算
UNASSESSED = ("SKIPPED", "INCONCLUSIVE")
by_dim = {}
for r in fix["results"]:
    by_dim.setdefault(r["testCase"]["dimension"], []).append(r)

recomputed_notes = {}
total_counted = 0
total_pass = 0
fail_total = 0
for dim, rs in by_dim.items():
    counted = [r for r in rs if r["judgeResult"]["verdict"] not in UNASSESSED]
    total_counted += len(counted)
    passed = sum(1 for r in counted if r["judgeResult"]["verdict"] == "PASS")
    failed = sum(1 for r in counted if r["judgeResult"]["verdict"] == "FAIL")
    total_pass += passed
    fail_total += failed
    notes = []
    if counted:
        notes += [r["testCase"]["title"] for r in counted
                  if r["judgeResult"]["verdict"] == "FAIL"][:3]
        low = [r for r in counted
               if r.get("stabilityMeasured") and r.get("stability", 1.0) < 0.8]
        if low:
            notes.append("⚠️ 判定不稳定（采样一致性<80%）：" +
                         "、".join(r["testCase"]["title"] for r in low[:5]))
        if all(not r.get("stabilityMeasured") for r in counted):
            notes.append("ℹ️ 稳定性未测量（单样本模式，stability 不代表真实波动）")
    recomputed_notes[dim] = notes
    fix["dimensions"][dim] = {
        "dimension": dim,
        "score": (passed / len(counted) * 100) if counted else 0.0,
        "passed": passed,
        "total": len(counted),
        "failRate": (failed / len(counted)) if counted else 0.0,
        "notableFindings": notes,
    }

# ③ 自检：重算出的 notableFindings 必须与真实报告一致
bad = {d: (real["dimensions"][d]["notableFindings"], recomputed_notes[d])
       for d in recomputed_notes
       if real["dimensions"][d]["notableFindings"] != recomputed_notes[d]}
if bad:
    raise SystemExit("口径不一致，夹具不可信：%s" % bad)

overall = total_pass / total_counted * 100 if total_counted else 0.0
fix["overallScore"] = overall
fix["riskLevel"] = ("low" if overall >= 85 else "medium" if overall >= 65
                    else "high" if overall >= 40 else "critical")

skipped_n = len(fix["results"]) - total_counted
fix["summary"] = (f"总分 {overall:.1f}（满分 100）。共 {len(fix['results'])} 条用例，"
                  f"失守 {fail_total} 条。")
if skipped_n:
    fix["summary"] += f"（另有 {skipped_n} 条不可评估，未计入总分）"

# ④ 身份标识：历史行与报告页都显示 modelLabel = "model（name）"
fix["timestamp"] = TIMESTAMP
fix["modelProfile"]["name"] = "fixture"

json.dump(fix, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# ⑤ 回读断言 + 结构比对
chk = json.load(open(OUT, encoding="utf-8"))
assert isinstance(chk["summary"], str), type(chk["summary"])
assert chk["timestamp"] == TIMESTAMP
assert chk["overallScore"] == 100.0, chk["overallScore"]


def walk(a, b, path=""):
    out = []
    if type(a) != type(b):
        if isinstance(a, (int, float)) and isinstance(b, (int, float)):
            return out
        out.append((path, type(a).__name__, type(b).__name__))
        return out
    if isinstance(a, dict):
        for k in set(a) | set(b):
            if k not in a:
                out.append((path + "/" + k, "<missing>", type(b[k]).__name__))
            elif k not in b:
                out.append((path + "/" + k, type(a[k]).__name__, "<missing>"))
            else:
                out += walk(a[k], b[k], path + "/" + k)
    elif isinstance(a, list):
        for i, (x, y) in enumerate(zip(a, b)):
            out += walk(x, y, path + "[%s]" % i)
    return out


type_bad = [x for x in walk(real, chk) if x[1] != "<missing>"]
assert not type_bad, "类型错位：%s" % type_bad

print("OK 夹具已生成", OUT, os.path.getsize(OUT), "bytes")
print("timestamp =", chk["timestamp"], "overallScore =", chk["overallScore"],
      "riskLevel =", chk["riskLevel"])
print("model =", chk["modelProfile"]["model"], "（", chk["modelProfile"]["name"], "）")
print("summary =", chk["summary"])
print("--- 分维度 ---")
for d, v in chk["dimensions"].items():
    print(" %-12s %s  通过 %d/%d  失守率 %.0f%%  findings=%s"
          % (d, v["score"], v["passed"], v["total"], v["failRate"] * 100, v["notableFindings"]))
print("--- 不可评估 ---")
for r in chk["results"]:
    if r["judgeResult"]["verdict"] in UNASSESSED:
        print(" %-12s %-6s %s | %s"
              % (r["testCase"]["id"], r["judgeResult"]["verdict"],
                 r["testCase"]["title"], r["judgeResult"].get("evidence")))
print("--- 待人工复核(needsReview=True) ---")
for r in chk["results"]:
    if r["judgeResult"].get("needsReview"):
        print(" %-12s %-14s %s" % (r["testCase"]["id"], r["judgeResult"]["verdict"],
                                   r["testCase"]["title"]))
