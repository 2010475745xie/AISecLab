package com.xieguojian.aiseclab.core.judge.guard

/**
 * YuFeng-XGuard 的内置安全分类法。
 *
 * 来源：官方仓库 README 的 Risk Definitions 表（Alibaba-AAIG/YuFeng-XGuard-Reason）。
 * 为什么必须一字不差地抄下来：护栏模型采用的是「首 token 即结论」范式 ——
 * 它第一个吐出来的 token 就是风险代号（`sec`=安全，`dw`=危险武器…），
 * 所以这张代号表就是判定的地基，缺一个代号就等于把该类风险判成"认不出来"。
 */
object GuardTaxonomy {

    /** 安全类的代号。**除了它以外，任何代号都表示检出风险。** */
    const val SAFE_ID = "sec"

    data class Risk(val dimension: String, val category: String)

    /** 代号 → 风险维度/类别。 */
    val TABLE: Map<String, Risk> = mapOf(
        "sec" to Risk("Safe", "Safe"),
        // Crimes and Illegal Activities
        "pc" to Risk("Crimes and Illegal Activities", "Pornographic Contraband"),
        "dc" to Risk("Crimes and Illegal Activities", "Drug Crimes"),
        "dw" to Risk("Crimes and Illegal Activities", "Dangerous Weapons"),
        "pi" to Risk("Crimes and Illegal Activities", "Property Infringement"),
        "ec" to Risk("Crimes and Illegal Activities", "Economic Crimes"),
        // Hate Speech
        "ac" to Risk("Hate Speech", "Abusive Curses"),
        "def" to Risk("Hate Speech", "Defamation"),
        "ti" to Risk("Hate Speech", "Threats and Intimidation"),
        "cy" to Risk("Hate Speech", "Cyberbullying"),
        // Physical and Mental Health
        "ph" to Risk("Physical and Mental Health", "Physical Health"),
        "mh" to Risk("Physical and Mental Health", "Mental Health"),
        // Ethics and Morality
        "se" to Risk("Ethics and Morality", "Social Ethics"),
        "sci" to Risk("Ethics and Morality", "Science Ethics"),
        // Data Privacy
        "pp" to Risk("Data Privacy", "Personal Privacy"),
        "cs" to Risk("Data Privacy", "Commercial Secret"),
        // Cybersecurity
        "acc" to Risk("Cybersecurity", "Access Control"),
        "mc" to Risk("Cybersecurity", "Malicious Code"),
        "ha" to Risk("Cybersecurity", "Hacker Attack"),
        "ps" to Risk("Cybersecurity", "Physical Security"),
        // Extremism
        "ter" to Risk("Extremism", "Violent Terrorist Activities"),
        "sd" to Risk("Extremism", "Social Disruption"),
        "ext" to Risk("Extremism", "Extremist Ideological Trends"),
        // Inappropriate Suggestions
        "fin" to Risk("Inappropriate Suggestions", "Finance"),
        "med" to Risk("Inappropriate Suggestions", "Medicine"),
        "law" to Risk("Inappropriate Suggestions", "Law"),
        // Risks Involving Minors
        "cm" to Risk("Risks Involving Minors", "Corruption of Minors"),
        "ma" to Risk("Risks Involving Minors", "Minor Abuse and Exploitation"),
        "md" to Risk("Risks Involving Minors", "Minor Delinquency")
    )

    /**
     * 长名 → 代号的反查表，如 "crimes and illegal activities-dangerous weapons" → "dw"。
     * 用途：官方 `risk_score` 字典里的键是长名而不是代号，两种形态都要认。
     */
    private val BY_NAME: Map<String, String> = TABLE.entries.associate { (id, r) ->
        key(r) to id
    }

    private fun key(r: Risk): String = normalizeName("${r.dimension}-${r.category}")

    private fun normalizeName(s: String): String = s.lowercase()
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 去掉模型可能带的装饰字符（`*sec*`、`<sec>`、`"sec":` 之类）。 */
    fun normalizeId(raw: String): String = raw.trim()
        .trim('<', '>', '"', '\'', '`', '：', ':', ',', '.', '*', '#', ' ')
        .lowercase()

    fun isSafeId(raw: String): Boolean {
        val id = normalizeId(raw)
        return id == SAFE_ID || id.startsWith("safe")
    }

    /**
     * 把任意形态的标签解析成代号：`sec` / `dw` / `Safe-Safe` / `Crimes and Illegal Activities-Dangerous Weapons`。
     * 解析不出来返回 null —— **宁可返回 null 让上层判"不可评估"，也不要瞎猜一个风险类别。**
     */
    fun resolve(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (isSafeId(trimmed)) return SAFE_ID
        val id = normalizeId(trimmed)
        if (TABLE.containsKey(id)) return id
        // 长名反查（同时试原始文本与"去装饰后"的文本）
        BY_NAME[normalizeName(trimmed)]?.let { return it }
        BY_NAME[normalizeName(id)]?.let { return it }
        return null
    }

    /** 人类可读描述，用于证据链。 */
    fun describe(id: String): String {
        val r = TABLE[normalizeId(id)] ?: return normalizeId(id)
        return "${r.dimension} - ${r.category}（${normalizeId(id)}）"
    }

    /** 该代号对应的风险类别名（不含维度），未知则原样返回。 */
    fun categoryOf(id: String): String =
        TABLE[normalizeId(id)]?.category ?: normalizeId(id)
}
