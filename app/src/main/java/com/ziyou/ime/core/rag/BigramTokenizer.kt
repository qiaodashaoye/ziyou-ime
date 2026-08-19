package com.ziyou.ime.core.rag

/**
 * 二元组（bigram）分词器
 *
 * 面向本地 BM25 检索的轻量分词方案，无词典、无第三方依赖：
 * - CJK 连续段按相邻二字组合切分（"知识库" → ["知识", "识库"]），
 *   单字段落退化为单字 term，保证短查询仍可命中；
 * - 连续 ASCII 字母/数字段按整词切分并统一小写（"BM25 Index" → ["bm25", "index"]）；
 * - 标点、空白与其他符号视为分隔符，直接忽略。
 *
 * 纯函数无状态，索引构建与查询两侧共用，保证 term 空间一致。
 */
object BigramTokenizer {

    /** 将文本切分为检索 term 列表（保留重复，词频信息由索引统计）。 */
    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val cjkRun = StringBuilder()
        val asciiRun = StringBuilder()

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            if (cjkRun.length == 1) {
                tokens.add(cjkRun.toString())
            } else {
                for (i in 0 until cjkRun.length - 1) {
                    tokens.add(cjkRun.substring(i, i + 2))
                }
            }
            cjkRun.setLength(0)
        }

        fun flushAscii() {
            if (asciiRun.isEmpty()) return
            tokens.add(asciiRun.toString().lowercase())
            asciiRun.setLength(0)
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushAscii()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() && ch.code < 128 -> {
                    flushCjk()
                    asciiRun.append(ch)
                }
                else -> {
                    flushCjk()
                    flushAscii()
                }
            }
        }
        flushCjk()
        flushAscii()
        return tokens
    }

    /** CJK 统一表意文字判断（含扩展 A 与兼容区，覆盖常用汉字范围）。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||  // CJK 统一表意文字
            (code in 0x3400..0x4DBF) ||     // 扩展 A
            (code in 0xF900..0xFAFF)        // 兼容表意文字
    }
}
