package com.ziyou.ime.ai

/**
 * AI 人设数据模型
 *
 * 描述一个 AI 角色的身份与行为风格，其 [systemPrompt] 会在每次对话
 * 请求中拼接于 [AiChatClient.BASE_SYSTEM_PROMPT] 之后，作为 messages[0]
 * （system role）传递给 LLM，从而在会话全程维持一致的人物性格。
 *
 * 内置人设（[isBuiltin] = true）由应用硬编码，不可删除，但可被同名自定义人设覆盖；
 * 用户可在设置页新建、编辑、删除自定义人设（[isBuiltin] = false）。
 *
 * 人设的核心用途是「润色」：键盘 AI 面板润色模式下，用户草稿不直接上屏，
 * 而是经 LLM 按人设口吻改写为候选句供用户选择上屏；绑定知识库的人设在
 * 润色时会先检索专属语料注入 prompt 作为风格参照（RAG）。
 *
 * @property id          唯一标识；内置人设以 "builtin_" 前缀命名
 * @property name        角色名称（列表展示、标题栏标签）
 * @property description 角色简介（设置页列表次行说明）
 * @property systemPrompt 系统提示词（注入 LLM system message，定义角色与风格）
 * @property isBuiltin   是否为内置人设（true 时不可删除）
 * @property knowledgeItemIds 绑定的知识条目 ID 列表（KnowledgeItem.id）；空 = 无专属
 *                            知识。仅自定义人设可绑定；条目删除时由
 *                            [PersonaRepository.purgeKnowledgeRefs] 反向清理引用
 */
data class AiPersona(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val isBuiltin: Boolean = false,
    val knowledgeItemIds: List<String> = emptyList()
) {

    companion object {

        /** 智能助手：通用问答，简洁专业（当前默认行为的超集） */
        val ASSISTANT = AiPersona(
            id = "builtin_assistant",
            name = "智能助手",
            description = "通用问答，简洁专业",
            systemPrompt = "你是输入法内置的 AI 助手，请用简体中文简明扼要地回答问题。" +
                "回答应准确、实用，优先给出可直接操作的建议。",
            isBuiltin = true
        )

        /** 创意写手：文案、故事、头脑风暴，风格活泼 */
        val CREATIVE = AiPersona(
            id = "builtin_creative",
            name = "创意写手",
            description = "文案创作、故事、头脑风暴",
            systemPrompt = "你是一位充满创意的写作助手，擅长文案创作、故事构思和头脑风暴。" +
                "请用生动活泼的语言激发灵感，多提供不同角度的创意选项，" +
                "风格可以大胆新颖，但要尊重用户指定的题材和基调。",
            isBuiltin = true
        )

        /** 学习导师：解释概念、引导思考、举例说明 */
        val TUTOR = AiPersona(
            id = "builtin_tutor",
            name = "学习导师",
            description = "解释概念、引导思考、举例说明",
            systemPrompt = "你是一位耐心细致的学习导师，擅长用通俗易懂的方式解释复杂概念。" +
                "回答问题时优先用类比和生活实例帮助理解，必要时分步骤拆解，" +
                "引导用户主动思考，而不只是直接给出答案。",
            isBuiltin = true
        )

        /** 翻译官：中英/多语互译，保留原文风格 */
        val TRANSLATOR = AiPersona(
            id = "builtin_translator",
            name = "翻译官",
            description = "中英/多语互译，保留原文风格",
            systemPrompt = "你是一位专业翻译，精通中文、英文及多种语言互译。" +
                "翻译时忠实于原文含义，同时兼顾目标语言的自然表达习惯，保留原文的语气和风格。" +
                "用户输入中文则译为英文，输入英文则译为中文；若为其他语言，请译为中文并标注语种。",
            isBuiltin = true
        )

        /** 娱乐伙伴：轻松幽默，讲段子、推荐影视 */
        val ENTERTAINER = AiPersona(
            id = "builtin_entertainer",
            name = "娱乐伙伴",
            description = "轻松幽默，聊天解闷",
            systemPrompt = "你是一位风趣幽默的聊天伙伴，擅长讲笑话、推荐影视剧和分享有趣的冷知识。" +
                "用轻松愉快的语气回答，适时加入幽默元素，" +
                "但要保持分寸，避免冒犯性内容，用户问正经问题时也要认真回答。",
            isBuiltin = true
        )

        /** 所有内置人设（顺序即列表展示顺序） */
        val BUILTINS: List<AiPersona> = listOf(
            ASSISTANT, CREATIVE, TUTOR, TRANSLATOR, ENTERTAINER
        )

        /** 默认人设 ID（首次使用 / 恢复默认时取此值） */
        const val DEFAULT_PERSONA_ID: String = "builtin_assistant"
    }
}
