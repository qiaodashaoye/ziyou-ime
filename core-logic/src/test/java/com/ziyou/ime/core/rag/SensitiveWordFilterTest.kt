package com.ziyou.ime.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SensitiveWordFilter] 单元测试：命中检测 / 替换 / 大小写 / 边界。 */
class SensitiveWordFilterTest {

    private val filter = SensitiveWordFilter(setOf("违禁词", "badword"))

    @Test
    fun `命中敏感词返回true`() {
        assertTrue(filter.check("这段文字包含违禁词内容"))
    }

    @Test
    fun `未命中返回false`() {
        assertFalse(filter.check("这是一段正常内容"))
    }

    @Test
    fun `命中词替换为等长星号`() {
        assertEquals("前缀***后缀", filter.sanitize("前缀违禁词后缀"))
    }

    @Test
    fun `多次出现全部替换`() {
        assertEquals("******中间***", filter.sanitize("违禁词违禁词中间违禁词"))
    }

    @Test
    fun `英文敏感词大小写不敏感`() {
        assertTrue(filter.check("contains BadWord here"))
        assertEquals("contains ******* here", filter.sanitize("contains BadWord here"))
    }

    @Test
    fun `未命中时sanitize返回原文本`() {
        val text = "正常内容"
        assertEquals(text, filter.sanitize(text))
    }

    @Test
    fun `空文本与空词表安全处理`() {
        assertFalse(filter.check(""))
        assertEquals("", filter.sanitize(""))
        val emptyFilter = SensitiveWordFilter(emptySet())
        assertFalse(emptyFilter.check("任意内容"))
        assertEquals("任意内容", emptyFilter.sanitize("任意内容"))
    }

    @Test
    fun `默认词表非空可用`() {
        val defaultFilter = SensitiveWordFilter(SensitiveWordFilter.DEFAULT_WORDS)
        assertTrue(SensitiveWordFilter.DEFAULT_WORDS.isNotEmpty())
        assertFalse(defaultFilter.check("今天天气不错"))
    }
}
