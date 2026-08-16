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
        appendReferenceBlock(builder, chunks, "【参考资料】", instruction)
    }

    /**
     * 构建「润色任务」的 system prompt（人设润色模式专用）。
     *
     * 拼接顺序：格式约束 → 人设 → 【风格参考资料】（chunks 非空时，
     * 编号 chunk，仅借鉴风格不要求引用编号）→ 【润色任务】指令。
     * 同样受 [MAX_PROMPT_CHARS] 预算约束。
     *
     * @param basePrompt    基础格式约束（AiChatClient.BASE_SYSTEM_PROMPT）
     * @param personaPrompt 人设提示词（定义角色身份与语言风格）
     * @param chunks        人设绑定知识库的检索结果（按分数降序，可为空）
     * @return 润色任务 system prompt；输出格式约束与
     *         [com.ziyou.ime.core.ai.PolishResultParser] 的解析规则严格对齐
     */
    fun buildPolish(
        basePrompt: String,
        personaPrompt: String,
        chunks: List<RetrievedChunk> = emptyList()
    ): String {
        val builder = StringBuilder()
        builder.append(basePrompt)
        if (personaPrompt.isNotBlank()) {
            builder.append("\n\n").append(personaPrompt)
        }
        if (chunks.isNotEmpty()) {
            val instruction = "\n\n上述资料仅作为你的语言风格、意象与知识背景参照，" +
                "润色时可借鉴其风格与用词，不得大段抄袭或与原文无关地引用。"
            appendReferenceBlock(builder, chunks, "【风格参考资料】", instruction)
        }
        builder.append("\n\n").append(POLISH_TASK_INSTRUCTION)
        return builder.toString()
    }

    /** 润色任务指令：原意保留底线 + 严格编号输出格式（解析器依赖）。 */
    private const val POLISH_TASK_INSTRUCTION: String =
        "【润色任务】\n" +
        "你正在执行「文本润色」：请以你的角色口吻改写用户提供的原文。\n" +
        "1. 保留原文的核心意思与关键信息，不得增删事实；\n" +
        "2. 语气、用词、句式须符合你的角色设定；\n" +
        "3. 输出 2~3 个改写版本，格式严格为：\n" +
        "   1. <版本一文本>（风格说明，不超过15字）\n" +
        "   2. <版本二文本>（风格说明）\n" +
        "   每个版本一行，版本内不要换行，不要使用 Markdown 格式；\n" +
        "4. 除编号版本外不要输出任何其他内容。"

    /** 通用参考资料区块追加：编号 chunk + 指令，超预算截断（问答/润色共用）。 */
    private fun appendReferenceBlock(
        builder: StringBuilder,
        chunks: List<RetrievedChunk>,
        headerName: String,
        instruction: String
    ) {
        val header = "\n\n$headerName\n"
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
