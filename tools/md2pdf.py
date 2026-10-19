# -*- coding: utf-8 -*-
"""把参赛文档 Markdown 排成正式 PDF。

为什么不用现成导出器：
  原 PDF 是 wj_markdown-editor 导的，三个硬伤 —— 封面和目录挤在第 1 页、
  页脚挂着编辑器水印、表格里 `core/api/OpenAiCompatibleClient.kt` 被硬折成
  `core/api/Ope / nAiCompatibl / eClient.kt`（列宽被压到 40px，完全没法读）。

做法：Markdown → HTML（带打印样式）→ Edge headless 打印 → PyMuPDF 补页码。
页码必须后期加：Chrome/Edge 不支持 CSS Paged Media 的 @bottom-center。

用法：
    python tools/md2pdf.py [输入.md] [输出.pdf]
"""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time

import markdown
import pymupdf

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MD = os.path.join(PROJECT, "docs", "参赛-产品技术文档.md")
DEFAULT_PDF = os.path.join(os.path.expanduser("~"), "Desktop", "参赛-产品技术文档.pdf")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

DOC_TITLE = "AISecLab —— BYOK 大模型红队测评与越狱取证系统"

CSS = r"""
@page { size: A4; margin: 19mm 18mm 17mm 18mm; }

* { box-sizing: border-box; }

html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }

body {
  margin: 0;
  font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
  font-size: 10pt;
  line-height: 1.62;
  color: #1b1b1f;
}

/* ── 封面 ── */
.cover { page-break-after: always; padding-top: 24mm;
         display: flex; flex-direction: column; min-height: 246mm; }
.cover .rule-top { height: 4px; background: #1f4e79; margin-bottom: 10mm; }
.cover h1 {
  font-size: 24pt; line-height: 1.38; margin: 0 0 6mm 0; font-weight: 700;
  border: none; padding: 0; color: #10243a;
}
.cover .subtitle {
  font-size: 12.5pt; letter-spacing: 3px; color: #1f4e79; font-weight: 600;
  margin-bottom: 16mm;
}
.cover table { width: 100%; border-collapse: collapse; font-size: 10pt; }
.cover td { border: 1px solid #cfd8e3; padding: 7px 10px; vertical-align: top; }
.cover td:first-child {
  width: 24%; background: #eef3f8; font-weight: 600; color: #10243a; white-space: nowrap;
}
.cover .foot {
  margin-top: auto; font-size: 9pt; color: #7a8794;
  border-top: 1px solid #e2e8ef; padding-top: 4mm;
}

/* ── 目录 ── */
.toc { page-break-after: always; }
.toc h2 {
  font-size: 17pt; color: #10243a; border-bottom: 2px solid #1f4e79;
  padding-bottom: 3mm; margin: 0 0 7mm 0;
}
.toc ol { list-style: decimal; margin: 0; padding-left: 9mm; }
.toc li {
  font-size: 11pt; padding: 3.2mm 0; border-bottom: 1px dotted #d8dee7;
}
.toc li::marker { color: #6b7a8b; }
.toc a { color: #1b1b1f; text-decoration: none; }
.toc .idx { display: inline-block; width: 9mm; color: #6b7a8b; }

/* ── 正文 ── */
.body > h2 {
  page-break-after: avoid;
  page-break-before: auto;
  font-size: 15pt; color: #10243a; margin: 0 0 6mm 0; padding-bottom: 3mm;
  border-bottom: 2px solid #1f4e79;
}
h3 { font-size: 12pt; color: #1f4e79; margin: 5mm 0 2.4mm; page-break-after: avoid; }
h4 { font-size: 11pt; color: #24405c; margin: 5mm 0 2mm; page-break-after: avoid; }

p { margin: 0 0 2.6mm 0; text-align: justify; }
ul, ol { margin: 0 0 2.6mm 0; padding-left: 6.5mm; }
li { margin: 1.2mm 0; }
li > p { margin: 0; }

strong { color: #0f2b46; }

/* 行内代码：别让它把行撑爆 */
code {
  font-family: Consolas, "Cascadia Mono", monospace;
  font-size: 0.86em; background: #eef1f5; color: #17324d;
  padding: 0.5px 3px; border-radius: 3px;
  word-break: break-word;
}

/* 代码块：自动换行，绝不横向溢出 */
pre {
  background: #f6f7f9; border: 1px solid #dfe4ea; border-left: 3px solid #1f4e79;
  border-radius: 3px; padding: 2.4mm 3mm; margin: 0 0 3mm 0;
  /* ⚠️ 这里必须是 auto 而不是 avoid。目录树那种超长代码块一旦 avoid，
     整块被推到下一页，上一页只剩一个章节标题（实测第 14 页只有 "4. 程序实现"）。
     auto 的行为是"放得下就不断"，短代码块照样完整。 */
  page-break-inside: auto;
}
pre code {
  background: none; padding: 0; font-size: 7.9pt; line-height: 1.42;
  white-space: pre-wrap; word-break: break-word; color: #1c2b3a;
}

/* 表格：按内容分配列宽，代码列给足最小宽度 */
table {
  width: 100%; border-collapse: collapse; margin: 0 0 4.5mm 0;
  font-size: 8.6pt;
  /* 同 pre：大表格整体 avoid 会在前一页留下半页空白。允许跨页，
     但单行不许断，表头跨页自动重复。 */
  page-break-inside: auto;
}
thead { display: table-header-group; }
tr { page-break-inside: avoid; }
th, td {
  border: 1px solid #ccd5e0; padding: 3.5px 6px; vertical-align: top;
  text-align: left; word-break: break-word;
}
th { background: #eef3f8; color: #10243a; font-weight: 700; }
td code, th code { font-size: 8.2pt; word-break: break-all; }
/* 「核心类/文件」那类长路径列：给整列一个下限，别把它压成竖排。
   ⚠️ 不要给 code 设 display:inline-block + min-width —— 那样 code 会撑成
   一个 130px 的方块，后面的中文被挤到右边，看起来像多了几个空格。 */
td:nth-child(2) { min-width: 152px; }
/* 第一列多是短标签（M1 模型接入 / 编号 / 用户），被压窄就会竖排成
   「M3 评 / 测调度」。keep-all 让中文只在空格处折行，不再逐字堆叠。 */
th:first-child { white-space: nowrap; }
th:first-child, td:first-child { word-break: keep-all; }

blockquote {
  margin: 0 0 4mm 0; padding: 2.5mm 4mm; background: #f4f8fc;
  border-left: 3px solid #7aa7cc; color: #33506b;
}
blockquote p { margin: 0; }

hr { border: none; border-top: 1px solid #e2e8ef; margin: 4mm 0; }

a { color: #1f4e79; text-decoration: none; }

/* 目录链接在 PDF 里保留内链，但不带下划线 */
.body a { border-bottom: 0.5px dotted #9db6cc; }

/* 插图 */
figure { margin: 3mm 0 3.5mm 0; page-break-inside: avoid; }
figure img { display: block; width: 94%; margin: 0 auto; height: auto; }
figcaption {
  margin-top: 2mm; font-size: 9pt; color: #6b7a8b; text-align: center;
}
"""


