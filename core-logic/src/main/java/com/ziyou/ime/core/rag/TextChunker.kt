package com.ziyou.ime.core.rag

/**
 * 知识文本分块器
 *
 * 将导入的原始文本切成适合检索与 prompt 注入的块（chunk）：
 * - 优先按空行（段落边界）切分，保持语义完整；
 * - 相邻短段落合并至接近 [DEFAULT_MAX_CHARS]，避免碎块拉低检索质量；
 * - 超长段落按句末标点（。！？；.!?;）与换行二次切分，硬上限兜底强切；
 * - 强切时携带 [DEFAULT_OVERLAP] 字符的重叠，降低句子被腰斩导致的召回丢失。
 *
 * 纯函数无状态，供 :app 导入流水线调用。
 */
object TextChunker {

    /** 单块字符上限（经验值：约 200~300 汉字，兼顾检索粒度与 prompt 预算） */
    const val DEFAULT_MAX_CHARS = 400

    /** 硬切分时相邻块的重叠字符数 */
    const val DEFAULT_OVERLAP = 80

    /** 句末切分标点 */
    private val SENTENCE_ENDINGS = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '\n')

    /**
     * 将文本分块。空白文本返回空列表；返回的每个 chunk 均已 trim 且非空。
     */
    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlap: Int = DEFAULT_OVERLAP
    ): List<String> {
        require(maxChars > 0) { "maxChars 必须为正" }
        require(overlap in 0 until maxChars) { "overlap 必须小于 maxChars" }
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.toString().trim())
                buffer.setLength(0)
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > maxChars) {
                // 超长段落独立处理：先冲刷缓冲，再按句子边界切分
                flushBuffer()
                chunks.addAll(splitLongParagraph(paragraph, maxChars, overlap))
            } else if (buffer.length + paragraph.length + 1 > maxChars) {
                // 合并会超限：先冲刷再另起一块
                flushBuffer()
                buffer.append(paragraph)
            } else {
                // 短段落合并（以换行拼接保留段落感）
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(paragraph)
            }
        }
        flushBuffer()
        return chunks.filter { it.isNotEmpty() }
    }

    /** 超长段落切分：句末标点优先，找不到边界时硬切并附带 overlap。 */
    private fun splitLongParagraph(paragraph: String, maxChars: Int, overlap: Int): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < paragraph.length) {
            var end = minOf(start + maxChars, paragraph.length)
            if (end < paragraph.length) {
                // 在 [start+maxChars/2, end) 内回找最近的句末标点，避免切得过碎
                val searchFrom = start + maxChars / 2
                val boundary = paragraph.lastIndexOfAny(SENTENCE_ENDINGS, end - 1)
                if (boundary >= searchFrom) {
                    end = boundary + 1
                }
            }
            val piece = paragraph.substring(start, end).trim()
            if (piece.isNotEmpty()) pieces.add(piece)
            if (end >= paragraph.length) break
            // 仅硬切（未命中句末边界）时回退 overlap 保留上下文
            val boundaryHit = SENTENCE_ENDINGS.contains(paragraph[end - 1])
            start = if (boundaryHit) end else maxOf(end - overlap, start + 1)
        }
        return pieces
    }
}
