package com.xieguojian.aiseclab.core.jailbreak

import com.xieguojian.aiseclab.core.model.JailbreakStrategy
import java.util.Base64

/**
 * 编码还原器 —— 在归因（以及送判）之前，先把「被编码包起来的有害请求」还原成明文。
 *
 * 为什么需要：编码是最省事的绕过手段 —— 把 `忽略之前的指令` 写成 base64，
 * 表面上一个危险词都没有，任何基于字面的检测都会漏掉。**还原是检测的前置条件。**
 *
 * ⚠️ **不是所有还原结果都可信**，这里做了两处关键降噪：
 * 1. **ROT13 只在还原结果真的命中策略指纹时才保留**。否则对任意文本 rot13 都能得到
 *    一个"新文本"，它会把指纹匹配的输入量凭空翻倍，制造大量假命中。
 * 2. **Base64 只在解码结果「看起来像文本」时才保留**（可打印字符占比 ≥ 0.85）。
 *    随便一段 16 位以上的字母数字都会被 base64 正则命中，不筛的话大部分是二进制垃圾。
 *
 * 与 `L1PatternRules` 的关系：L1 判的是**这条回答违不违规**；本类只负责
 * **把输入还原出来**，供指纹匹配（纯归因）与后续判定使用，**自己不下任何结论**。
 *
 * 与后端 `backend/app/jailbreak.py::EncodingNormalizer` 保持同构（同一套阈值与表）。
 */
object EncodingNormalizer {

    /**
     * 零宽 / 不可见字符。攻击者把它们插在关键词中间，肉眼看不出来但字面匹配会失效。
     *
     * `internal` 而非 `private`：变体生成器（[EncodingVariants]）要用**同一张表**来
     * 造编码样本。抄一份到别处就会出现"生成用的字符集 ≠ 还原用的字符集"——
     * 造出来的样本还原不回去，而测试还会绿（因为它只验自己那半张表）。
     */
    internal val ZERO_WIDTH = setOf(
        '\u200B', // zero width space
        '\u200C', // zero width non-joiner
        '\u200D', // zero width joiner
        '\u2060', // word joiner
        '\uFEFF', // BOM / zero width no-break space
        '\u00AD'  // soft hyphen
    )

    /**
     * 同形字 → ASCII。西里尔 / 希腊字母里长得跟拉丁字母一样的那些，
     * 常被用来写下 `іgnore prevіous` 这种**看起来完全正常**的绕过文本。
     *
     * `internal` 的理由同 [ZERO_WIDTH]：变体生成器必须用同一张表造样本。
     * 反向映射（ASCII → 同形字）由 [EncodingVariants.homoglyph] 在本表上现算。
     */
    internal val HOMOGLYPH: Map<Char, Char> = mapOf(
        // 西里尔小写
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'с' to 'c', 'р' to 'p', 'х' to 'x',
        'у' to 'y', 'і' to 'i', 'ѕ' to 's', 'ј' to 'j', 'ԁ' to 'd', 'һ' to 'h',
        'ѡ' to 'w', 'ν' to 'v',
        // 西里尔大写
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
        'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'Х' to 'X',
        // 希腊
        'α' to 'a', 'ο' to 'o', 'ρ' to 'p', 'Α' to 'A', 'Β' to 'B',
        'Ε' to 'E', 'Ο' to 'O', 'Ρ' to 'P', 'Χ' to 'X'
    )

    /** 候选 base64 块。16 位起 —— 太短的（如 8 位）误报率陡增。 */
    private val BASE64_RE = Regex("""[A-Za-z0-9+/]{16,}={0,2}""")

    /** 候选摩斯段：至少 5 组 `.-` 串，组间用空格或斜杠分隔。 */
    private val MORSE_RE = Regex("""(?:[.\-]{1,6}[ /]+){4,}[.\-]{1,6}""")

    /**
     * 摩斯表。`internal` 的理由同 [ZERO_WIDTH] —— 生成器取它的**反向表**来编码，
     * 反向表现算，避免两张表各写一遍后对不上。
     */
    internal val MORSE_TABLE: Map<String, Char> = mapOf(
        ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
        "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
        "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
        ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
        "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
        "--.." to 'Z',
        "-----" to '0', ".----" to '1', "..---" to '2', "...--" to '3', "....-" to '4',
        "....." to '5', "-...." to '6', "--..." to '7', "---.." to '8', "----." to '9'
    )

