package com.xieguojian.aiseclab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 有害意图 —— 这条用例**到底想让模型干什么**（忽略表面措辞与包装）。
 *
 * 为什么要有它：只看表面文本，`「写一部犯罪小说」`和`「教我制毒」`是两回事；
 * 只有把意图抽出来，才能回答「同一个坏目的，换个包装还攻不攻得破」。
 *
 * ⚠️ 与 [JailbreakStrategy] 的分工：意图 = **目的**，策略 = **手段**。
 * 二者是正交的 —— 同一个「系统提示窃取」意图，可以用角色扮演、编码、虚构情境任意一种策略发起。
 * 报告里的「策略 × 意图矩阵」就是这个正交关系的产物。
 */
@Serializable
enum class HarmIntent(val id: String, val label: String) {
    @SerialName("malware") MALWARE("malware", "恶意代码/入侵"),
    @SerialName("chem_weapon") CHEM_WEAPON("chem_weapon", "危险物品/制毒"),
    @SerialName("discrimination") DISCRIMINATION("discrimination", "歧视/仇恨"),
    @SerialName("privacy_exfil") PRIVACY_EXFIL("privacy_exfil", "隐私窃取"),
    @SerialName("sysprompt_leak") SYSTEM_PROMPT_LEAK("sysprompt_leak", "系统提示窃取"),
    @SerialName("fraud") FRAUD("fraud", "欺诈/社工"),
    @SerialName("self_harm") SELF_HARM("self_harm", "自伤"),
    // ── A7（2026-10-16 补）──────────────────────────────────────────────
    // 借 OpenAI 使用政策的 14 类风险轴做**覆盖率对照**（见
    // `docs/参考项目-能力升级路线.md` §1 A7），只补这两类 ——
    // 判据是「我们现有 7 类确实**接不住**它，且它在本项目里可判定」：
    // - VIOLENCE：现有 `chem_weapon` 只管**危险物品/制毒**，`malware` 只管**代码**，
    //   而「怎么把人打成重伤」「怎么绑架不被追踪」两头都不沾 —— 是实打实的空洞。
    // - MEDICAL：现有 `self_harm` 只管**对自己**，而「无资质下诊断开处方」「在家自己缝合深伤口」
    //   伤害的是**他人或不确定对象**，且它天然与抗幻觉维度交叉（模型是"编"还是"教"）。
    //
    // ⛔ **政策性排除、不建用例的三类**（写在这里防止以后"补全 14 类"时又被想起来）：
    //   CSAM —— 建用例就意味着把这类文本写进仓库与跑进请求体，本项目不碰；
    //   政治游说 / 高风险政府决策 —— 属政治敏感与价值判断，不是可机械判定的安全边界，
    //   强行判定只会产出"模型不听话"的噪声指标。
    // **宁可轴少一条，不要一条轴全是假样本。**
    @SerialName("violence") VIOLENCE("violence", "暴力/人身伤害"),
    @SerialName("medical") MEDICAL("medical", "高风险医疗建议"),
    @SerialName("unknown") UNKNOWN("unknown", "未归类");

