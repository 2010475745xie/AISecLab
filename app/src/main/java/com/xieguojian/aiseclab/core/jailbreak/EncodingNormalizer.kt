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
     * Unicode Tag 块 `U+E0000–U+E007F` 的边界。攻击者把 payload 里每个 ASCII 字符换成
     * `U+E0000 + ord(ch)`（`'a'` → `U+E0061`）—— 肉眼与常规正则都看不见
     * （"不可见提示走私"），但 tokenizer 仍会读到原文。这是 FuzzyAI 的 `asc` 技术，
     * 我们此前**完全缺失**（[ZERO_WIDTH] 只含 BMP 内的那几个）。
     *
     * ⚠️ **该块在 BMP 之外**。实测（jshell，Java 与 Kotlin 同为 UTF-16）：
     * ```
     * "A\uDB40\uDC20B"   按 Char 迭代 → U+0041, U+DB40, U+DC20, U+0042
     *                    按码点迭代 → U+00041, U+E0020, U+00042
     * ```
     * 即 `for (c in text)` 拿到的是**代理对的两个 Char**，其 `code` 落在 0xD800–0xDFFF，
     * **永远不在** U+E0000–U+E007F —— 按 `Char` 判会**静默失效**（字面看着对、行为全错）。
     * 而 Python 的 `for c in text` 天然按**码点**：同一份判据两端**不同构且不报错**。
     * 所以 [clean] 必须按码点迭代。
     */
    internal const val TAG_BASE = 0xE0000
    internal const val TAG_MIN = 0xE0000
    internal const val TAG_MAX = 0xE007F

    /** 有没有 Unicode Tag 字符（"不可见提示走私"的载体）。同样按码点扫。 */
    fun hasTag(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in TAG_MIN..TAG_MAX) return true
            i += Character.charCount(cp)
        }
        return false
    }

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
        /**
         * 命中的编码种类：`zero_width_or_homoglyph` / `unicode_tag` / `base64` /
         * `rot13` / `piglatin` / `morse`。取值与 [EncodingVariants.KINDS] 的 id 对应
         * （`zero_width` / `homoglyph` 两种在**上报时合并**成 `zero_width_or_homoglyph` ——
         * [clean] 对二者做的是同一件事，"是零宽还是同形字"在归因上没有区别）。
         */
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

        // 类别要报准：以前是"clean 改过文本就记 zero_width_or_homoglyph"，但 Tag 现在也会改文本 ——
        // 不分开判会把 Tag 走私误报成零宽/同形字（报告里的 detectedEncodings 会显示错）。
        if (text.any { it in ZERO_WIDTH } || text.any { it in HOMOGLYPH }) {
            kinds.add("zero_width_or_homoglyph")
        }
        if (hasTag(text)) kinds.add("unicode_tag")

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

        // ── rot13 ── 两道闸见 [hitsNonEncoding]
        val r13 = rot13(cleaned)
        if (r13 != cleaned && hitsNonEncoding(r13)) {
            variants.add(r13)
            kinds.add("rot13")
        }

        // ── piglatin ── 与 rot13 同一把闸（理由见 [hitsNonEncoding]）：
        // 猪拉丁的还原产物也是**普通英文**，没这道闸，任何含 `xxx-yyay` 的文本都会被收。
        val pl = decodePiglatin(cleaned)
        if (pl != null && pl != cleaned && hitsNonEncoding(pl)) {
            variants.add(pl)
            if ("piglatin" !in kinds) kinds.add("piglatin")
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

    /**
     * 剥离零宽字符 + 同形字归一 + **Unicode Tag 还原成可见字符**。
     *
     * ⚠️ **按码点迭代**，不能写 `for (c in text)` —— 见 [TAG_BASE] 的实测注释：
     * U+E0000 块是代理对，按 `Char` 迭代拿到的两个代理 Char 永远匹配不到该区间，会**静默失效**。
     *
     * Tag 与零宽的**处理方向不同**，别混：零宽只起分隔作用、本身不含信息 → 删掉；
     * Tag 是"隐形地写下 ch"（`U+E0000 + ord(ch)`）→ 必须**还成 ch**，删掉等于把证据也删了。
     */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 0xFFFF) {
                val c = cp.toChar()
                if (c in ZERO_WIDTH) continue
                sb.append(HOMOGLYPH[c] ?: c)
                continue
            }
            if (cp in TAG_MIN..TAG_MAX) {
                sb.appendCodePoint(cp - TAG_BASE)
                continue
            }
            sb.appendCodePoint(cp)
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
     * 这段文本有没有命中**非编码族**的指纹。编码还原的**共用工**（[rot13] 与 [decodePiglatin] 都用）。
     *
     * 两道闸，缺一不可：
     * ① 还原结果必须命中指纹 —— 否则等于给每段文本凭空造一个变体（rot13 对任意文本都有输出）；
     * ② 命中的**不能只有编码族** —— 「裸长串」那条指纹（`[A-Za-z0-9+/]{40,}`）对 rot13
     *    结果天然成立（rot13 不改变字符集），只看 ① 的话任意长字母数字串都会被判成
     *    "rot13 编码"（实测：一段二进制 base64 就触发了，base64 闸拒掉了它、rot13 闸收了它）。
     *    还原的意义是把隐藏的有害意图**还原成明文**；还原出来还是编码样的东西 = 什么都没暴露。
     */
    private fun hitsNonEncoding(text: String): Boolean =
        StrategyFingerprintLibrary.ALL.any { fp ->
            fp.strategy != JailbreakStrategy.ENCODING && fp.matches(text)
        }

    /** 猪拉丁还原用：`(.+)-([^aeiouAEIOU]+)ay`，**整词匹配**（与后端 `re.fullmatch` 同构）。
     *
     * 贪婪 `.+` 会回退到"最后一个能凑出 `-辅音簇ay` 的连字符"：Java 与 Python 两条引擎
     * 都是回溯式，在这条模式上的结论一致，所以两端不会分叉。但**两端各写一遍正则必须逐字
     * 相同** —— 差异只会表现为"两端还原出的变体不一样"，而没有任何地方会报错。 */
    private val PIG_DECODE_RE = Regex("""(.+)-([^aeiouAEIOU]+)ay""")

    /**
     * [EncodingVariants.piglatin] 的严格逆运算：找每词最后一个 `-` 拆开。
     *
     * 与 [decodeMorse] 不同，这里**不做整体放弃**：一个词不像猪拉丁就原样留着
     * （猪拉丁是在正常句子里改词的，不是整段替换），但**至少得有一个词真的被拆开**才返回，
     * 否则返回 null —— "什么都没还原出来"与"还原出来等于原文"要分清（后者会让
     * `pl != cleaned` 之外的调用方误判成一次成功还原）。
     *
     * 为什么限制方言：完整猪拉丁不可逆 —— `strap` 编成 `apstray` 后无法确定被搬走的是
     * `str` 还是 `pstr`；且"元音开头加 `-way`"与"辅音簇是 `w`"撞车（`asp-way` 二义）。
     * 所以生成侧只动"纯 ASCII 字母且以辅音开头"的词（见 [EncodingVariants.piglatin]），
     * 还原侧才敢这么直接拆。**两侧的收窄规则必须成对**，改一边就是不可逆。
     *
     * 分隔符按 Unicode 空白算（`Char.isWhitespace()`），与后端 `re.split(r"(\s+)")` 在
     * 本用例可达的输入（普通空格）上等价；原文的空白**原样保留**（拼接回去不重排）。
     */
    internal fun decodePiglatin(encoded: String): String? {
        val sb = StringBuilder(encoded.length)
        var hit = false
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c.isWhitespace()) {
                sb.append(c); i++; continue
            }
            var j = i
            while (j < encoded.length && !encoded[j].isWhitespace()) j++
            val word = encoded.substring(i, j)
            val m = PIG_DECODE_RE.matchEntire(word)
            if (m != null) {
                sb.append(m.groupValues[2]).append(m.groupValues[1])
                hit = true
            } else {
                sb.append(word)
            }
            i = j
        }
        return if (hit) sb.toString() else null
    }

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
