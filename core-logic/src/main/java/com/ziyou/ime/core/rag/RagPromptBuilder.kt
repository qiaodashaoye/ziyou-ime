package com.ziyou.ime.core.rag

/**
 * RAG 系统提示词构建器
 *
 * 将格式约束、人设、长期记忆摘要与检索到的知识块融合为最终 system prompt，
 * 经 AiChatClient.ask() 的 systemPrompt 参数传入（不改动客户端签名）。
 *
 * 拼接顺序：格式约束 → 人设 → 【长期记忆】（非空时）→ 【参考资料】编号
 * chunk（按分数序，超预算截断）→ 引用指令。总长度受 [MAX_PROMPT_CHARS]
 * 约束，防止 prompt 过长挤占对话 token。
 */
object RagPromptBuilder {

    /** 最终 system prompt 字符上限 */
    const val MAX_PROMPT_CHARS = 6000

    /**
     * 构建最终 system prompt。
     *
     * @param basePrompt    基础格式约束（AiChatClient.BASE_SYSTEM_PROMPT）
     * @param personaPrompt 人设提示词
     * @param memorySummary 跨会话记忆摘要（空串表示无记忆）
     * @param chunks        检索结果（按分数降序，可为空）
     * @return 融合后的 system prompt；chunks 与 memorySummary 均为空时
     *         退化为与现状一致的 "base + persona" 拼接
     */
    fun build(
        basePrompt: String,
        personaPrompt: String,
        memorySummary: String = "",
        chunks: List<RetrievedChunk> = emptyList()
    ): String {
        val builder = StringBuilder()
        builder.append(basePrompt)
        if (personaPrompt.isNotBlank()) {
            builder.append("\n\n").append(personaPrompt)
        }
        if (memorySummary.isNotBlank()) {
            builder.append("\n\n【长期记忆】\n")
                .append("以下是此前对话的要点摘要，回答时可参考用户的偏好与上下文：\n")
                .append(memorySummary.trim())
        }
        if (chunks.isNotEmpty()) {
            appendReferences(builder, chunks)
        }
        return builder.toString()
    }

    /** 追加【参考资料】区块：编号 chunk + 引用指令，超预算的低分 chunk 丢弃。 */
    private fun appendReferences(builder: StringBuilder, chunks: List<RetrievedChunk>) {
        val instruction = "\n\n请优先基于上述参考资料回答问题，引用资料处标注对应编号（如 [1]）；" +
            "若资料不足以回答，可使用通用知识但需说明。"
        val header = "\n\n【参考资料】\n"
        var budget = MAX_PROMPT_CHARS - builder.length - header.length - instruction.length
        if (budget <= 0) return
        val entries = StringBuilder()
        var index = 1
        for (chunk in chunks) {
            val entry = "[$index]（来源：${chunk.sourceName}）\n${chunk.text.trim()}\n"
            if (entry.length > budget) break
            entries.append(entry)
            budget -= entry.length
            index++
        }
        if (entries.isEmpty()) return
        builder.append(header).append(entries.toString().trimEnd()).append(instruction)
    }
}
