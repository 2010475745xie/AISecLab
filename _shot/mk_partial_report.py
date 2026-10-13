# -*- coding: utf-8 -*-
"""构造一份**带逐轮记录（含 compliance）**的报告，推到设备历史目录，用来验证
报告页「半妥协轮次」在有数据时的渲染。

为什么需要构造：端上要产出 attackRounds 必须开「递归对抗」，而它要求
`useRecursive && attackerChat != null`（TestRunner.kt:42）—— 也就是**必须配好辅助模型**。
本机当前辅助模型三项全空，真机跑不出逐轮记录。单测已覆盖算法，这里补的是
**真机渲染 + 真机序列化往返**这一段（铁律：编译与单测全绿挡不住取数挂错分支）。

⚠️ 这是**构造数据**，不是一次真实测试的结果，验证完立即删除（脚本会打印删除命令）。
"""
import json
import sys

sys.path.insert(0, "_shot")
from adb import adb  # noqa: E402

PKG = "com.xieguojian.aiseclab"
NEW_TS = 1791879999999
DST = f"files/history/report_{NEW_TS}.json"

def pull_all():
    """从设备拉全部历史报告 —— 现场取模板，不依赖任何本地缓存文件。"""
    r = adb("shell", "run-as", PKG, "ls", "files/history")
    names = [n.strip() for n in (r.stdout or "").splitlines() if n.startswith("report_")]
    out = []
    for n in names:
        rr = adb("exec-out", "run-as", PKG, "cat", "files/history/" + n, text=False)
        try:
            out.append((n, json.loads(rr.stdout.decode("utf-8"))))
        except Exception:
            pass
    return out


def pick_template():
    """挑一份**含 ≥4 条安全用例**的真实报告当模板（半妥协行只对安全用例的逐轮记录取数）。"""
    for name, d in pull_all():
        n_sec = sum(1 for x in d["results"] if x["testCase"]["dimension"] == "security")
        if n_sec >= 4:
            print("模板：%s（%d 条用例，其中安全 %d 条）" % (name, len(d["results"]), n_sec))
            return d
    raise SystemExit("设备上没有安全用例 >=4 条的报告 —— 先跑一轮安全维度测试再来")


src = pick_template()

# 给前 4 条安全用例编逐轮记录：轮次 = 3 / 2 / 4 / 1，compliance 覆盖四种取值。
PLAN = [
    [("refused", True), ("partial", True), ("complied", False)],
    [("refused", True), ("partial", True)],
    [("refused", True), ("refused", True), ("partial", True), ("partial", True)],
    [("complied", False)],
]

sec_idx = [i for i, r in enumerate(src["results"]) if r["testCase"]["dimension"] == "security"]
assert len(sec_idx) >= 4, f"模板里安全用例不足 4 条：{len(sec_idx)}"

for n, (idx, plan) in enumerate(zip(sec_idx[:4], PLAN), start=1):
    rounds = []
    for rnd, (comp, refused) in enumerate(plan, start=1):
        rounds.append({
            "round": rnd,
            "prompt": f"[构造数据] 第 {rnd} 轮变异提示",
            "response": f"[构造数据] 第 {rnd} 轮回答（compliance={comp}）",
            "refused": refused,
            "compliance": comp,
        })
    src["results"][idx]["attackRounds"] = rounds

src["timestamp"] = NEW_TS
src["modelProfile"]["apiKey"] = ""
src["summary"] = "【构造数据 · 仅用于验证「半妥协轮次」渲染，验证后删除】" + src["summary"]

payload = json.dumps(src, ensure_ascii=False).encode("utf-8")

total = {c: 0 for c in ("refused", "partial", "complied", "unknown")}
for idx, plan in zip(sec_idx[:4], PLAN):
    for comp, _ in plan:
        total[comp] += 1

print("轮次总计", sum(total.values()), total)
print("预期这一行应该显示：半妥协 %d · 拒绝 %d · 配合 %d"
      % (total["partial"], total["refused"], total["complied"]))

import subprocess  # noqa: E402

# ⚠️ 重定向必须**整体**作为 `sh -c` 的脚本参数传进去（外面套一层引号）：
# 否则 `>` 会被设备的外层 shell 处理，由 shell uid 去创建 app 私有目录里的文件 →
# 报 "can't create files/history/...: No such file or directory"（其实是权限问题）。
r = subprocess.run(
    [r"D:\SDK\platform-tools\adb.exe", "-s", "ADNQUT6227001894",
     "shell", f"run-as {PKG} sh -c 'cat > {DST}'"],
    input=payload, capture_output=True,
)
print("push:", r.returncode, (r.stdout or b"").decode("utf-8", "replace")[:200],
      (r.stderr or b"").decode("utf-8", "replace")[:200])

chk = adb("shell", "run-as", PKG, "ls", "-l", DST)
print("落盘:", (chk.stdout or chk.stderr or "").strip())
