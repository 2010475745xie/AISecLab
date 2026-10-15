package com.xieguojian.aiseclab.core.jailbreak

import java.util.Base64

/**
 * 编码**生成器** —— [EncodingNormalizer] 的反向操作，供越狱变体集造样本用。
 *
 * 为什么必须有它，而不是在变体集里手写几段编码文本：
 * 1.2 的验收标准是「编码型越狱在还原后可被 L1 命中」。要证明这条，
 * 样本就得是**真的被这套还原器还原得回去**的编码，而不是看上去像编码的字符串。
 * 手写样本极容易写成"形式像、其实还原器不认"（换个字符集、少个分隔符就废了），
 * 而测试会照样绿 —— 因为测试验的是它自己写的那段文本。
 * 生成器直接引用还原器的表，**"编得出来"与"还原得回去"由同一份数据保证**。
 *
 * ⚠️ 本类**只负责造样本**，不参与任何判定链路：它产出的是用例的 prompt，
 * 不是"看到编码就动手解"。线上还原始终走 [EncodingNormalizer]。
 *
 * 与后端 `backend/app/jailbreak.py` 的 `encode_*` 函数保持同构（同一套表与分隔符）。
 */
object EncodingVariants {

    /**
     * 支持造样本的编码种类 id，与 [EncodingNormalizer] 的 `detectedEncodings` 取值对应。
     *
     * **顺序即轮转顺序**：变体集按 `GOALS[i % KINDS.size]` 取种类，所以这里的顺序
     * 决定了哪条目标用哪种编码 —— 改顺序＝改 100 条变体里的 20 条 prompt，
     * 必须与后端 `ENCODING_KINDS` **逐项同序**（`check-jailbreak-parity.py` 会比 prompt 全文）。
     */
    val KINDS: List<String> = listOf(
        "base64", "rot13", "morse", "zero_width", "homoglyph",
        // A1（借 FuzzyAI 的 `pig` / `asc`）：两种**可逆**的新编码，2026-10-15 补。
        "piglatin", "unicode_tag"
    )