def split_sections(text: str):
    """拆成 cover / toc / body 三段。"""
    m_toc = re.search(r"^## 目录\s*$", text, re.M)
    if not m_toc:
        raise SystemExit("找不到 `## 目录`，无法分离封面")
    m_first = re.search(r"^## 1\.", text, re.M)
    if not m_first:
        raise SystemExit("找不到 `## 1.`，无法分离正文")

    head = text[: m_toc.start()]
    toc = text[m_toc.end(): m_first.start()]
    body = text[m_first.start():]
    return head, toc, body


def render(head_md: str, toc_md: str, body_md: str, base_dir: str = "") -> str:
    md = markdown.Markdown(extensions=["fenced_code", "tables", "attr_list", "toc"],
                           extension_configs={"toc": {"permalink": False}})

    # ── 封面：第一行是标题，表格是信息表 ──
    lines = head_md.strip().splitlines()
    title = ""
    for i, ln in enumerate(lines):
        if ln.startswith("# "):
            title = ln[2:].strip()
            lines = lines[:i] + lines[i + 1:]
            break
    # 封面已有「产品技术文档」副标题，原文这行和分隔线要去掉，否则重复出现
    lines = [ln for ln in lines
             if not re.fullmatch(r"\s*\*\*产品技术文档\*\*\s*", ln)
             and not re.fullmatch(r"\s*-{3,}\s*", ln)]
    rest = "\n".join(lines).strip()
    md.reset()
    cover_html = md.convert(rest)

    # 信息表第一列加淡底色（靠 CSS 的 :first-child，无需改 HTML）
    cover = (
        '<section class="cover">'
        '<div class="rule-top"></div>'
        '<h1>%s</h1>'
        '<div class="subtitle">产品技术文档</div>'
        "%s"
        '<div class="foot">2026 年 10 月 · 启航队</div>'
        "</section>"
    ) % (_break_title(title), cover_html)

    # ── 目录：把列表项的序号拆出来做左侧灰字 ──
    md.reset()
    toc_html = md.convert(toc_md)
    toc_html = re.sub(r"<li>", "<li>", toc_html)
    toc = (
        '<section class="toc"><h2>目 录</h2>%s</section>' % toc_html
    )

    # ── 正文 ──
    md.reset()
    body_html = md.convert(body_md)
    body_html = _wrap_figures(body_html)
    body = '<section class="body">%s</section>' % body_html

    # 图片在 Markdown 里写的是相对路径（GitHub 上也靠它显示），
    # 而 HTML 是生成到临时目录的 —— 不给 <base> 的话 src 会解析到临时目录，图全是破的。
    base = ""
    if base_dir:
        uri = pathlib.Path(base_dir).resolve().as_uri() + "/"
        base = '<base href="%s">' % uri

    return (
        '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">%s'
        "<title>%s</title><style>%s</style></head><body>%s%s%s</body></html>"
    ) % (base, _esc(title or DOC_TITLE), CSS, cover, toc, body)


