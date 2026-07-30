package com.ziyou.ime.core.rag

import kotlin.math.ln

/**
 * BM25 倒排索引（内存态）
 *
 * 面向知识库 chunk 级检索的标准 Okapi BM25 实现（k1=1.5, b=0.75）：
 * - [addDocument] 逐文档构建倒排表与文档长度统计；
 * - [search] 仅对命中查询 term 的候选文档打分（非全表扫描），返回按分降序 Top-K。
 *
 * 非线程安全：构建阶段由调用方保证单线程；构建完成后只读检索可并发。
 * 索引不持久化，chunk 原文为唯一可信源，重建成本秒级（见知识库模块设计）。
 */
class Bm25Index(
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) {

    /** 倒排表条目：文档 ID + 该 term 在文档中的出现次数 */
    data class Posting(val docId: Int, val termFreq: Int)

    /** 检索结果：文档 ID + BM25 得分 */
    data class ScoredDoc(val docId: Int, val score: Double)

    /** term → 倒排列表 */
    private val invertedIndex = HashMap<String, MutableList<Posting>>()

    /** docId → 文档 token 总数 */
    private val docLengths = HashMap<Int, Int>()

    /** 全库 token 总数（求平均文档长度用） */
    private var totalTokens = 0L

    /** 已索引文档数 */
    val documentCount: Int get() = docLengths.size

    /**
     * 添加一个文档（docId 需唯一，重复添加会污染统计，由调用方保证）。
     * 空 token 列表的文档直接忽略。
     */
    fun addDocument(docId: Int, tokens: List<String>) {
        if (tokens.isEmpty()) return
        docLengths[docId] = tokens.size
        totalTokens += tokens.size
        val termFreqs = HashMap<String, Int>()
        for (token in tokens) {
            termFreqs[token] = (termFreqs[token] ?: 0) + 1
        }
        for ((term, freq) in termFreqs) {
            invertedIndex.getOrPut(term) { mutableListOf() }.add(Posting(docId, freq))
        }
    }

    /**
     * BM25 检索：返回按得分降序的前 [topK] 个文档；查询 token 全部未命中
     * 或索引为空时返回空列表。
     */
    fun search(queryTokens: List<String>, topK: Int): List<ScoredDoc> {
        if (queryTokens.isEmpty() || docLengths.isEmpty() || topK <= 0) return emptyList()
        val n = docLengths.size.toDouble()
        val avgDocLength = totalTokens.toDouble() / n
        val scores = HashMap<Int, Double>()
        // 查询侧按去重 term 打分（查询内重复 term 不额外加权，与常见实现一致）
        for (term in queryTokens.distinct()) {
            val postings = invertedIndex[term] ?: continue
            // IDF 采用带 +1 平滑的标准公式，保证非负
            val idf = ln(1.0 + (n - postings.size + 0.5) / (postings.size + 0.5))
            for ((docId, tf) in postings) {
                val docLength = docLengths[docId] ?: continue
                val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * docLength / avgDocLength))
                scores[docId] = (scores[docId] ?: 0.0) + idf * norm
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { ScoredDoc(it.key, it.value) }
    }
}
