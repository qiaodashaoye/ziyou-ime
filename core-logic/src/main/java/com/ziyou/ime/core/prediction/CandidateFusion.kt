package com.ziyou.ime.core.prediction

/**
 * 引擎候选与 LLM 候选的融合规则（永远做加法不做替换）。
 *
 * 引擎预测零延迟零成本是保底体验，恒按原序排前；LLM 词仅在其后
 * 按序追加，去重键按 [dedupeKey] 归一（剔除标点/符号）——既防文本重复，
 * 也防「疑似地上霜」「疑似地上霜。」「疑似地上霜？」这类仅标点不同的
 * 变体占多个位置；首现胜，保留首现项的原始文本（含其句尾标点），
 * 总长截断到 [limit]（见 docs/智能预测可行性方案.md §4.4）。
 */
object CandidateFusion {

    /**
     * 去重归一键：仅保留字母/数字（含 CJK），剔除标点与符号。
     * 「疑似地上霜」/「疑似地上霜。」/「疑似地上霜？」归一后相等 → 只保留首现。
     */
    private fun dedupeKey(text: String): String = text.filter { it.isLetterOrDigit() }

    /**
     * 融合引擎候选与 LLM 候选。
     *
     * @param engineCandidates 引擎候选（原序保留在前，本身不查重不改动）
     * @param llmCandidates LLM 候选（按序追加）
     * @param limit 融合结果总长上限
     * @param exclude 排除文本（按 [dedupeKey] 归一比较）：命中即丢弃该 LLM 词。用于防
     *        「刚上屏的词再次出现在候选栏」——把词窗口传入，则最近上屏过的
     *        词（含刚采纳的续写词）不会被模型复读回显（其标点变体同样被排除）
     * @return 融合后的候选列表（长度 ≤ limit）
     */
    fun fuse(
        engineCandidates: List<String>,
        llmCandidates: List<String>,
        limit: Int = 5,
        exclude: List<String> = emptyList()
    ): List<String> {
        val result = engineCandidates.take(limit).toMutableList()
        // 已收录归一键集合：引擎词 + 排除词 + 已追加的 LLM 词（均剔除标点比较）
        val seen = result.mapTo(mutableSetOf()) { dedupeKey(it) }
        exclude.forEach { seen.add(dedupeKey(it)) }
        for (candidate in llmCandidates) {
            if (result.size >= limit) break
            val trimmed = candidate.trim()
            val key = dedupeKey(trimmed)
            // 空白/纯标点片段（归一后为空）与归一键重复（首现胜）跳过
            if (key.isEmpty() || !seen.add(key)) continue
            result.add(trimmed)
        }
        return result
    }
}