    /** 标准 Base64（带 padding）。还原侧用 MIME 解码器，两种解码器对本输出等价。 */
    fun base64(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    /** ROT13。直接借还原器那份实现，不另写一遍 —— 自反，编解码同一函数。 */
    fun rot13(plain: String): String = EncodingNormalizer.rot13(plain)

    /**
     * 摩斯电码：字母间空格、词间 ` / `。
     *
     * **只保留 A–Z 0–9**：其余字符没有摩斯码，硬塞会让还原侧
     * `decodeMorse` 整段放弃（它要求每一段都认得出，见其 KDoc）。
     * 与其造一段还原不回去的"摩斯"，不如在这里就丢掉不支持的字符。
     */
    fun morse(plain: String): String {
        val table = EncodingNormalizer.MORSE_TABLE.entries.associate { (code, ch) -> ch to code }
        val words = plain.uppercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        // 拆成两步写：链式 `joinToString { … }.filter { … }` 会让类型推断把
        // 内层 lambda 的参数当成 Unit（编译报 "inferred type is Unit but Boolean was expected"）。
        val encoded: String = words.joinToString(" / ") { word ->
            word.mapNotNull { table[it] }.joinToString(" ")
        }
        return if (encoded.isNotBlank()) encoded else ""
    }

    /** 在每个字符后插一个零宽空格 —— 字面匹配全部失效，肉眼完全看不出差别。 */
    fun zeroWidth(plain: String): String {
        // 显式写死 U+200B，不用 `ZERO_WIDTH.first()`：
        // 后者依赖集合的**迭代顺序**（Python 那侧的 set 更是无序），
        // 两端一旦取到不同的零宽字符，变体集就不是逐条镜像了，而且**不会报错**。
        val zw = '\u200B'
        return plain.map { "$it$zw" }.joinToString("")
    }

    /**
     * 把拉丁字母换成西里尔 / 希腊同形字（`ignore` → `іgnоrе`）。
     * 反向表由 [EncodingNormalizer.HOMOGLYPH] 现算，保证正好是还原器能吃掉的那些字符。
     *
     * **先按同形字码点升序排序、再用 `distinctBy` 取首个（码点最小）**：正向表里 `a`
     * 同时有西里尔 `а`(U+0430) 与希腊 `α`(U+03B1) 两个候选，直接 `associate` 得到哪一个
     * 是**迭代顺序**说了算 —— 两端顺序一致时"碰巧"对得上，哪天有人往表中间插一行就静默分叉。
     * 排序 + first-wins 是语言无关的确定性规则，与后端 `encode_homoglyph` 同构。
     */
    fun homoglyph(plain: String): String {
        val reverse = EncodingNormalizer.HOMOGLYPH.entries
            .sortedBy { it.key.code }
            .distinctBy { it.value }
            .associate { (fake, ascii) -> ascii to fake }
        return plain.map { reverse[it] ?: it }.joinToString("")
    }

    private val PIG_WORD_RE = Regex("""[A-Za-z]+""")
    private val PIG_HEAD_RE = Regex("""^([^aeiouAEIOU]+)(.+)$""")

    /**
     * **受限**猪拉丁（Pig Latin）：`R + "-" + C + "ay"`（C = 词首辅音簇，R = 其余）。
     *
     * 为什么是"受限"方言（**可逆性换来的，不是偷懒**）：
     * 1. **完整猪拉丁不可逆** —— `strap` 编成 `apstray`，还原时无法确定被搬到末尾的辅音簇
     *    是 `str` 还是 `pstr`（去掉 `ay` 得 `apstr`，尾辅音串有多种切法）；
     * 2. **标准写法自相冲突** —— 元音开头的词加 `-way`，而"辅音簇恰好是 w"的词拼出来一模一样：
     *    `asp-way` 既可能是 `asp` 原地加 `-way`，也可能是 `wasp` 把 w 搬到末尾。
     *
     * 所以本方言**只动"纯 ASCII 字母、且以辅音开头"的词**，元音开头与无字母的词原样保留
     * —— 这样还原只需找最后一个 `-`，唯一可逆（见 [EncodingNormalizer.decodePiglatin]）。
     * 规避效果不受影响：命中词只要以辅音开头就照样被改形。
     *
     * 逐词扫描而不是 `Regex.split`：**空白必须原样保留**（编解码要能逐字还原），
     * 而 `Regex.split` 是否把捕获组放回结果属于 stdlib 实现细节，赌它不如自己写明白。
     */
    fun piglatin(plain: String): String {
        val sb = StringBuilder(plain.length)
        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            if (c.isWhitespace()) { sb.append(c); i++; continue }
            var j = i
            while (j < plain.length && !plain[j].isWhitespace()) j++
            sb.append(pigWord(plain.substring(i, j)))
            i = j
        }
        return sb.toString()
    }

    private fun pigWord(w: String): String {
        if (!PIG_WORD_RE.matches(w)) return w              // 中文/数字/标点：不碰
        if (w[0] in "aeiouAEIOU") return w                 // 元音开头：方言里不动（见 KDoc 第 2 条）
        val m = PIG_HEAD_RE.matchEntire(w) ?: return w     // 全辅音：没有 R 可搬，原样
        return m.groupValues[2] + "-" + m.groupValues[1] + "ay"
    }

    /**
     * 把可打印 ASCII 映射进 Unicode Tag 块（`U+E0000 + ord(ch)`）：肉眼完全不可见，
     * 但 tokenizer 照读 —— 即 FuzzyAI 的 `asc`，「不可见提示走私」。
     *
     * 非 ASCII（中文等）原样保留：Tag 块只覆盖与 ASCII 一一对应的那一段。
     *
     * ⚠️ 必须按**码点**迭代（`codePointAt` + `Character.charCount`）：Python 的
     * `for c in text` 天然是码点，Kotlin 的 `for (c in text)` 是 UTF-16 码元 ——
     * 同一段文本两端会得到**不同的串**，而且不报错。见 [EncodingNormalizer.TAG_BASE]。
     */
    fun unicodeTag(plain: String): String {
        val sb = StringBuilder(plain.length)
        var i = 0
        while (i < plain.length) {
            val cp = plain.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x20..0x7E) sb.appendCodePoint(EncodingNormalizer.TAG_BASE + cp)
            else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** 按 [KINDS] 里的 id 编码；认不出的 id 返回原文（宁可原样，也不猜一个）。 */
    fun encode(kind: String, plain: String): String = when (kind) {
        "base64" -> base64(plain)
        "rot13" -> rot13(plain)
        "morse" -> morse(plain)
        "zero_width" -> zeroWidth(plain)
        "homoglyph" -> homoglyph(plain)
        "piglatin" -> piglatin(plain)
        "unicode_tag" -> unicodeTag(plain)
        else -> plain
    }
}
