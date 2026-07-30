package com.ziyou.ime.core.rag

/**
 * 敏感词过滤器
 *
 * 面向知识库导入内容与 AI 回答的轻量内容安全过滤：
 * - [check]：文本是否命中任一敏感词；
 * - [sanitize]：命中词替换为等长 `*`（其余内容原样保留）。
 *
 * 词表由构造方注入（大小写不敏感，ASCII 词统一小写比对）；预期词表
 * 规模 <1k，直接逐词 indexOf 匹配即可，未来量级增长可替换为 Trie /
 * Aho-Corasick 而不影响调用方。
 */
class SensitiveWordFilter(words: Set<String>) {

    companion object {
        /**
         * 内置最小词表（占位基线）：覆盖明显违规类目的少量示例词，
         * 完整词表后续经设置项扩展（预留），本期不做用户自定义 UI。
         */
        val DEFAULT_WORDS: Set<String> = setOf(
            "法轮功", "六合彩", "摇头丸", "冰毒", "海洛因",
            "枪支弹药", "买卖枪支", "自杀教程", "炸药配方"
        )
    }

    /** 归一化后的词表（去空白、ASCII 小写、剔除空词） */
    private val normalizedWords: List<String> = words
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    /** 文本是否命中任一敏感词（大小写不敏感）。 */
    fun check(text: String): Boolean {
        if (text.isEmpty() || normalizedWords.isEmpty()) return false
        val lower = text.lowercase()
        return normalizedWords.any { lower.contains(it) }
    }

    /** 将命中的敏感词替换为等长 `*`，未命中时返回原文本。 */
    fun sanitize(text: String): String {
        if (text.isEmpty() || normalizedWords.isEmpty()) return text
        // 替换为等长 `*` 不改变长度，命中位置始终基于同一份小写副本计算
        val lower = text.lowercase()
        var builder: StringBuilder? = null
        for (word in normalizedWords) {
            var from = 0
            while (true) {
                val hit = lower.indexOf(word, from)
                if (hit < 0) break
                if (builder == null) builder = StringBuilder(text)
                for (i in hit until hit + word.length) {
                    builder.setCharAt(i, '*')
                }
                from = hit + word.length
            }
        }
        return builder?.toString() ?: text
    }
}
