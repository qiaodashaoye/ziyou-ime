package com.ziyou.ime.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VoiceUtteranceBuffer] 行为测试：partial/final 双路隔离与 drain 语义。 */
class VoiceUtteranceBufferTest {

    private val buffer = VoiceUtteranceBuffer()

    @Test
    fun `初始状态为空`() {
        assertEquals("", buffer.preview())
        assertEquals("", buffer.drainConfirmed())
        assertFalse(buffer.hasPendingConfirmed())
        assertEquals("", buffer.currentPartial())
    }

    @Test
    fun `partial 只进预览不产生上屏增量`() {
        assertTrue(buffer.updatePartial("今天"))
        assertEquals("今天", buffer.preview())
        assertFalse(buffer.hasPendingConfirmed())
        assertEquals("", buffer.drainConfirmed())
    }

    @Test
    fun `重复 partial 返回 false 便于跳过重绘`() {
        assertTrue(buffer.updatePartial("你好"))
        assertFalse(buffer.updatePartial("你好"))
        // 首尾空白视为相同内容
        assertFalse(buffer.updatePartial("  你好  "))
    }

    @Test
    fun `commitSegment 落段后 partial 清零`() {
        buffer.updatePartial("你好")
        assertTrue(buffer.commitSegment("你好"))
        assertEquals("", buffer.currentPartial())
        assertEquals("你好", buffer.preview())
    }

    @Test
    fun `空白段落被忽略`() {
        assertFalse(buffer.commitSegment(""))
        assertFalse(buffer.commitSegment("   "))
        assertFalse(buffer.hasPendingConfirmed())
    }

    @Test
    fun `预览为已确认段与 partial 拼接`() {
        buffer.commitSegment("今天天气")
        buffer.updatePartial("不错")
        assertEquals("今天天气不错", buffer.preview())
    }

    @Test
    fun `drain 取走增量后不重复投递`() {
        buffer.commitSegment("第一句")
        buffer.commitSegment("第二句")
        assertEquals("第一句第二句", buffer.drainConfirmed())
        assertEquals("", buffer.drainConfirmed())
        assertFalse(buffer.hasPendingConfirmed())
    }

    @Test
    fun `drain 只取增量不影响后续段落`() {
        buffer.commitSegment("甲")
        assertEquals("甲", buffer.drainConfirmed())
        buffer.commitSegment("乙")
        assertTrue(buffer.hasPendingConfirmed())
        assertEquals("乙", buffer.drainConfirmed())
        // 预览仍保留全部历史（上屏进度不影响面板展示）
        assertEquals("甲乙", buffer.preview())
    }

    @Test
    fun `reset 清空全部状态`() {
        buffer.commitSegment("已确认")
        buffer.updatePartial("进行中")
        buffer.drainConfirmed()
        buffer.reset()
        assertEquals("", buffer.preview())
        assertEquals("", buffer.currentPartial())
        assertEquals("", buffer.drainConfirmed())
        assertFalse(buffer.hasPendingConfirmed())
    }
}