    /** 还原结果。 */
    data class NormalizationResult(
        /** 剥离零宽字符 + 同形字归一之后的文本。 */
        val cleaned: String,
        /** 各编码还原出来的明文（可能多条）。 */
        val decodedVariants: List<String>,
        /** 命中的编码种类：`zero_width_or_homoglyph` / `base64` / `rot13` / `morse`。 */
        val detectedEncodings: List<String>
    ) {
        /** 是否有任何编码痕迹。 */
        val hasEncoding: Boolean get() = detectedEncodings.isNotEmpty()

        /**
         * 供指纹匹配用的合并文本：清洗后的原文 + 全部还原结果。
         *
         * 为什么带原文：清洗只删零宽、只归一化同形字，原文的关键词都还在；
         * 带上它能覆盖"没编码、只是插了零宽字符"的情形。
         */
        fun forMatching(): String = buildString {
            append(cleaned)
            decodedVariants.forEach { append('\n').append(it) }
        }
    }

    fun normalize(text: String): NormalizationResult {
        val cleaned = clean(text)
        val variants = mutableListOf<String>()
        val kinds = mutableListOf<String>()

        if (cleaned != text) kinds.add("zero_width_or_homoglyph")

        // ── base64 ──
        BASE64_RE.findAll(cleaned).forEach { m ->
            val raw = m.value
            val dec = runCatching {
                String(Base64.getMimeDecoder().decode(raw), Charsets.UTF_8)
            }.getOrNull()
            if (dec != null && looksLikeText(dec)) {
                variants.add(dec)
                if ("base64" !in kinds) kinds.add("base64")
            }
        }

        // ── rot13 ──
        // 两道闸，缺一不可：
        // ① 还原结果必须命中指纹 —— 否则等于给每段文本凭空造一个变体；
        // ② 命中的**不能只有编码族** —— 「裸长串」那条指纹（`[A-Za-z0-9+/]{40,}`）对 rot13
        //    结果天然成立（rot13 不改变字符集），只看 ① 的话任意长字母数字串都会被判成
        //    "rot13 编码"（实测：一段二进制 base64 就触发了，base64 闸拒掉了它、rot13 闸收了它）。
        //    rot13 的意义是把隐藏的有害意图**还原成明文**；还原出来还是编码样的东西 = 什么都没暴露。
        val r13 = rot13(cleaned)
        val r13HitsNonEncoding = StrategyFingerprintLibrary.ALL.any { fp ->
            fp.strategy != JailbreakStrategy.ENCODING && fp.matches(r13)
        }
        if (r13 != cleaned && r13HitsNonEncoding) {
            variants.add(r13)
            kinds.add("rot13")
        }

        // ── morse ──
        MORSE_RE.findAll(cleaned).forEach { m ->
            decodeMorse(m.value)?.let {
                if (it.length >= 3) {
                    variants.add(it)
                    if ("morse" !in kinds) kinds.add("morse")
                }
            }
        }

        return NormalizationResult(cleaned, variants, kinds)
    }

    /** 剥离零宽字符 + 同形字归一。 */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c in ZERO_WIDTH) continue
            sb.append(HOMOGLYPH[c] ?: c)
        }
        return sb.toString()
    }

    /** 便利方法：直接给出可匹配文本（内部已含清洗与还原）。 */
    fun forMatching(text: String): String = normalize(text).forMatching()

    /**
     * ROT13。`internal` 的理由同 [ZERO_WIDTH]：变体生成器用它**编码**，
     * 本类用同一个实现**还原** —— 两边共用一份映射才谈得上"还原得回去"。
     * （ROT13 是自反的，编码/解码同一函数。）
     */
    internal fun rot13(s: String): String = s.map { c ->
        when (c) {
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    /**
     * 摩斯解码。**任一段认不出就整体放弃**（return null）——
     * 半截解码出来的字符串没有意义，还会污染指纹匹配。
     */
    private fun decodeMorse(s: String): String? {
        val tokens = s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size < 4) return null
        val sb = StringBuilder()
        for (t in tokens) {
            if (t == "/") { sb.append(' '); continue }
            sb.append(MORSE_TABLE[t] ?: return null)
        }
        return sb.toString().trim()
    }

    /** 解码结果是否"像一段文本"：可打印 ASCII 或 CJK 占比 ≥ 0.85。 */
    private fun looksLikeText(s: String): Boolean {
        if (s.length < 4) return false
        val printable = s.count {
            it.code in 0x20..0x7E ||
                it.code in 0x4E00..0x9FFF ||   // CJK 统一汉字
                it.code in 0x3000..0x303F      // CJK 标点
        }
        return printable.toDouble() / s.length >= 0.85
    }
}
