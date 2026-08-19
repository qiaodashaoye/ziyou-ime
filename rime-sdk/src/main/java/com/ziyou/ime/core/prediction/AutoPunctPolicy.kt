package com.ziyou.ime.core.prediction

/**
 * 预测候选采纳时的标点自动插入策略（纯函数，可穷举单测）。
 *
 * 对齐主流输入法「联想上屏自动补标点」行为：已上屏「床前明月光」后采纳预测词
 * 「疑似地上霜」，落地为「床前明月光，疑似地上霜」——标点插入在前文与预测词
 * 之间。决策为固定规则（非数值权重，避免不可解释的行为抖动）：
 * 1. 上下文窗口为空 → 不插（无判定依据）；
 * 2. 采纳词自身以标点开头 → 不插。源头治理在 LlmPredictor 解析期剥离前导标点
 *    （前文与续写词之间的标点由本策略统一负责），本规则作纵深防御保留；
 * 3. 前文末词已以任何标点/符号结尾（含 ，。、：！？… 等）→ 不插（避免标点叠加）；
 * 4. 诗句链路 → 不插：前文末词与采纳词同为 5/7 字纯汉字（五言/七言诗句形态）
 *    时视为「一句诗联想整首诗」链式上屏，诗句间逗号会破坏诗的原文形态
 *    （docs/一句诗联想整首诗调研与方案.md §3.4/§5 Phase 2）；
 * 5. 其余 → 插入 [DEFAULT_PUNCT]（逗号：最通用的句中连接；句号需要语义判断，
 *    规则层不妄加，交给 LLM 候选自带标点的自然覆盖）。
 *
 * 判定依据是 [CommitWordWindow] 词窗口末词——窗口收录全部非空白上屏（含独立
 * 标点提交），保证「用户手动打过逗号」等场景判定不失真。
 */
object AutoPunctPolicy {

    /** 默认自动插入的标点：中文逗号（最通用的句中连接标点） */
    const val DEFAULT_PUNCT = "，"

    /**
     * 给定上下文词窗口与采纳词，产出应前置插入的标点（空串 = 不插入）。
     *
     * @param contextWords 词窗口序列（时间序，取末词判定）
     * @param adoptedText 被采纳的预测词文本
     */
    fun decidePrefix(contextWords: List<String>, adoptedText: String): String {
        val last = contextWords.lastOrNull()?.trim().orEmpty()
        if (last.isEmpty()) return ""
        // 采纳词自带前导标点（如 LLM 输出「，低头思故乡」）：标点已就位，不重复插
        val adoptedHead = adoptedText.trimStart().firstOrNull() ?: return ""
        if (!adoptedHead.isLetterOrDigit()) return ""
        // 前文末尾已有标点/符号（isLetterOrDigit 为 false 的可见字符）：避免「，，」叠加
        if (!last.last().isLetterOrDigit()) return ""
        // 诗句链路：前后同为 5/7 字纯汉字（五言/七言）→ 链式补全整首诗，不插逗号
        if (isPoetryChain(contextWords, adoptedText)) return ""
        return DEFAULT_PUNCT
    }

    /**
     * 诗句链路判定（供埋点复用）：词窗口末词与采纳词同为 5/7 字纯汉字。
     */
    fun isPoetryChain(contextWords: List<String>, adoptedText: String): Boolean {
        val last = contextWords.lastOrNull()?.trim().orEmpty()
        return looksLikePoetryLine(last) && looksLikePoetryLine(adoptedText.trim())
    }

    /** 五言/七言诗句形态判定：长度 5 或 7 且全部为 CJK 汉字（纯函数，与构建管线 CJK 范围一致） */
    private fun looksLikePoetryLine(s: String): Boolean {
        if (s.length != 5 && s.length != 7) return false
        return s.all { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
    }
}
