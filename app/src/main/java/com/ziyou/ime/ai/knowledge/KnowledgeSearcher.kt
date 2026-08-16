package com.ziyou.ime.ai.knowledge

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.rag.BigramTokenizer
import com.ziyou.ime.core.rag.Bm25Index
import com.ziyou.ime.core.rag.RetrievedChunk
import com.ziyou.ime.core.rag.Retriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 知识库检索器（BM25 实现，[Retriever] 的本期唯一实现）
 *
 * 内存索引懒构建：仅在「知识库开启且首次检索」时从 chunk 文件加载并构建
 * BM25 倒排索引（IO 线程，Mutex 防并发重复构建）；构建后缓存直到
 * [invalidate]（仓库增删条目时调用）。检索本身为纯内存操作（<50ms 级）。
 *
 * IME 冷启动与输入热路径零参与：不开启知识库时本对象完全不加载。
 */
object KnowledgeSearcher : Retriever {

    private const val TAG = "KnowledgeSearcher"

    /** 润色模式检索 Top-K：参考资料仅作风格参照，过多 chunk 挤占润色输出
     *  token 且诱导模型照抄资料，故比问答路径（[KnowledgeRepository.DEFAULT_TOP_K]）收紧。 */
    const val POLISH_TOP_K = 3

    /** 索引快照：BM25 索引 + docId → (条目, chunk 原文) 映射 + 条目 ID →
     *  docId 集反向表（人设绑定子集检索用），原子替换 */
    private class Snapshot(
        val index: Bm25Index,
        val chunks: List<Pair<KnowledgeItem, String>>,
        val docIdsByItem: Map<String, Set<Int>>
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** 构建互斥锁（防多协程并发重复构建） */
    private val buildMutex = Mutex()

    /**
     * 确保索引已加载（IO 线程构建）。库为空时建立空快照，同样视为已加载。
     * 面板在发起提问前调用；构建失败时保持未加载状态并抛出异常由调用方降级。
     */
    suspend fun ensureLoaded(context: Context) {
        val application = context.applicationContext
        if (snapshot != null) return
        buildMutex.withLock {
            if (snapshot != null) return
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                val items = KnowledgeRepository.getItems(application)
                val allChunks = mutableListOf<Pair<KnowledgeItem, String>>()
                for (item in items) {
                    for (chunk in KnowledgeRepository.loadChunks(application, item.id)) {
                        allChunks += item to chunk
                    }
                }
                val index = Bm25Index()
                val docIdsByItem = HashMap<String, MutableSet<Int>>()
                allChunks.forEachIndexed { docId, (item, text) ->
                    index.addDocument(docId, BigramTokenizer.tokenize(text))
                    docIdsByItem.getOrPut(item.id) { mutableSetOf() }.add(docId)
                }
                snapshot = Snapshot(index, allChunks, docIdsByItem)
                Log.i(TAG, "知识库索引构建完成: ${items.size} 条目 / ${allChunks.size} chunk" +
                    " / ${System.currentTimeMillis() - start}ms")
            }
        }
    }

    /**
     * BM25 检索最相关的 [topK] 个知识块（纯内存，需先 [ensureLoaded]；
     * 未加载或无命中返回空列表，调用方降级为无知识库路径）。
     *
     * [itemIds] 非空时仅在指定条目范围内检索（人设绑定专属知识库）：
     * 经反向表映射为 docId 集后传入 BM25 打分阶段过滤；集合内无任何
     * 已索引文档（条目已删但绑定未清理等）时安全返回空列表。
     */
    override fun retrieve(query: String, topK: Int, itemIds: Set<String>?): List<RetrievedChunk> {
        val snap = snapshot ?: return emptyList()
        if (snap.chunks.isEmpty()) return emptyList()
        val docFilter: Set<Int>? = itemIds?.let { ids ->
            val docIds = mutableSetOf<Int>()
            for (id in ids) {
                snap.docIdsByItem[id]?.let { docIds.addAll(it) }
            }
            if (docIds.isEmpty()) return emptyList()
            docIds
        }
        val queryTokens = BigramTokenizer.tokenize(query)
        return snap.index.search(queryTokens, topK, docFilter).map { scored ->
            val (item, text) = snap.chunks[scored.docId]
            RetrievedChunk(
                text = text,
                sourceName = item.name,
                itemId = item.id,
                score = scored.score
            )
        }
    }

    /** 全库检索（旧签名保留，等价于不传 itemIds）。 */
    override fun retrieve(query: String, topK: Int): List<RetrievedChunk> =
        retrieve(query, topK, null)

    /** 失效缓存（知识条目增删改后调用），下次检索时重新构建。 */
    fun invalidate() {
        snapshot = null
    }
}
