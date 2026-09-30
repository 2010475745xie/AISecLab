package com.xieguojian.aiseclab.core.judge.guard

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
    const val MIRROR = "https://hf-mirror.com"

    const val REPO = "mradermacher/YuFeng-XGuard-Reason-0.6B-GGUF"

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
        val url: String get() = "https://hf-mirror.com/mradermacher/YuFeng-XGuard-Reason-0.6B-GGUF/resolve/main/$fileName"

        val sizeMb: Long get() = sizeBytes / (1024 * 1024)
    }

    /** Q2_K —— 最省空间，判定质量明显下降，仅低端机兜底（331 MB） */
    val Q2_K = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q2_K.gguf",
        sizeBytes = 347286656L,
        sha256 = "bac263a3f8c9c64f602cad2e89f110ef484229aba558d5e811d46f5c4e0e7c89",
        label = "Q2_K",
        note = "最省空间，判定质量明显下降，仅低端机兜底"
    )

    /** Q3_K_S —— 省空间档（371 MB） */
    val Q3_K_S = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q3_K_S.gguf",
        sizeBytes = 389924992L,
        sha256 = "2268c44571dcb7259d54f6d67b53bd6e0470ed064cb2391edc0a1e359e747613",
        label = "Q3_K_S",
        note = "省空间档"
    )

    /** Q3_K_M —— 省空间档，质量略好于 Q3_K_S（394 MB） */
    val Q3_K_M = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q3_K_M.gguf",
        sizeBytes = 413976704L,
        sha256 = "1fd3f97a6ea06ef95d3a51018a6c2056dac849a6aae19af4d2cece905ccfff4e",
        label = "Q3_K_M",
        note = "省空间档，质量略好于 Q3_K_S"
    )

    /** Q3_K_L —— 省空间档，质量接近 Q4（415 MB） */
    val Q3_K_L = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q3_K_L.gguf",
        sizeBytes = 435341440L,
        sha256 = "62bbcd900d95e1127f62bcc93c141c64417dba5dc87d0f58591a3a352186cba4",
        label = "Q3_K_L",
        note = "省空间档，质量接近 Q4"
    )

    /** IQ4_XS —— 比 Q4_K_M 略小，质量相当（430 MB） */
    val IQ4_XS = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.IQ4_XS.gguf",
        sizeBytes = 451929216L,
        sha256 = "7a5ae105981adbfbf17f416e4109d9c82765ac37455f78d76925410830c3ee48",
        label = "IQ4_XS",
        note = "比 Q4_K_M 略小，质量相当"
    )

    /** Q4_K_S —— 均衡偏省（448 MB） */
    val Q4_K_S = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q4_K_S.gguf",
        sizeBytes = 470783104L,
        sha256 = "9287726cf84a400e840e977ca2b92a2e3dac7f368ad326822a2f883d4e8a053a",
        label = "Q4_K_S",
        note = "均衡偏省"
    )

    /** Q4_K_M —— 推荐默认档：质量与体积平衡最好（461 MB） */
    val Q4_K_M = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q4_K_M.gguf",
        sizeBytes = 484217984L,
        sha256 = "c9766937ea76717be85261fe5518d328d4209fecc291bfa24d18b521d897adde",
        label = "Q4_K_M",
        note = "推荐默认档：质量与体积平衡最好"
    )

    /** Q5_K_S —— 高质量档（518 MB） */
    val Q5_K_S = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q5_K_S.gguf",
        sizeBytes = 543577216L,
        sha256 = "7ce26f566d61344f2a87cde06f46b23991f67929f8fbafc27d8fe9388dd4451a",
        label = "Q5_K_S",
        note = "高质量档"
    )

    /** Q5_K_M —— 高质量档（525 MB） */
    val Q5_K_M = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q5_K_M.gguf",
        sizeBytes = 551376000L,
        sha256 = "b518ca729b378035ca1f187e517a5dc19b50964814617bd09328dd13f83f1db9",
        label = "Q5_K_M",
        note = "高质量档"
    )

    /** Q6_K —— 接近无损，体积偏大（593 MB） */
    val Q6_K = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q6_K.gguf",
        sizeBytes = 622731392L,
        sha256 = "f8a8cf627f0d1d4f6c1f5912547f61bd1f6b70162970e7a24d939e158749df31",
        label = "Q6_K",
        note = "接近无损，体积偏大"
    )

    /** Q8_0 —— 几乎无损，体积很大（767 MB） */
    val Q8_0 = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.Q8_0.gguf",
        sizeBytes = 804751488L,
        sha256 = "3c0d0f65c71945c2ac6c306aa031b77ee8a65874d30c010ccd0ddbcf51306f23",
        label = "Q8_0",
        note = "几乎无损，体积很大"
    )

    /** f16 —— 原始精度，不建议端上使用（1439 MB） */
    val f16 = Tier(
        fileName = "YuFeng-XGuard-Reason-0.6B.f16.gguf",
        sizeBytes = 1509345408L,
        sha256 = "0235f08c1021de6aab70722c524cafa8a0bc6ff4d63f6acdccb0c8b321d393b6",
        label = "f16",
        note = "原始精度，不建议端上使用"
    )

    /** 默认档位。 */
    val DEFAULT: Tier = Q4_K_M

    /** 按体积从小到大排列，供 UI 展示。 */
    val TIERS: List<Tier> = listOf(
        Q2_K,
        Q3_K_S,
        Q3_K_M,
        Q3_K_L,
        IQ4_XS,
        Q4_K_S,
        Q4_K_M,
        Q5_K_S,
        Q5_K_M,
        Q6_K,
        Q8_0,
        f16,
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