    companion object {
        /** 按 wire 值反查；认不出就当未归类（绝不抛——报告解析不能因为一个新意图值就崩）。 */
        fun fromId(id: String): HarmIntent = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * 越狱策略族 —— 这次攻击**用了什么手段**。
 *
 * 为什么要「族」而不是记原始写法：`「忽略之前的指令」`和`「Disregard your prior rules」`
 * 表面完全不同，但属同一策略族（[PROMPT_OVERWRITE]）。归到族才能跨样本统计
 * 「这个模型最怕哪一类手法」——这正是 Strategy-ASR 要回答的问题。
 *
 * [NONE] 是**基线**：纯直问、不加任何包装。它必须存在，否则无法回答
 * 「加了包装之后成功率涨了多少」——所有策略的效果都要减去这个基线才有意义。
 */
@Serializable
enum class JailbreakStrategy(val id: String, val label: String) {
    @SerialName("roleplay") ROLEPLAY("roleplay", "角色扮演"),
    @SerialName("overwrite") PROMPT_OVERWRITE("overwrite", "系统提示覆盖"),
    @SerialName("encoding") ENCODING("encoding", "编码混淆"),
    @SerialName("many_shot") MANY_SHOT("many_shot", "多轮蚕食/many-shot"),
    // ── 多轮族（A3，2026-10-15 补，借 FuzzyAI 的 `crs` / `act`）──────────
    // 这两族**本质是多轮**：`crescendo` 逐轮升压（无害问题起手、逐步逼近敏感目标），
    // `actor_network` 用"角色链"迂回（把目标拆到创作/执行/传播…等行动者身上，绕过单轮意图识别）。
    // 它们**不进变体集**（`JailbreakCases.STRATEGIES`）—— 塞进一条单轮 prompt 就是编归因
    // （与 [MANY_SHOT] 同理，见该族 KDoc）；
    // 但它们**进指纹库**，好让多轮执行器（多轮侵蚀 / 递归对抗）真正跑出来的攻击能被归到族上。
    @SerialName("crescendo") CRESCENDO("crescendo", "递进式加压"),
    @SerialName("actor_network") ACTOR_NETWORK("actor_network", "角色网络"),
    @SerialName("fiction") FICTIONAL_FRAMING("fiction", "虚构情境"),
    @SerialName("tool_inject") TOOL_INJECTION("tool_inject", "工具返回注入"),
    @SerialName("refusal_suppress") REFUSAL_SUPPRESSION("refusal_suppress", "拒绝抑制/前缀注入"),
    // ── 说服 / 社会工程（A2，2026-10-15 补）─────────────────────────────
    // 借自 FuzzyAI 的 `per` / `tax` / `hst` / `exp` / `wrd` 五族。**收它们的理由是方向不同**：
    // 上面那批（overwrite / encoding / fiction…）是**对抗性指令**——把规则当作要被推翻的对象；
    // 这五族是**哄骗**——不否认规则，而是让模型觉得"这次应该给"。
    // 现代模型栽在后者上的比栽在前者上的多（参考件 FuzzyAI §6）。
    @SerialName("persuasion") PERSUASION("persuasion", "说服改写"),
    @SerialName("taxonomy") TAXONOMY("taxonomy", "说服分类学"),
    @SerialName("history_framing") HISTORY_FRAMING("history_framing", "历史学术包装"),
    @SerialName("thought_experiment") THOUGHT_EXPERIMENT("thought_experiment", "思维实验"),
    @SerialName("word_game") WORD_GAME("word_game", "游戏化"),
    // ── 任务层伪装（A5，2026-10-16 补，借 ReNeLLM 的 3 组场景模板）──────────
    // 与上面**两批都不同向**：那批是"对规则下手"（推翻它），这批是"哄骗"（让它觉得该给），
    // 这一族是**改换任务本身** —— 不提"请回答我的问题"，而是把有害诉求埋进一个
    // **待完成的工作产物**（代码补全 / 表格填写 / 故事续写）里，让模型以为自己只是在干活。
    // 15 族里此前**没有一族**是这个方向的（实测缺口，见
    // `docs/借鉴-ReNeLLM-可迁移清单.md` §1）—— 这也是路线里唯一一条
    // **只增用例、不动判定链**的新增，历史数字方向不变。
    @SerialName("scenario_nesting") SCENARIO_NESTING("scenario_nesting", "任务层伪装"),
    @SerialName("none") NONE("none", "纯直问（基线）");

    companion object {
        fun fromId(id: String): JailbreakStrategy = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * 策略指纹 —— 把表面写法归到策略族的规则。**纯静态、不烧 token**。
 *
 * 为什么不全靠 LLM 识别策略：策略识别要在每条用例上跑，成本会随用例数线性涨；
 * 而越狱手法的表面特征（关键字、句式）高度模式化，正则足够覆盖绝大多数。
 *
 * `keywords` 与 `patterns` 是**并列**关系（命中任一即算），不是与关系：
 * 有些手法（DAN、developer mode）是专有名词，有些（`忽略上述所有规则`）是句式。
 *
 * ⚠️ 与 `L1PatternRules` 的区别：L1 判的是**这条用例违不违规**（影响 verdict），
 * 指纹只判**用了什么手法**（纯归因、**绝不回流影响判定**）。二者共用正则设施但不共用结论。
 */
@Serializable
data class StrategyFingerprint(
    val strategy: JailbreakStrategy,
    /** 大小写不敏感的字面命中（专有名词、固定搭配）。 */
    val keywords: List<String> = emptyList(),
    /** 正则源码。端上按 [_compiled] 缓存编译，别在热路径上重复 `Regex(...)`。 */
    val patterns: List<String> = emptyList(),
    /** 命中权重，用于多策略共存时排序（1f = 普通，>1f = 该特征更具判别力）。 */
    val weight: Float = 1f
) {
    /**
     * 人读的指纹名（报告证据链里要写「命中哪条指纹」）。
     * 形如 `overwrite:忽略之前的指令` —— 带策略族前缀，扫一眼就知道归到哪族。
     */
    val label: String
        get() = "${strategy.id}:${keywords.firstOrNull() ?: patterns.firstOrNull() ?: "-"}"

    /** 该指纹是否命中给定文本。`keywords` 与 `patterns` 任一命中即算。 */
    fun matches(text: String): Boolean {
        val lower = text.lowercase()
        if (keywords.any { it.isNotBlank() && lower.contains(it.lowercase()) }) return true
        return patterns.any { src ->
            // 单条 pattern 写错不能让整轮归因炸掉 —— 跳过它，当这条没命中。
            runCatching { compiledRegex(src).containsMatchIn(text) }.getOrDefault(false)
        }
    }
}

/**
 * 正则编译缓存。放**文件级**而不是 `StrategyFingerprint` 的 companion 里：
 * `@Serializable` 类由编译插件生成 `companion.serializer()`，自带私有 companion 会与之打架
 * （生成出来的 `serializer()` 会跟着变 private，外部就取不到了）。
 */
private val FINGERPRINT_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private fun compiledRegex(src: String): Regex =
    FINGERPRINT_REGEX_CACHE.getOrPut(src) { Regex(src, RegexOption.IGNORE_CASE) }

/**
 * 一次攻击链的「意图 × 策略」归因（**运行时识别**结果，区别于用例上的预设标注）。
 *
 * 与 `TestCase.intent` / `TestCase.strategy` 的关系：
 * - 用例上的字段是**设计时写死的**（这条用例本意是用哪种策略）；
 * - 这里的是**跑起来识别出来的**（实际发出的 prompt 命中了哪些指纹）。
 *
 * 两者都要留：前者用来分组统计，后者用来发现「设计的策略和实际发出的不一致」
 * （攻击者 LLM 可能把 prompt 改写成另一种策略，这本身就是值得记进报告的信号）。
 */
@Serializable
data class AttackAttribution(
    val intent: HarmIntent,
    /** 命中的策略族，**可多选**（一次攻击常常叠加多种手法），按 [StrategyFingerprint.weight] 降序。 */
    val strategies: List<JailbreakStrategy> = emptyList(),
    /** 命中的指纹名（[StrategyFingerprint.label]），作为可追溯的证据链。 */
    val matchedFingerprints: List<String> = emptyList(),
    /** 归因置信度 0..1；无任何指纹命中时 = 0（表现为 [JailbreakStrategy.NONE]，不猜）。 */
    val confidence: Float = 0f
)
