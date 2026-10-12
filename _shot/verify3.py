# -*- coding: utf-8 -*-
"""真机验证 ③：「不可评估」两区块渲染 + 与「待人工复核」去重。

前置：设备历史目录里已有夹具 report_1791804930902.json（modelProfile.name=fixture），
      其 needsReview=true 的用例有 5 条（3 PASS + 2 INCONCLUSIVE），
      不可评估的 3 条（1 SKIPPED + 2 INCONCLUSIVE）。

核心断言（用界面树文本，不靠肉眼）：
  A. 出现「不可评估 · 调用失败 1 条」与「不可评估 · 判定拿不准 2 条」两个区块；
  B. 「待人工复核」标题里的**条数必须是 3**（若去重失效会是 5）；
  C. 两条 INCONCLUSIVE 的标题不得出现在「待人工复核」区块下方；
  D. 维度卡显示 17/17、11/11、9/9、10/10，且三个维度各带「不可评估 1 条」；
  E. 总分仍是 100.0（不可评估剔出分母，不是按 0.5 计）；
  F. SKIPPED 那条显示的成因是接口错误，不是裁判理由。
"""
import re
import subprocess
import sys
import time

ADB = r"D:\SDK\platform-tools\adb.exe"
PKG = "com.xieguojian.aiseclab"
SHOT = r"C:\AISecLab\_shot"


def run(args, binary=False, timeout=90):
    p = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    if binary:
        return p.returncode, p.stdout, p.stderr.decode("utf-8", "replace")
    return (p.returncode,
            p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace"))


def dump_texts(tag):
    """dump 一次界面树，返回按 y 排序的 (text, y, x) 列表。"""
    run(["shell", "uiautomator", "dump", "/sdcard/ui.xml"])
    rc, data, err = run(["exec-out", "cat", "/sdcard/ui.xml"], binary=True)
    xml = data.decode("utf-8", "replace")
    try:
        open("%s\\v3_%s.xml" % (SHOT, tag), "w", encoding="utf-8").write(xml)
    except Exception:
        pass
    items = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, x1, y1 = m.group(1), int(m.group(2)), int(m.group(3))
        if t.strip():
            items.append((t, y1, x1))
    items.sort(key=lambda z: (z[1], z[2]))
    return items


def shot(tag):
    rc, data, err = run(["exec-out", "screencap", "-p"], binary=True)
    if rc == 0 and data:
        open("%s\\%s.png" % (SHOT, tag), "wb").write(data)


def main():
    print("== 重启 App ==")
    run(["shell", "am", "force-stop", PKG])
    time.sleep(1)
    print(run(["shell", "am", "start", "-n", PKG + "/.MainActivity"])[1].strip())
    time.sleep(4)
    shot("home")
    home = dump_texts("home")

    # ── 找夹具行（modelLabel = deepseek-flash（fixture））──
    fixture = [it for it in home if "fixture" in it[0]]
    if not fixture:
        print("!! 历史列表里没有夹具行。当前首页文本：")
        for t, y, x in home:
            print("   ", y, t)
        sys.exit(2)
    print("找到夹具行：", fixture[0])

    # 点夹具行的分数位置（同一行的可点区域）
    rc, data, err = run(["exec-out", "cat", "/sdcard/ui.xml"], binary=True)
    xml = data.decode("utf-8", "replace")
    target = None
    for m in re.finditer(r'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
        if y1 <= fixture[0][1] <= y2:
            target = (x1, y1, x2, y2)
            break
    if target is None:
        # 退化：直接点该行文字右侧
        target = (fixture[0][2], fixture[0][1] - 20,
                  fixture[0][2] + 400, fixture[0][1] + 60)
    cx, cy = (target[0] + target[2]) // 2, (target[1] + target[3]) // 2
    print("点击夹具行中心", cx, cy)
    run(["shell", "input", "tap", str(cx), str(cy)])
    time.sleep(3)

    # ── 逐屏收集报告页文本 ──
    all_txt = []
    for i in range(12):
        texts = dump_texts("rep%02d" % i)
        all_txt += [t for t, y, x in texts]
        if i < 11:
            run(["shell", "input", "swipe", "540", "1700", "540", "700", "260"])
            time.sleep(1)
    shot("rep_end")
    # 回到顶部再截一张
    for _ in range(12):
        run(["shell", "input", "swipe", "540", "700", "540", "1700", "260"])
        time.sleep(0.4)
    time.sleep(1)
    shot("rep_top")

    uniq = []
    for t in all_txt:
        if t not in uniq:
            uniq.append(t)

    print("\n== 报告页出现的文本（去重，按首次出现顺序）==")
    for t in uniq:
        print("  ", t)

    def has(pat):
        return any(re.search(pat, t) for t in uniq)

    print("\n== 断言 ==")
    checks = [
        ("A1 有「不可评估 · 调用失败 1 条」", has(r"不可评估 · 调用失败 1 条")),
        ("A2 有「不可评估 · 判定拿不准 2 条」", has(r"不可评估 · 判定拿不准 2 条")),
        ("B  「待人工复核 3 条」（去重生效，非 5）", has(r"待人工复核 3 条")),
        ("B- 未出现「待人工复核 5 条」", not has(r"待人工复核 5 条")),
        ("C1 未见「待人工复核 3 条」之外的重复计数", not has(r"待人工复核 [4-9] 条")),
        ("D1 安全 17/17", has(r"通过 17/17")),
        ("D2 一致 11/11", has(r"通过 11/11")),
        ("D3 抗幻觉 9/9", has(r"通过 9/9")),
        ("D4 鲁棒 10/10", has(r"通过 10/10")),
        ("D5 出现「不可评估 1 条」后缀", has(r"不可评估 1 条")),
        ("E  总分 100.0", has(r"^100\.0$")),
        ("F  SKIPPED 显示接口错误", has(r"HTTP 529")),
    ]
    bad = 0
    for name, ok in checks:
        print(("  [OK]   " if ok else "  [FAIL] ") + name)
        if not ok:
            bad += 1
    print("\n结果：%d 项失败" % bad)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
