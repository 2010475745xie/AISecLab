# -*- coding: utf-8 -*-
"""从真实报告派生一份「带 attackRounds」的夹具，推到设备上验证攻击演进时间轴。

为什么要夹具：真机上那 8 份报告都是**后端**跑的（N=3 采样 + 三角色），
`recursive=False` → 每例只有 1 步 → 报告页的「攻击演进」区块（要求 ≥2 步）
**没有样本可渲染**，等于这条渲染路径从没验过。
端上本机跑走 `MutationEngine` 静态变异（≥2 步），但要真跑一轮得烧用户 API 额度，
所以按既有做法（见 make_fixture.py）从真实报告派生夹具。

夹具必须自洽：这里只**新增** attackRounds（纯附加字段），
不动 verdict / 分数 / 分区，所以 dimensions/overallScore 不需要重算。
"""
import json
import os
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REAL = os.path.join(ROOT, "_shot", "report_1791801330902.json")
OUT = os.path.join(ROOT, "_shot", "fixture_attack_rounds.json")
ADB = r"D:\SDK\platform-tools\adb.exe"
PKG = "com.xieguojian.aiseclab"

# 比最新真实报告（1791801330902 → 18:35）晚 → 历史列表排最前，好点开
TIMESTAMP = 1791804990902
DEVICE_NAME = "report_%d.json" % TIMESTAMP

# 三条样本，覆盖时间轴的三种形态：
#  - 只在末尾破防（破防后即停 → 红格必定在末尾）
#  - 全程未破防（跑满轮次，全绿）
#  - 2 步就破防（短链）
SHAPES = [
    # (用例 id, 每步 refused)
    ("sec-inj-1", [True, True, True, False]),
    ("con-contra-1", [True, True, True]),
    ("fact-known-1", [True, False]),
]

real = json.load(open(REAL, encoding="utf-8"))
fix = json.loads(json.dumps(real))  # 深拷贝

by_id = {r["testCase"]["id"]: r for r in fix["results"]}
for tid, pattern in SHAPES:
    assert tid in by_id, "报告里没有用例 %s" % tid
    rounds = []
    for i, refused in enumerate(pattern, start=1):
        rounds.append({
            "round": i,
            "prompt": "[夹具] 第 %d 步攻击 prompt：%s（变体 %d）" % (i, by_id[tid]["testCase"]["title"], i),
            "response": ("[夹具] 该步模型给出拒绝性回答，未破防。" if refused
                         else "[夹具] 该步模型未拒绝，已破防 —— 攻击到此停止。"),
            "refused": refused,
        })
    by_id[tid]["attackRounds"] = rounds
    by_id[tid]["attackTrail"] = ["[夹具] 第 %d 步（%s）" % (r["round"], "未破防" if r["refused"] else "破防")
                                 for r in rounds]

fix["timestamp"] = TIMESTAMP
fix["modelProfile"]["name"] = "fixture"

json.dump(fix, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# ── 自检：只许新增 attackRounds/attackTrail，其余字段一个都不许动 ──
chk = json.load(open(OUT, encoding="utf-8"))
assert chk["overallScore"] == real["overallScore"]
assert chk["riskLevel"] == real["riskLevel"]
assert chk["summary"] == real["summary"]
assert chk["dimensions"] == real["dimensions"]
for a, b in zip(real["results"], chk["results"]):
    assert a["judgeResult"] == b["judgeResult"], "judgeResult 被改动了：%s" % a["testCase"]["id"]
assert sum(1 for r in chk["results"] if len(r.get("attackRounds") or []) >= 2) == len(SHAPES)
print("夹具 OK", OUT, os.path.getsize(OUT), "bytes")
for tid, pattern in SHAPES:
    print("  %-14s %d 步  refused=%s" % (tid, len(pattern), pattern))

# ── 推到设备 ──
subprocess.run([ADB, "push", OUT, "/data/local/tmp/fix.json"], check=True, capture_output=True)
cmd = ('cat /data/local/tmp/fix.json | run-as %s sh -c "cat > files/history/%s"' % (PKG, DEVICE_NAME))
r = subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=120)
print("push rc=", r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()[:300])
r = subprocess.run([ADB, "shell", "run-as", PKG, "ls", "-l", "files/history"],
                   capture_output=True, text=True, timeout=60)
print(r.stdout.strip())
