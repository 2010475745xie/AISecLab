# -*- coding: utf-8 -*-
"""生成 §3.1 的总体架构图（SVG）。

为什么不用 Graphviz / Mermaid：
  - Graphviz 装不装得上另说，它排出来的中文架构图基本没法看（连线路由乱飞）；
  - Mermaid 要拉 puppeteer，为了这一张图不值。
  这里节点是固定五列两行 + 三条链路，坐标算得出来，手写 SVG 反而最可控，
  而且 SVG 在 GitHub 上能直接显示（PNG 也行，但改个字就得重导）。

输出：docs/img/architecture.svg
"""
from __future__ import annotations

import os

W, H = 1000, 620
FONT = "Microsoft YaHei, PingFang SC, Segoe UI, sans-serif"

INK = "#10243a"
MUTED = "#5b6b7c"
LINE = "#8fa8bf"
STROKE = "#a8bfd4"
PANEL_A = "#f6fafd"
PANEL_B = "#f4faf6"


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def box(x, y, w, h, fill="#ffffff", stroke=STROKE, rx=7, dash=None, sw=1.2):
    d = ' stroke-dasharray="5 4"' if dash else ""
    return ('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%s" fill="%s" '
            'stroke="%s" stroke-width="%s"%s/>' % (x, y, w, h, rx, fill, stroke, sw, d))


def text(x, y, s, size=13, weight="400", fill=INK, anchor="middle", spacing=None):
    sp = ' letter-spacing="%s"' % spacing if spacing else ""
    return ('<text x="%.1f" y="%.1f" font-size="%s" font-weight="%s" fill="%s" '
            'text-anchor="%s"%s>%s</text>' % (x, y, size, weight, fill, anchor, sp, esc(s)))


def node(x, y, w, h, title, subs, fill="#ffffff"):
    out = [box(x, y, w, h, fill=fill)]
    ty = y + h / 2 - (len(subs) - 1) * 7 + 1
    out.append(text(x + w / 2, ty, title, size=15, weight="600"))
    for i, s in enumerate(subs):
        out.append(text(x + w / 2, ty + 19 + i * 14, s, size=11.5, fill=MUTED))
    return "".join(out)


def arrow(x1, y1, x2, y2, dash=False):
    d = ' stroke-dasharray="6 5"' if dash else ""
    return ('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="1.4" '
            'marker-end="url(#ar)"%s/>' % (x1, y1, x2, y2, LINE, d))


