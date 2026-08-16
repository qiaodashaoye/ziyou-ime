package com.ziyou.ime.ai

import android.content.Context
import android.util.Log
import com.ziyou.ime.ai.knowledge.AiMemoryStore
import com.ziyou.ime.ai.knowledge.AiUsageStats
import com.ziyou.ime.ai.knowledge.KnowledgeRepository
import com.ziyou.ime.ai.knowledge.KnowledgeSearcher
import com.ziyou.ime.core.ai.PolishResultParser
import com.ziyou.ime.core.ai.PolishVariant
import com.ziyou.ime.core.rag.RagPromptBuilder
import com.ziyou.ime.core.rag.RetrievedChunk
import com.ziyou.ime.core.rag.SensitiveWordFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 问答/润色编排器：面板的唯一业务入口。
 *
 * 职责：检索源决策 → RAG 检索 → system prompt 融合 → 发起请求 → 结果整形；
 * 面板层（AiPanelView）只保留 UI 与输入路由，不持有业务决策。
 *
 * 两种面板（对应工具栏两个独立入口）：
 * - [ask]    问答面板：检索由当前人设的知识库绑定驱动（强绑定：
 *            仅检索 persona.knowledgeItemIds 范围内条目，无绑定则纯问答），
 *            检索失败降级为纯人设问答（错误隔离）；
 * - [polish] 润色面板：同样按人设绑定范围检索（打分阶段过滤），检索结果
 *            仅作风格参照注入 [RagPromptBuilder.buildPolish]。
 * 润色内容不落盘（隐私纪律，见 AiMemoryStore 注释）。
 *
 * 纯 suspend 编排，无 View 依赖。
 */
object AiChatOrchestrator {

    private const val TAG = "AiChatOrchestrator"

    /** 回答/候选内容安全过滤器（内置最小词表，与知识库导入侧一致） */
    private val answerFilter = SensitiveWordFilter(SensitiveWordFilter.DEFAULT_WORDS)

    /** 问答结果：[chunks] 非空时面板渲染「参考:[1]…」引用行。 */
    data class AskOutcome(
        val answer: String,
        val chunks: List<RetrievedChunk>
    )

    /** 润色结果：候选列表（已清洗）+ 引用来源名；失败时 [error] 非空。 */
    data class PolishOutcome(
        val variants: List<PolishVariant>,
        val sources: List<String>,
        val error: String? = null
    )

    /**
     * 问答面板：人设绑定库检索 → prompt 融合 → 请求。
     *
     * 知识库与人设强绑定：仅当 [persona] 绑定了知识条目时才检索，
     * 且只在绑定范围内检索；无绑定即纯人设问答。
     *
     * @param persona 当前人设（其 systemPrompt 恒注入）
     * @param history 多轮历史（调用方已追加本轮 user，内部 dropLast 处理）
     * @return 成功返回答案与命中 chunk；失败经 [Result.failure] 传递用户可读消息
     */
    suspend fun ask(
        context: Context,
        persona: AiPersona,
        question: String,
        history: List<ChatMessage>
    ): Result<AskOutcome> {
        val appContext = context.applicationContext
        val plainPrompt = AiChatClient.BASE_SYSTEM_PROMPT + "\n\n" + persona.systemPrompt
        val scopedIds = persona.knowledgeItemIds.takeIf { it.isNotEmpty() }?.toSet()
        var retrieved: List<RetrievedChunk> = emptyList()
        val systemPrompt = withContext(Dispatchers.IO) {
            try {
                if (scopedIds != null) {
                    KnowledgeSearcher.ensureLoaded(appContext)
                    retrieved = KnowledgeSearcher.retrieve(
                        question, KnowledgeRepository.getTopK(appContext), scopedIds)
                }
                if (retrieved.isNotEmpty()) {
                    RagPromptBuilder.build(
                        AiChatClient.BASE_SYSTEM_PROMPT,
                        persona.systemPrompt,
                        AiMemoryStore.loadSummary(appContext, persona.id),
                        retrieved
                    )
                } else plainPrompt
            } catch (e: Exception) {
                Log.w(TAG, "知识库检索失败，降级为普通问答: ${e.message}")
                retrieved = emptyList()
                plainPrompt
            }
        }
        AiUsageStats.recordQuestion(appContext, retrieved.size)
        return AiChatClient.ask(
            appContext, question, systemPrompt,
            history.dropLast(1)  // 刚追加的 user 由 buildRequestBody 末尾加入
        ).map { answer ->
            AiUsageStats.recordSuccess(appContext)
            AskOutcome(answerFilter.sanitize(answer), retrieved)
        }.onFailure {
            AiUsageStats.recordFailure(appContext)
        }
    }

    /**
     * 润色面板：人设绑定库检索 → 润色 prompt 融合 → 请求 → 候选解析。
     *
     * @param persona  当前人设；knowledgeItemIds 非空时仅在其范围内检索
     * @param draft    用户草稿原文
     * @param feedback 重新润色时的调整要求（首轮为 null）
     * @param history  迭代润色历史（user=原文/调整要求，assistant=上轮候选）
     */
    suspend fun polish(
        context: Context,
        persona: AiPersona,
        draft: String,
        feedback: String?,
        history: List<ChatMessage>
    ): PolishOutcome {
        val appContext = context.applicationContext
        val scopedIds = persona.knowledgeItemIds.takeIf { it.isNotEmpty() }?.toSet()
        var chunks: List<RetrievedChunk> = emptyList()
        val systemPrompt = withContext(Dispatchers.IO) {
            try {
                if (scopedIds != null) {
                    KnowledgeSearcher.ensureLoaded(appContext)
                    // 检索 query = 草稿 + 人设名扩词（白话草稿与文言语料
                    // 词汇重合度低时借角色名提升召回）
                    chunks = KnowledgeSearcher.retrieve(
                        "$draft ${persona.name}", KnowledgeSearcher.POLISH_TOP_K, scopedIds)
                }
                RagPromptBuilder.buildPolish(
                    AiChatClient.BASE_SYSTEM_PROMPT, persona.systemPrompt, chunks)
            } catch (e: Exception) {
                Log.w(TAG, "人设知识库检索失败，降级为纯人设润色: ${e.message}")
                chunks = emptyList()
                RagPromptBuilder.buildPolish(
                    AiChatClient.BASE_SYSTEM_PROMPT, persona.systemPrompt, emptyList())
            }
        }
        val userContent = if (feedback.isNullOrBlank()) draft
        else "原文：$draft\n调整要求：$feedback"
        AiUsageStats.recordQuestion(appContext, chunks.size)
        return AiChatClient.ask(appContext, userContent, systemPrompt, history).fold(
            onSuccess = { answer ->
                AiUsageStats.recordSuccess(appContext)
                val variants = PolishResultParser.parse(answer)
                    .map { it.copy(text = answerFilter.sanitize(it.text)) }
                PolishOutcome(variants, chunks.map { it.sourceName }.distinct())
            },
            onFailure = { e ->
                Log.w(TAG, "润色请求失败: ${e.message}")
                AiUsageStats.recordFailure(appContext)
                PolishOutcome(emptyList(), emptyList(), e.message ?: "润色失败，请稍后重试")
            }
        )
    }
}
