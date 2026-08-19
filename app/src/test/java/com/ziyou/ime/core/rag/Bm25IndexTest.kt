package com.ziyou.ime.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Bm25Index] 单元测试：相关性排序 / 多 term / 无命中 / topK 截断
 * + docFilter 子集检索（过滤等价于子库独立建索引的守护用例）。
 */
class Bm25IndexTest {

    private fun buildIndex(vararg docs: String): Bm25Index {
        val index = Bm25Index()
        docs.forEachIndexed { id, text ->
            index.addDocument(id, BigramTokenizer.tokenize(text))
        }
        return index
    }

    @Test
    fun `命中term的文档排名靠前`() {
        val index = buildIndex(
            "今天天气很好适合出门散步",       // 0
            "输入法引擎使用倒排索引检索知识",  // 1
            "知识库检索依赖倒排索引与打分"    // 2
        )
        val results = index.search(BigramTokenizer.tokenize("倒排索引"), topK = 3)
        assertTrue(results.isNotEmpty())
        // 文档 0 与查询无关，不应出现在结果中
        assertTrue(results.none { it.docId == 0 })
        // 命中文档得分为正
        results.forEach { assertTrue(it.score > 0) }
    }

    @Test
    fun `多term查询累积打分`() {
        val index = buildIndex(
            "苹果是一种水果",           // 0：仅命中「苹果」
            "苹果手机是电子产品",       // 1：命中「苹果」+「手机」
            "手机可以用来打电话"        // 2：仅命中「手机」
        )
        val results = index.search(BigramTokenizer.tokenize("苹果手机"), topK = 3)
        // 双命中的文档 1 应排第一
        assertEquals(1, results.first().docId)
    }

    @Test
    fun `无命中返回空列表`() {
        val index = buildIndex("知识库内容", "另一段内容")
        assertTrue(index.search(BigramTokenizer.tokenize("hello"), topK = 5).isEmpty())
    }

    @Test
    fun `空索引与空查询返回空列表`() {
        val empty = Bm25Index()
        assertTrue(empty.search(listOf("任意"), topK = 5).isEmpty())
        val index = buildIndex("知识库内容")
        assertTrue(index.search(emptyList(), topK = 5).isEmpty())
    }

    @Test
    fun `topK截断且按分降序`() {
        val docs = Array(10) { "检索测试文档编号${it}号" }
        val index = buildIndex(*docs)
        val results = index.search(BigramTokenizer.tokenize("检索测试"), topK = 3)
        assertEquals(3, results.size)
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score)
        }
    }

    @Test
    fun `空token文档被忽略`() {
        val index = Bm25Index()
        index.addDocument(0, emptyList())
        assertEquals(0, index.documentCount)
    }

    // ===== docFilter 子集检索（人设绑定专属知识库） =====

    /** 诗词语料索引（docFilter 用例专用）：0 李白静夜思 / 1 苏轼水调歌头 / 2 王之涣登鹳雀楼。 */
    private fun buildPoetryIndex(): Bm25Index = buildIndex(
        "床前明月光疑是地上霜",
        "明月几时有把酒问青天",
        "白日依山尽黄河入海流"
    )

    @Test
    fun `docFilter仅保留集合内文档`() {
        val index = buildPoetryIndex()
        // 查询「明月」命中 doc0 与 doc1；过滤后只剩 doc1
        val results = index.search(BigramTokenizer.tokenize("明月"), 3, docFilter = setOf(1))
        assertEquals(1, results.size)
        assertEquals(1, results[0].docId)
    }

    @Test
    fun `docFilter为空集直接返回空`() {
        assertTrue(buildPoetryIndex()
            .search(BigramTokenizer.tokenize("明月"), 3, docFilter = emptySet()).isEmpty())
    }

    @Test
    fun `docFilter为null等价于全库检索`() {
        val index = buildPoetryIndex()
        assertEquals(
            index.search(BigramTokenizer.tokenize("明月"), 3),
            index.search(BigramTokenizer.tokenize("明月"), 3, docFilter = null)
        )
    }

    @Test
    fun `子集过滤命中集合等价于子库独立建索引`() {
        // 守护「打分阶段过滤」语义：全库索引 + docFilter 与仅含子集文档的
        // 独立索引，二者命中文档集合一致（IDF 统计口径不同允许分数差异）
        val full = buildPoetryIndex()
        val sub = buildIndex(
            "明月几时有把酒问青天",   // sub 内 docId=0（对应全库 doc1）
            "白日依山尽黄河入海流"    // sub 内 docId=1（对应全库 doc2）
        )
        val filtered = full.search(BigramTokenizer.tokenize("明月"), 2, docFilter = setOf(1, 2))
        val standalone = sub.search(BigramTokenizer.tokenize("明月"), 2)
        // 两侧均仅命中「明月」所在文档
        assertEquals(1, standalone.size)
        assertEquals(1, filtered.size)
        assertEquals(1, filtered[0].docId)
    }

    @Test
    fun `过滤集合内无命中term时返回空而非越界`() {
        // doc2 不含「明月」：过滤到 doc2 后应空返回
        assertTrue(buildPoetryIndex()
            .search(BigramTokenizer.tokenize("明月"), 3, docFilter = setOf(2)).isEmpty())
    }
}
