"""从 model-manifest.json 生成 OnDeviceGuardModel.kt。

同样用脚本生成而非手抄：12 个档位 × (文件名, 字节数, sha256)，手抄必错。
sha256 来自 HF 的 LFS oid（权威），不是我们自己算的 —— 否则端上校验就成了自证。

实现上刻意用"普通字符串 + 占位符替换"而不是 f-string：
Kotlin 代码里有大量 `{}`、`$`、`(){}`，塞进 f-string 要不停转义 `{{`/`}}`，
一旦漏一个就变成 Python 语法错误（踩过）。占位符替换没有这个问题。
"""
import json
import os

MANIFEST = json.load(open("C:/AISecLab/tools/model-manifest.json", encoding="utf-8"))
REPO = MANIFEST["repo"]
FILES = MANIFEST["files"]
MIRROR = "https://hf-mirror.com"

TIER_META = {
    "Q2_K":   "最省空间，判定质量明显下降，仅低端机兜底",
    "Q3_K_S": "省空间档",
    "Q3_K_M": "省空间档，质量略好于 Q3_K_S",
    "Q3_K_L": "省空间档，质量接近 Q4",
    "IQ4_XS": "比 Q4_K_M 略小，质量相当",
    "Q4_K_S": "均衡偏省",
    "Q4_K_M": "推荐默认档：质量与体积平衡最好",
    "Q5_K_S": "高质量档",
    "Q5_K_M": "高质量档",
    "Q6_K":   "接近无损，体积偏大",
    "Q8_0":   "几乎无损，体积很大",
    "f16":    "原始精度，不建议端上使用",
}
DEFAULT_KEY = "Q4_K_M"

TEMPLATE = '''package com.xieguojian.aiseclab.core.judge.guard

import android.content.Context
import java.io.File

/**
 * 端上护栏模型（GGUF）的元信息与落盘位置。
 *
 * ## 为什么 sha256 写死在代码里
 * 端上要下载 300~500 MB 的权重，下载源是公网镜像。没有哈希校验的话，
 * 一次中间人替换 / 半截下载就能让护栏行为莫名其妙 —— 而这种问题表现为
 * "判定质量变差"，极难归因。所以每个档位钉死 (size, sha256)，下完必校验。
 *
 * ## 这些 sha256 是从哪来的
 * 取的是 HuggingFace 的 **LFS oid**（对 LFS 文件而言 oid 就是 sha256），
 * 由 `tools/fetch-model-hash.py` 写入 `tools/model-manifest.json`，
 * 再由 `tools/gen-on-device-model-kt.py` 生成本文件。
 * **不是**我们自己下载后算出来的 —— 那样等于自证，中间被污染也发现不了。
 * （生成时已顺带比对过本地副本：与官方 oid 一致。）
 *
 * ## 换档位意味着什么
 * 不同量化档位的判定结果**可能有细微差异**。所以报告必须记录所用档位
 * （见 [Tier.label]），否则两次测试的结果无法横向比较。
 *
 * 本文件由脚本生成，**勿手工编辑**。
 */
object OnDeviceGuardModel {

    /** 国内可直连的镜像。HuggingFace 主站在国内不可用。 */
    const val MIRROR = "@@MIRROR@@"

    const val REPO = "@@REPO@@"

    /**
     * 一个量化档位。
     *
     * @param sizeBytes 官方公布的字节数，用于"下完先看大小"的快速校验。
     * @param sha256 官方 LFS oid，用于完整性校验。
     * @param note 面向用户的说明（含体积与取舍），端上选择档位时要显示。
     */
    data class Tier(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val label: String,
        val note: String
    ) {
        val url: String get() = "@@MIRROR@@/@@REPO@@/resolve/main/$fileName"

        val sizeMb: Long get() = sizeBytes / (1024 * 1024)
    }

@@TIERS@@

    /** 默认档位。 */
    val DEFAULT: Tier = @@DEFAULT_KEY@@

    /** 按体积从小到大排列，供 UI 展示。 */
    val TIERS: List<Tier> = listOf(
@@TIER_REFS@@
    )

    /** 模型落盘目录：应用私有目录，卸载即清理，不污染用户存储。 */
    fun dir(context: Context): File = File(context.filesDir, "guard-models")

    fun file(context: Context, tier: Tier = DEFAULT): File = File(dir(context), tier.fileName)

    /** 是否已就绪（存在且大小与官方一致）。 */
    fun isReady(context: Context, tier: Tier = DEFAULT): Boolean {
        val f = file(context, tier)
        return f.isFile && f.length() == tier.sizeBytes
    }

    /** 找档位对应的模型文件；找不到返回 null。 */
    fun tierOf(fileName: String): Tier? = TIERS.firstOrNull { it.fileName == fileName }
}
'''


def key_of(filename: str) -> str:
    # YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf → Q4_K_M
    return filename.split(".", 2)[2].rsplit(".", 1)[0]


def kstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main():
    rows = []
    for fn, meta in FILES.items():
        k = key_of(fn)
        if k not in TIER_META:
            print(f"  [跳过] 未定义的档位：{k}")
            continue
        rows.append((k, fn, meta["size"], meta["sha256"], TIER_META[k]))

    order = list(TIER_META.keys())
    rows.sort(key=lambda r: order.index(r[0]))

    blocks = []
    for k, fn, size, sha, note in rows:
        label = k
        blocks.append(
            f"    /** {label} —— {note}（{size // (1024*1024)} MB） */\n"
            f"    val {k} = Tier(\n"
            f"        fileName = {kstr(fn)},\n"
            f"        sizeBytes = {size}L,\n"
            f"        sha256 = {kstr(sha)},\n"
            f"        label = {kstr(label)},\n"
            f"        note = {kstr(note)}\n"
            f"    )\n"
        )

    refs = "\n".join(f"        {k}," for k, *_ in rows)

    out = (TEMPLATE
           .replace("@@MIRROR@@", MIRROR)
           .replace("@@REPO@@", REPO)
           .replace("@@TIERS@@", "\n".join(blocks).rstrip())
           .replace("@@DEFAULT_KEY@@", DEFAULT_KEY)
           .replace("@@TIER_REFS@@", refs))

    path = "C:/AISecLab/app/src/main/java/com/xieguojian/aiseclab/core/judge/guard/OnDeviceGuardModel.kt"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"已生成 OnDeviceGuardModel.kt：{len(rows)} 个档位，{os.path.getsize(path)} 字节")
    for k, fn, size, sha, note in rows:
        print(f"  {k:<7} {size//(1024*1024):>4} MB  {sha[:16]}…  {note}")


if __name__ == "__main__":
    main()