def _wrap_figures(html: str) -> str:
    """把独立成段的 <img> 包成 <figure>，用 alt 当图注。"""
    def repl(m):
        tag, alt = m.group(1), m.group(2)
        cap = ('<figcaption>%s</figcaption>' % alt) if alt else ""
        return "<figure>%s%s</figure>" % (tag, cap)
    return re.sub(r'(<img[^>]*?alt="([^"]*)"[^>]*?>)', repl, html)


def _esc(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def _break_title(t: str) -> str:
    """标题折行位置由人决定，交给浏览器会在词中间断。

    不控制的话实测断成「AISecLab —— BYOK 大模 / 型红队测评与越狱取证系统」；
    用 text-wrap: balance 更糟（它会为了均衡长度往前挪断点）。
    所以在这里按「—— 后面那一节 + 一个词」切开，剩下的整体换行。
    """
    s = _esc(t)
    m = re.match(r"^(.{0,40}?——\s*\S+)\s+(.+)$", s)
    if m and len(s) > 20:
        return m.group(1) + "<br>" + m.group(2)
    return s


def html_to_pdf(html_path: str, pdf_path: str) -> None:
    if not os.path.exists(EDGE):
        raise SystemExit("找不到 Edge：%s" % EDGE)
    profile = os.path.join(tempfile.gettempdir(), "edge-pdf-profile")
    os.makedirs(profile, exist_ok=True)
    url = "file:///" + html_path.replace("\\", "/")
    cmd = [
        EDGE,
        "--headless=new",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "--user-data-dir=" + profile,
        "--print-to-pdf=" + pdf_path,
        # 两个都要给：老版本认 --print-to-pdf-no-header，新版本改成了
        # --no-pdf-header-footer。只给一个的话页眉会印出 URL 和日期（踩过）。
        "--print-to-pdf-no-header",
        "--no-pdf-header-footer",
        "--virtual-time-budget=8000",
        url,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=180)
    if not os.path.exists(pdf_path):
        print(r.stdout[-1500:])
        print(r.stderr[-1500:])
        raise SystemExit("Edge 打印失败")


def _font_path() -> str | None:
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"):
        if os.path.exists(p):
            return p
    return None


def add_page_numbers(pdf_path: str, title: str, chapters=None) -> int:
    """页脚（左下文档名 / 右下页码）+ 目录右侧页码。

    ⚠️ 必须用**嵌入的系统中文字体**（msyh.ttc）。用内置的 "china-s" 会出两种毛病：
    数字被拉成「共 4 0 页」、英文被拉成「A I S e c L a b」—— 它的 CID 宽度表
    只对中文准，ASCII 全靠硬撑。
    """
    font_file = _font_path()
    use_custom = font_file is not None
    fname = "MSYH" if use_custom else "china-s"

    doc = pymupdf.open(pdf_path)
    n = doc.page_count

    # ── 目录页码：在正文里找每章标题第一次出现的页 ──
    if chapters and n >= 3:
        toc = doc[1]
        right = toc.rect.width - 54
        for name in chapters:
            try:
                hits = toc.search_for(name)
                if not hits:
                    continue
                line = hits[0]
                pgno = None
                for i in range(2, n):
                    if doc[i].search_for(name):
                        pgno = i + 1
                        break
                if pgno is None:
                    continue
                kwargs = dict(fontsize=9.6, color=(0.30, 0.36, 0.42))
                if use_custom:
                    kwargs.update(fontname=fname, fontfile=font_file)
                else:
                    kwargs.update(fontname=fname)
                toc.insert_text((right, line.y1 - 1.6), str(pgno), **kwargs)
            except Exception:
                continue

    # ── 页脚 ──
    for i, page in enumerate(doc):
        if i == 0:            # 封面不印页码
            continue
        w, h = page.rect.width, page.rect.height
        pn = "第 %d 页 / 共 %d 页" % (i + 1, n)
        for xy, s, col in (((w - 132, h - 20), pn, (0.46, 0.51, 0.57)),
                           ((36, h - 20), title, (0.63, 0.67, 0.71))):
            kwargs = dict(fontsize=7.6, color=col)
            if use_custom:
                kwargs.update(fontname=fname, fontfile=font_file)
            else:
                kwargs.update(fontname=fname)
            page.insert_text(xy, s, **kwargs)

    tmp = pdf_path + ".tmp"
    # 不子集化会把整份 msyh.ttc（~10MB）塞进 PDF：1.4MB → 13.5MB。
    # 页脚只用到十几个字，subset 之后基本回落到原始体积。
    try:
        doc.subset_fonts()
    except Exception:
        pass
    doc.save(tmp, garbage=4, deflate=True)
    doc.close()
    os.replace(tmp, pdf_path)
    return n


def main() -> int:
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MD
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_PDF

    text = open(src, encoding="utf-8").read()
    head, toc, body = split_sections(text)
    html = render(head, toc, body, os.path.dirname(os.path.abspath(src)))

    tmpdir = tempfile.mkdtemp(prefix="md2pdf-")
    html_path = os.path.join(tmpdir, "doc.html")
    open(html_path, "w", encoding="utf-8").write(html)

    pdf_tmp = os.path.join(tmpdir, "out.pdf")
    t0 = time.time()
    html_to_pdf(html_path, pdf_tmp)

    chapters = [m.group(2).strip() for m in
                re.finditer(r"^##\s+(\d+)\.\s+(.+?)\s*$", body, re.M)]
    pages = add_page_numbers(pdf_tmp, "AISecLab 产品技术文档", chapters)

    if os.path.exists(out):
        shutil.copy2(out, out + ".bak")
    shutil.copy2(pdf_tmp, out)
    shutil.rmtree(tmpdir, ignore_errors=True)

    print("[ok] %s" % out)
    print("     %d 页 / %.1f KB / 用时 %.0fs" % (pages, os.path.getsize(out) / 1024.0, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