def build() -> str:
    p = []
    p.append('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">'
             % (W, H, W, H))
    p.append("<defs>"
             '<marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6.5" '
             'markerHeight="6.5" orient="auto-start-reverse">'
             '<path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>'
             "</defs>" % LINE)
    p.append('<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H))

    # ── 端侧面板 ──
    p.append(box(14, 14, 972, 258, fill=PANEL_A, stroke="#c3d3e2"))
    p.append(text(34, 42, "Android 客户端", size=16.5, weight="700", anchor="start"))
    p.append(text(176, 42, "Kotlin 100 文件 / 15,810 行 · Jetpack Compose", size=11.5, fill=MUTED, anchor="start"))
    p.append(text(966, 42, "端上可独立跑完整一轮", size=11.5, fill="#1f4e79", anchor="end", spacing="0.5"))

    nw, gap, ny1, nh = 168, 18, 56, 64
    xs = [34 + i * (nw + gap) for i in range(5)]
    row1 = [
        ("模型接入", ["BYOK · 目标/辅助/护栏", "MasterKey AES256-GCM"]),
        ("用例装载", ["50 条基线", "24×11 越狱变体集"]),
        ("攻击变异", ["6 种确定性变体", "可选多轮递归攻击"]),
        ("目标模型调用", ["任意 OpenAI 兼容", "端点 / 自建模型"]),
        ("判定链", ["闸门→护栏→规则", "→三角色裁判"]),
    ]
    ny2 = ny1 + nh + 54
    for i, (t, s) in enumerate(row1):
        p.append(node(xs[i], ny1, nw, nh, t, s))
        if i < 4:
            p.append(arrow(xs[i] + nw + 1, ny1 + nh / 2, xs[i + 1] - 2, ny1 + nh / 2))

    # 判定链 → 端上护栏（同列折返，两个节点都在第 5 列）
    cx = xs[4] + nw / 2
    p.append('<path d="M %.1f %.1f L %.1f %.1f" stroke="%s" stroke-width="1.4" fill="none" marker-end="url(#ar)"/>'
             % (cx, ny1 + nh, cx, ny2 - 2, LINE))

    # 第二行是「返回路径」，右对齐到第 2…5 列，好让端上护栏正好接住上面的竖箭头
    row2 = [
        ("报告导出", ["Markdown / HTML", "四路分区成章"]),
        ("打分汇总", ["维度分 = PASS 占比", "总分 = 用例加权"]),
        ("四路分区", ["失守 / 待复核", "调用失败 / 判定拿不准"]),
        ("端上护栏（可选）", ["XGuard 0.6B 离线", "只问 SHOULD_REFUSE"]),
    ]
    row2_x = xs[1:]
    for i, (t, s) in enumerate(row2):
        p.append(node(row2_x[i], ny2, nw, nh, t, s))
    for i in range(len(row2) - 1):     # 从右往左
        p.append(arrow(row2_x[i + 1] - 2, ny2 + nh / 2, row2_x[i] + nw + 1, ny2 + nh / 2))

    # 左下角空出来的一格：放一句图例，别让它空着
    p.append(text(34, ny2 + 26, "← 汇总与产出路径", size=10.5, fill="#8296a8", anchor="start"))
    p.append(text(34, ny2 + 44, "判定链定完一条就落一条，", size=10.5, fill="#8296a8", anchor="start"))
    p.append(text(34, ny2 + 60, "整轮结束再一次汇总", size=10.5, fill="#8296a8", anchor="start"))

    # ── 中间：两条运行路径 ──
    my = 292
    p.append(box(34, my, 454, 58, fill="#fffdf5", stroke="#e2cfa0", dash=True, sw=1.1))
    p.append(text(50, my + 23, "① 开「本机运行测试」", size=11.5, weight="600", fill="#7a5c14", anchor="start"))
    p.append(text(50, my + 43, "端上自己跑 TestSession，不碰后端，断网也能出报告", size=10.5, fill="#8a7442", anchor="start"))

    p.append(box(512, my, 454, 58, fill="#f7f9ff", stroke="#b8c6e6", dash=True, sw=1.1))
    p.append(text(528, my + 23, "② 填后端地址 + 开关关闭", size=11.5, weight="600", fill="#2f3f77", anchor="start"))
    p.append(text(528, my + 43, "POST /v1/sessions → 订阅 SSE 进度 → 拉报告", size=10.5, fill="#5c6b96", anchor="start"))

    p.append('<line x1="261" y1="272" x2="261" y2="%d" stroke="%s" stroke-width="1.3" stroke-dasharray="5 4"/>' % (my - 2, LINE))
    p.append(arrow(739, 350, 739, 366, dash=True))

    # ── 后端面板 ──
    p.append(box(14, 368, 972, 238, fill=PANEL_B, stroke="#bcd7c6"))
    p.append(text(34, 396, "AISecLab 后端", size=16.5, weight="700", anchor="start"))
    p.append(text(158, 396, "Python 14 文件 / 5,314 行 · FastAPI · Docker 一键起", size=11.5, fill=MUTED, anchor="start"))

    feats = [
        ("8 个端点", ["会话 / SSE / 报告", "/cancel / /judge"]),
        ("同一套判定链", ["与端上逐条对齐", "机械校验防漂移"]),
        ("多轮递归攻击", ["最多 10 轮", "破防即取证"]),
        ("多次采样稳定性", ["N=3 多数裁决", "压边界抖动"]),
        ("检查点续跑", ["逐条原子落盘", "停止不丢进度"]),
    ]
    fw = 168
    fxs = [34 + i * (fw + 18) for i in range(5)]
    for i, (t, s) in enumerate(feats):
        p.append(node(fxs[i], 416, fw, 64, t, s, fill="#ffffff"))

    p.append(text(500, 530,
                  "两条链路产出的是同一份报告结构：端上 Kotlin 与后端 Python 的判定词表、"
                  "评分口径、风险阈值由工具逐字比对",
                  size=10.5, fill="#3f5648"))
    p.append(text(500, 552, "不一致即构建失败，不靠人记", size=10.5, fill="#3f5648"))

    p.append("</svg>")
    return "".join(p)


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "docs", "img")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "architecture.svg")
    svg = build()
    with open(out, "w", encoding="utf-8") as f:
        f.write(svg)
    print("[ok] %s (%.1f KB)" % (out, os.path.getsize(out) / 1024.0))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
