package com.ziyou.ime.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TextChunker] 单元测试：段落边界 / 短段合并 / 超长段切分 / 边界情形。 */
class TextChunkerTest {

    @Test
    fun `空文本与纯空白返回空列表`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("  \n\n  \t ").isEmpty())
    }

    @Test
    fun `单个短段落原样成块`() {
        val chunks = TextChunker.chunk("这是一个短段落。")
        assertEquals(listOf("这是一个短段落。"), chunks)
    }

    @Test
    fun `短段落合并至接近上限`() {
        val p1 = "第一段。"
        val p2 = "第二段。"
        val chunks = TextChunker.chunk("$p1\n\n$p2", maxChars = 100)
        // 两个短段落应合并为一块（换行拼接）
        assertEquals(1, chunks.size)
        assertEquals("$p1\n$p2", chunks[0])
    }

    @Test
    fun `合并超限时另起一块`() {
        val p1 = "一".repeat(60)
        val p2 = "二".repeat(60)
        val chunks = TextChunker.chunk("$p1\n\n$p2", maxChars = 100)
        assertEquals(2, chunks.size)
        assertEquals(p1, chunks[0])
        assertEquals(p2, chunks[1])
    }

    @Test
    fun `超长段落按句末标点切分`() {
        val sentence = "这是句子。".repeat(30)  // 150 字符
        val chunks = TextChunker.chunk(sentence, maxChars = 60, overlap = 10)
        assertTrue(chunks.size > 1)
        // 每块不超过上限，且句末边界切分应以句号结尾
        chunks.forEach { assertTrue("块长 ${it.length} 超限", it.length <= 60) }
        assertTrue(chunks[0].endsWith("。"))
    }

    @Test
    fun `无标点超长段硬切且所有内容被覆盖`() {
        val text = "字".repeat(500)
        val chunks = TextChunker.chunk(text, maxChars = 100, overlap = 20)
        assertTrue(chunks.size >= 5)
        chunks.forEach { assertTrue(it.length <= 100) }
        // 总覆盖长度（去重叠后）不少于原文
        assertTrue(chunks.sumOf { it.length } >= text.length)
    }

    @Test
    fun `所有块非空且已trim`() {
        val chunks = TextChunker.chunk("  段落甲  \n\n\n  段落乙  \n\n")
        chunks.forEach {
            assertTrue(it.isNotEmpty())
            assertEquals(it, it.trim())
        }
    }
}
