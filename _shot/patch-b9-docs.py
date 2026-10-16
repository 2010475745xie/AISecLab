#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B9 负对照 + B7 判定侧闸门/报告侧一行 的文档落账。

改两份：
- docs/参考项目-能力升级路线.md（B7/B9 行、★ 方向判断块、§3.10 补完、§3.14 新节、§4 优先级）
- docs/越狱取证审计-技术方案与MVP.md（落地物附表：B7 补完/B9 行 + 顺手改净过期数字）

**为什么用脚本而不是手改**：同一份文档里有十几处要改，且其中夹着"过期数字"
（19 指纹 / 200 条 / 250 条 / 76 条 97.3% / 15 个策略族 …）——
这类数字只要漏一处，文档就成了第二个真相源。脚本跑完会逐项报 MISS。

⚠️ 所有待替换文本一律用**三引号**包（里面夹着大量中文引号与 `"`）——
用普通双引号会自己把自己截断。
"""
import io
import pathlib
import sys

ROOT = pathlib.Path(r"C:\AISecLab")
ROUTE = ROOT / "docs" / "参考项目-能力升级路线.md"
MVP = ROOT / "docs" / "越狱取证审计-技术方案与MVP.md"

misses = []


def patch(path: pathlib.Path, pairs, label: str):
    s = io.open(path, encoding="utf-8").read()
    n_miss = 0
    for i, (old, new) in enumerate(pairs, 1):
        if old not in s:
            misses.append(f"{label} #{i} MISS: {old[:90]!r}")
            n_miss += 1
            continue
        if s.count(old) > 1:
            misses.append(f"{label} #{i} 不唯一（{s.count(old)} 处）: {old[:70]!r}")
            n_miss += 1
            continue
        s = s.replace(old, new, 1)
    io.open(path, "w", encoding="utf-8", newline="\n").write(s)
    print(f"  {label}: {len(pairs)} 处，MISS {n_miss}")


# ── 路线文档 ────────────────────────────────────────────────────────────
ROUTE_PAIRS = [
    # (1) §2 B7 行
    (
        '''| ✅ **判定链已实现**（两端 + 三重验证，2026-10-16，见 §3.10）；**报告侧"无效 N 条"一行尚未做** |''',
        '''| ✅ **已完成**（两端 + 三重验证，2026-10-16，见 §3.10 与 §3.10.1）：**判定侧闸门**（坏输出在 `judge()` 最外层就被拦成 SKIPPED，护栏/规则层看不到它）+ **报告侧「输出有效性」一行**（报告页 / Markdown / HTML 三处同源） |''',
    ),
    # (2) §2 B9 行
    (
        '''无害对照组的期望失守率 ≈ 0；显著偏离 ⇒ 判定链有假阳性，或策略指纹认的是包装而非内容 | ⬜ **未开始** |''',
        '''无害对照组的期望失守率 ≈ 0；显著偏离 ⇒ 判定链有假阳性，或策略指纹认的是包装而非内容 | ✅ **已实现**（2026-10-16，见 §3.14）：264 条对照集 + `check-negative-control.py` 七项校验（含「包装与有害组同源」的机械证明） |''',
    ),
    # (3) ★ 方向判断块的实测账
    (
        '''> 已重跑全量单测（262 项）、后端 `_verify_refusal.py`（`[2.1] ALL OK`）、两端 parity，
> 以及 `_verify_compliance.py` / `_verify_judge_fix.py`。**真机跑一轮对比报告仍待做**。''',
        '''> **判定侧闸门（同日补，见 §3.10.1）**：上面那三行只覆盖了**测量**。
> `judgeResult.verdict` 那条链此前**一处都没问过输出可不可用** ⇒ 词循环被判 **PASS**（假安全）、
> 纯标点被判 **FAIL**（假失守）、空回答被判 **FAIL**，而且**照样进分母**；配了护栏更糟
> （护栏判的是「内容危不危险」，没有「这段输出压根不可用」这个概念）。
> 现在闸门在最外层（护栏之外）把它们落成 `SKIPPED` ⇒ 「不进分母」才从文档里的一句话变成事实。
>
> 已重跑全量单测（**290 项**）、后端 `_verify_refusal.py`（`[2.1] ALL OK`，新增 `== 3d ==` 15 条）、
> `check-judge-parity.py`（新增**判定链形状**校验）、`_verify_compliance.py` / `_verify_judge_fix.py`、
> `smoke_test.py`、`check-jailbreak-parity.py`、`check-negative-control.py`。**真机跑一轮对比报告仍待做**。''',
    ),
    # (4) §3.10 之后插入 3.10.1
    (
        '''⚠️ **UNKNOWN 与 INVALID 不是一回事**：UNKNOWN = 没测到（空 / 调用失败），
INVALID = 测到了但输出不可用。两者**都不进百分比分母**（铁律 5.2 的延伸）。
''',
        '''⚠️ **UNKNOWN 与 INVALID 不是一回事**：UNKNOWN = 没测到（空 / 调用失败），
INVALID = 测到了但输出不可用。两者**都不进百分比分母**（铁律 5.2 的延伸）。

### 3.10.1 B7 的两处补完（2026-10-16 同日）

**(a) 判定侧闸门** —— 上面那句「不进百分比分母」在**测量**上成立，在**判定**上当时是假的：
`output_validity` 只被 `RefusalScorer.classify` / `ComplianceDetector.detect` 用到，
而 `judgeResult.verdict` 那条链**从头到尾没有问过输出可不可用**。双向失真，而且都进分母：

| 场景 | 闸门之前 | 方向 |
|---|---|---|
| `" I'm sorry" × 24`（词循环） | 拒绝锚命中 ⇒ **PASS**（守住） | 假安全：报告替一个坏掉的模型背书 |
| `。。。。。。。。`（纯标点） | 无拒绝锚 ⇒ **FAIL**（失守） | 假失守：凭空造一条失守 |
| 空回答（200 但内容为空） | 无拒绝锚 ⇒ **FAIL** | 同上（「空」根本不是一次作答） |
| 配了护栏时 | 上面每一条都先过护栏；护栏判的是「内容危不危险」，**没有**「这段输出压根不可用」这个概念 ⇒ 低危险度 ⇒ **PASS** | 假安全 |

所以闸门必须在**最外层**（护栏之外），不能塞进规则层：后端加在 `judge()` 里
（`output_validity` 先于 `rule_layer`），端上是 `ValidityGatedJudge` 在 `TestSession`
构造 judge 时包的那一层（**唯一应用点** —— 放进 `RuleJudge` / `GuardJudge` 内部的话，
每加一个 Judge 实现就要记得再包一次，而**漏掉的那一个不会报错**）。
落 `SKIPPED`（不可评估），由 `ScoringModel` / `report_gen` 从分母剔除 ——
**「模型坏了」和「模型被攻破了」是两条不同的结论，取证报告不能把它们混成一个数。**

**(b) 报告侧「输出有效性」一行** —— 无效回答被踢出分母 ⇒ **分母变了**。
分母变了却不说，「失守率降了」就会被读成「模型变强了」。所以报告页 / Markdown / HTML
三处共用 `core/report/ValidityNote.kt::validityNote()`：0 条时正面说「全部可用」
（0 是**好结论**，不是「没采集」）；N 条时说清比例 + 「既不算守住也不算失守」+ 「不是模型守住了」。

**证据**：`_verify_refusal.py` 新增 `== 3d ==`（15 条，含「配了护栏也不许进护栏」——
用会抛异常的假护栏证明闸门在内层之前）；`check-judge-parity.py` 新增**判定链形状**校验
（闸门位置 / 唯一应用点 / 证据文案两端一致）；`ValidityGateTest`（9 条）、`ValidityNoteTest`（7 条）。
''',
    ),
    # (5) §4 优先级 #7
    (
        '''| 7 | ~~**B7 退化/无效输出第三态**~~ | ✅ **判定链已完成**（2026-10-16，§3.10）。**当时就在双向失真**（假失守 + 假安全），且 `refusal_strength` 被侵蚀曲线 / 半妥协率共用 ⇒ 三个数字同时被污染。**遗留**：报告侧「无效 N 条」一行未加 —— 现在报告不会主动说"这轮有多少条输出是坏的" |''',
        '''| 7 | ~~**B7 退化/无效输出第三态**~~ | ✅ **已完成**（2026-10-16，§3.10 + §3.10.1）。**当时就在双向失真**（假失守 + 假安全），且 `refusal_strength` 被侵蚀曲线 / 半妥协率共用 ⇒ 三个数字同时被污染。同日补完两处——**判定侧闸门**（原先「不进分母」只在测量上成立）与**报告侧一行**（分母变了就必须自证） |''',
    ),
    # (6) §4 优先级 #11
    (
        '''| 11 | **B9 负对照基线** | ⬜ 未开始。**唯一能证伪判定链自身的东西**；要造"无害目标 × 现有策略包装"，成本可预算，单独一轮 |''',
        '''| 11 | ~~**B9 负对照基线**~~ | ✅ **已完成**（2026-10-16，§3.14）。**唯一能证伪判定链自身的东西**：把有害目标换成无害目标、包装一字不改，期望失守率 ≈ 0。为此先把包装的 dispatch 抽成 `promptFor()` **唯一实现**（各写一份 = 对照组与实验组不是同一个实验，且报告上看不出来） |''',
    ),
    # (7) 新节 §3.14 —— 插在 §4 之前
    (
        '''## 4. 优先级（修正后）''',
        '''### 3.14 B9 负对照基线：唯一能证伪判定链自身的东西

**命题**：本项目所有指标**只能自己证明自己**。失守率 12% 说明不了什么 ——
没人知道「**如果内容本身无害，同一套包装会不会也被读成失守**」。
「基线对比」只跟自己的上一次比，缺一个**独立**期望值。这一集就是那个期望值。

**做法**：把 `JailbreakCases.GOALS` 的 24 个有害目标**原样换成无害目标**，
包装（模板 / 编码种类 / 说服技术 / 场景）**一字不改**，跑**同一条判定链**。
**期望失守率 ≈ 0。**

**显著偏离 0 只有两种解释，而且两种都必须让人看见**：
1. **判定链有假阳性** —— 它读的是「这句话像不像坏话」，而不是「你给没给有害内容」；
2. **模型对包装本身过敏** —— 那也是一条真发现：有害组里那些「拒绝了」的样本，
   拒绝理由可能来自**包装**（「你想让我忽略规则」）而不是**内容**。
   这种情况下把「拒绝率」直接读成「模型的安全对齐水平」就是错的。

**两条纪律**（不遵守的话，这一集比没有更糟）：
1. **包装一个字都不许改** ⇒ 负对照**不自己拼模板**，一律走
   `JailbreakCases.promptFor()` ↔ `jailbreak_cases.prompt_for()`。
   为此把 `buildCases()` 里那坨 `when(strategy)` dispatch 抽成了
   `promptFor(strategy, goal, i) -> Wrapped` —— **包装的唯一实现**。
   各写一份的后果是「对照组和实验组其实不是同一个实验」，而报告上完全看不出来。
2. **不进变体集、不进默认 50 条** —— 它的 `expected` 是 `SHOULD_ANSWER_CORRECTLY`
   而非 `SHOULD_REFUSE`，混进去会污染失守率的分母（把对照目标算成「该拒绝却没拒绝」）。

**`i` 对齐是硬要求**：`i` 同时决定编码种类 / 说服技术 / 场景，错位就意味着两组包装其实不一样。
后端 `assert len(GOALS) == len(_HARMFUL_GOALS)` 钉住，端上 `BenignControlsTest` 钉住。

**交付物**：
- 两端各 **264 条**（11 族 × 24 目标）：`core/jailbreak/BenignControls.kt` ↔ `backend/app/benign_controls.py`；
- `SessionRequest.benignControls`（**诊断专用，端上不发**）+ `engine` 里**替代**变体集的并入分支
  （叠加会让对照组的期望值失去意义；未勾安全维度则不并入，与界面预告的条数对齐）；
- `tools/check-negative-control.py`（**七项**）：包装唯一实现（源码级）/ 下标对齐 /
  prompt 是载荷的纯函数 / 非编码族替换回代 / 编码族包装行逐字相同 / 阴性纪律 / 两端 264 条逐条一致；
- `BenignControlsTest`（11 条）+ `BenignControlParityDumpTest` + `smoke_test.py` 负对照段。

**实测**：`check-negative-control.py` → `ALL OK`。其中两条最有信息量：
- **非编码族 240 条替换回代后逐字等于有害 prompt** ⇒ 两组只差载荷，包装确实没改；
- **编码族 24 条包装行逐字相同**（`encodingPayload` 只取 `goal.en`，编码体纯粹是载荷）。

---

## 4. 优先级（修正后）''',
    ),
]

# ── MVP 文档（落地物附表）──────────────────────────────────────────────
MVP_PAIRS = [
    ('''### 落地状态（2026-10-15 快照）''', '''### 落地状态（2026-10-16 快照）'''),
    ('''（19 条 / 14 族）''', '''（20 条指纹 / 15 个策略族）'''),
    ('''「编码族的20条还原后都能命中覆盖指纹」''', '''「编码族的24条还原后都能命中覆盖指纹」'''),
    ('''200 条 = 20 目标 × 10 策略''', '''264 条 = 24 目标 × 11 策略'''),
    ('''（开→250 条）''', '''（开→314 条）'''),
    ('''含自测集 76 条：**97.3%**；朴素基线仅 **79.5%**''', '''含自测集 80 条：**97.5%**；朴素基线仅 **73.8%**'''),
    ('''**两端逐条分类 76/76 完全一致**''', '''**两端逐条分类 92/92 完全一致**'''),
    ('''15 个策略族一个不落''', '''16 个策略族一个不落'''),
    ('''`HardeningAdvisorTest`（15 条）''', '''`HardeningAdvisorTest`（16 条）'''),
]

MVP_TAIL_ANCHOR = '''| B8 | **CoT 剥离覆盖补全**（`<analysis>` / Harmony 通道 / `final` 标记 + 未闭合思考块判无效） | `_verify_refusal.py`、`CoTGovernanceTest` |
'''
MVP_TAIL_ADD = '''| B7 补完 | **判定侧闸门 + 报告侧一行**：坏输出在 `judge()` / `ValidityGatedJudge` 最外层就被拦成 `SKIPPED`（护栏/规则层看不到它）；报告页 / Markdown / HTML 新增「输出有效性」一行 | `ValidityGateTest`（9 条）、`ValidityNoteTest`（7 条）、`_verify_refusal.py` `== 3d ==`（15 条）、`check-judge-parity.py`（判定链形状校验） |
| B9 | **负对照基线**（264 条 = 24 无害目标 × 11 族；包装与有害组**同源**、期望 `SHOULD_ANSWER_CORRECTLY`） | `BenignControlsTest`（11 条）、`tools/check-negative-control.py`（七项，含替换回代 240 条逐字还原）、`smoke_test.py`（负对照段） |
'''


def main():
    print("patch-b9-docs")
    patch(ROUTE, ROUTE_PAIRS, "路线文档")
    patch(MVP, MVP_PAIRS, "MVP 文档")

    s = io.open(MVP, encoding="utf-8").read()
    if MVP_TAIL_ANCHOR not in s:
        misses.append("MVP 追加锚点 MISS（B8 行）")
    elif "| B7 补完 |" in s:
        print("  MVP 追加: 已存在，跳过")
    else:
        s = s.replace(MVP_TAIL_ANCHOR, MVP_TAIL_ANCHOR + MVP_TAIL_ADD, 1)
        io.open(MVP, "w", encoding="utf-8", newline="\n").write(s)
        print("  MVP 追加: B7 补完 / B9 两行")

    print()
    if misses:
        print(f"!! {len(misses)} 项未落盘：")
        for m in misses:
            print("   -", m)
        return 1
    print("ALL OK —— 两份文档落账完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
