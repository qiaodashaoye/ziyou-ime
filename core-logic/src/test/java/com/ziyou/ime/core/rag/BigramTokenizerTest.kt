package com.ziyou.ime.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BigramTokenizer] 单元测试：中文 bigram / 英文整词 / 混合 / 边界。 */
class BigramTokenizerTest {

    @Test
    fun `中文按二元组切分`() {
        assertEquals(listOf("知识", "识库"), BigramTokenizer.tokenize("知识库"))
    }

    @Test
    fun `两字中文得到单个二元组`() {
        assertEquals(listOf("输入"), BigramTokenizer.tokenize("输入"))
    }

    @Test
    fun `单字中文退化为单字term`() {
        assertEquals(listOf("好"), BigramTokenizer.tokenize("好"))
    }

    @Test
    fun `英文按整词切分并小写`() {
        assertEquals(listOf("bm25", "index"), BigramTokenizer.tokenize("BM25 Index"))
    }

    @Test
    fun `中英混合各按各自规则切分`() {
        assertEquals(
            listOf("使用", "kotlin", "开发", "发应", "应用"),
            BigramTokenizer.tokenize("使用Kotlin开发应用")
        )
    }

    @Test
    fun `标点作为分隔符被忽略`() {
        assertEquals(
            listOf("你好", "世界"),
            BigramTokenizer.tokenize("你好，世界！")
        )
    }

    @Test
    fun `空串与纯标点返回空列表`() {
        assertTrue(BigramTokenizer.tokenize("").isEmpty())
        assertTrue(BigramTokenizer.tokenize("，。！？ \n\t").isEmpty())
    }
}
