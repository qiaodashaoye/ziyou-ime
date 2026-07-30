package com.ziyou.ime.core.rag

/**
 * 检索到的知识块（chunk 级检索结果）。
 *
 * @param text       chunk 原文（已清洗）
 * @param sourceName 来源名称（文件名 / 自定义文本标题），用于引用标注展示
 * @param itemId     所属知识条目 ID（:app 层 KnowledgeItem.id）
 * @param score      相关性得分（BM25 或未来的向量相似度，仅同一检索器内可比）
 */
data class RetrievedChunk(
    val text: String,
    val sourceName: String,
    val itemId: String,
    val score: Double
)

/**
 * 知识检索器接口
 *
 * RAG 检索层的统一抽象：本期由 :app 的 BM25 检索实现；未来可新增
 * EmbeddingRetriever（向量检索）并以 RRF 等策略融合，调用方（面板 /
 * prompt 构建）无需改动。
 */
interface Retriever {

    /**
     * 按相关性检索最相关的 [topK] 个知识块，按得分降序返回；
     * 无可用知识或无命中时返回空列表（调用方据此降级为无知识库路径）。
     */
    fun retrieve(query: String, topK: Int): List<RetrievedChunk>
}
