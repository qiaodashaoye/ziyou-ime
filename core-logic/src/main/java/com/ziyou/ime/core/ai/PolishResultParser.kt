package com.ziyou.ime.core.ai

/**
 * 润色候选：一条改写版本 + 可选风格说明。
 *
 * @param text 改写后的文本（已剥离编号与风格说明）
 * @param note 风格说明（如「豪放洒脱」）；模型未输出时为空串
 */
data class PolishVariant(
    val text: String,
    val note: String = ""
)

/**
 * 润色候选解析器
 *
 * 将 LLM 按 [com.ziyou.ime.core.rag.RagPromptBuilder.buildPolish] 约定格式
 * 输出的候选文本解析为 [PolishVariant] 列表。约定格式：
 * ```
 * 1. <版本一文本>（风格说明）
 * 2. <版本二文本>（风格说明）
 * ```
 * 解析纪律（模型输出不完全可控，解析器负责兜底）：
 * - 编号分隔符容忍半角/全角句点、顿号、右括号（`1.` `1．` `1、` `1)`）；
 * - 行尾 `（说明）`/`(说明)` 提取为 [PolishVariant.note]，其余为正文；
 * - 非编号行（模型寒暄/总结等杂散输出）丢弃；
 * - 无任何有效编号行时整体作为单候选兜底（不让用户空手）；
 * - 候选数上限 [MAX_VARIANTS]，超出丢弃（防异常输出刷屏）。
 *
 * 纯文本确定性逻辑，无 Android 依赖（:core-logic 下沉纪律）。
 */
object PolishResultParser {

    /** 候选数上限（prompt 约定 2~3 个，上限仅防御异常输出） */
    const val MAX_VARIANTS = 5

    /** 编号行：前导空白 + 数字 + 分隔符（.．、。） + 空白 + 正文 */
    private val NUMBERED_LINE = Regex("""^\s*\d+\s*[.．、。)]\s*(.+)$""")

    /** 行尾风格说明：全角或半角括号包裹，取最后一个匹配 */
    private val TRAILING_NOTE = Regex("""[（(]([^（）()]{1,30})[)）]\s*$""")

    /**
     * 解析模型原始输出为候选列表；空/空白输入返回空列表。
     * 结果顺序与原文编号顺序一致，文本均已 trim。
     */
    fun parse(raw: String): List<PolishVariant> {
        if (raw.isBlank()) return emptyList()
        val variants = raw.lineSequence()
            .mapNotNull { parseLine(it) }
            .filter { it.text.isNotBlank() }
            .take(MAX_VARIANTS)
            .toList()
        if (variants.isNotEmpty()) return variants
        // 兜底：模型未遵守编号格式时整体作为单候选（去除首尾空白）
        val whole = raw.trim()
        return if (whole.isEmpty()) emptyList() else listOf(PolishVariant(whole))
    }

    /** 解析单行；非编号行返回 null。 */
    private fun parseLine(line: String): PolishVariant? {
        val match = NUMBERED_LINE.matchEntire(line) ?: return null
        var body = match.groupValues[1].trim()
        if (body.isEmpty()) return null
        var note = ""
        TRAILING_NOTE.find(body)?.let { noteMatch ->
            note = noteMatch.groupValues[1].trim()
            body = body.removeRange(noteMatch.range).trim()
        }
        return PolishVariant(text = body, note = note)
    }
}
