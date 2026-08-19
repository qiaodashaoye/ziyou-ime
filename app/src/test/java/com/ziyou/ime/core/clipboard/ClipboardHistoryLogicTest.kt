package com.ziyou.ime.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴板历史纯逻辑测试：头插排序 / 去重 / 容量裁剪 / 文本清洗 /
 * 编解码往返与容错 / 相对时间格式化。
 */
class ClipboardHistoryLogicTest {

    @Test
    fun `头插新条目且最新在前`() {
        var entries = ClipboardHistoryLogic.addEntry(emptyList(), "第一条", 1000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "第二条", 2000L)
        assertEquals(listOf("第二条", "第一条"), entries.map { it.text })
        assertEquals(2000L, entries[0].timestamp)
    }

    @Test
    fun `空白文本拒收`() {
        val base = ClipboardHistoryLogic.addEntry(emptyList(), "内容", 1000L)
        assertEquals(base, ClipboardHistoryLogic.addEntry(base, "", 2000L))
        assertEquals(base, ClipboardHistoryLogic.addEntry(base, "   \n\t", 3000L))
    }

    @Test
    fun `同文本去重并携新时间戳置顶`() {
        var entries = ClipboardHistoryLogic.addEntry(emptyList(), "重复", 1000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "其他", 2000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "重复", 3000L)
        assertEquals(listOf("重复", "其他"), entries.map { it.text })
        assertEquals(3000L, entries[0].timestamp)
        assertEquals(2, entries.size)
    }

    @Test
    fun `与头条同文本视为重复捕获返回原列表`() {
        val base = ClipboardHistoryLogic.addEntry(emptyList(), "头条", 1000L)
        // 重复捕获不刷时间戳（返回同一列表，仓库层据此跳过落盘）
        assertEquals(base, ClipboardHistoryLogic.addEntry(base, "头条", 9000L))
        assertEquals(1000L, ClipboardHistoryLogic.addEntry(base, "头条", 9000L)[0].timestamp)
    }

    @Test
    fun `超出容量裁掉最旧条目`() {
        var entries = emptyList<ClipboardEntry>()
        for (i in 1..12) {
            entries = ClipboardHistoryLogic.addEntry(entries, "条目$i", i.toLong())
        }
        assertEquals(ClipboardHistoryLogic.MAX_ENTRIES, entries.size)
        assertEquals("条目12", entries.first().text)
        assertEquals("条目3", entries.last().text)
    }

    @Test
    fun `超长文本截断并剥离控制符`() {
        val longText = "a".repeat(ClipboardHistoryLogic.MAX_TEXT_LENGTH + 100)
        val entries = ClipboardHistoryLogic.addEntry(emptyList(), longText, 1000L)
        assertEquals(ClipboardHistoryLogic.MAX_TEXT_LENGTH, entries[0].text.length)

        val dirty = "前\u001E中\u001F后"
        val cleaned = ClipboardHistoryLogic.addEntry(emptyList(), dirty, 2000L)
        assertEquals("前中后", cleaned[0].text)
    }

    @Test
    fun `按时间戳删除单条且幂等`() {
        var entries = ClipboardHistoryLogic.addEntry(emptyList(), "甲", 1000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "乙", 2000L)
        val removed = ClipboardHistoryLogic.removeEntry(entries, 1000L)
        assertEquals(listOf("乙"), removed.map { it.text })
        // 再删同一时间戳为空操作
        assertEquals(removed, ClipboardHistoryLogic.removeEntry(removed, 1000L))
    }

    @Test
    fun `编解码往返一致含换行与emoji`() {
        var entries = ClipboardHistoryLogic.addEntry(emptyList(), "多行\n文本\t带符号,;|", 1000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "表情😀🎉 emoji", 2000L)
        entries = ClipboardHistoryLogic.addEntry(entries, "https://example.com?a=1&b=2", 3000L)
        val decoded = ClipboardHistoryLogic.decode(ClipboardHistoryLogic.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `空列表编解码往返`() {
        assertEquals("", ClipboardHistoryLogic.encode(emptyList()))
        assertEquals(emptyList<ClipboardEntry>(), ClipboardHistoryLogic.decode(""))
    }

    @Test
    fun `损坏数据逐条跳过不抛异常`() {
        val good = "1000\u001F正常条目"
        val noSeparator = "2000缺字段分隔"
        val badTimestamp = "abc\u001F时间戳非法"
        val blankText = "3000\u001F   "
        val raw = listOf(good, noSeparator, badTimestamp, blankText).joinToString("\u001E")
        val decoded = ClipboardHistoryLogic.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(ClipboardEntry("正常条目", 1000L), decoded[0])
    }

    @Test
    fun `相对时间格式化边界`() {
        val now = 1_000_000_000_000L
        assertEquals("刚刚", ClipboardHistoryLogic.formatRelativeTime(now, now))
        assertEquals("刚刚", ClipboardHistoryLogic.formatRelativeTime(now - 59_999L, now))
        assertEquals("1分钟前", ClipboardHistoryLogic.formatRelativeTime(now - 60_000L, now))
        assertEquals("59分钟前", ClipboardHistoryLogic.formatRelativeTime(now - 3_599_999L, now))
        assertEquals("1小时前", ClipboardHistoryLogic.formatRelativeTime(now - 3_600_000L, now))
        assertEquals("23小时前", ClipboardHistoryLogic.formatRelativeTime(now - 86_399_999L, now))
        assertEquals("1天前", ClipboardHistoryLogic.formatRelativeTime(now - 86_400_000L, now))
        // 时钟回拨兜底
        assertEquals("刚刚", ClipboardHistoryLogic.formatRelativeTime(now + 10_000L, now))
    }

    @Test
    fun `解码超容量数据按上限裁剪`() {
        val raw = (1..15).joinToString("\u001E") { "$it\u001F条目$it" }
        val decoded = ClipboardHistoryLogic.decode(raw)
        assertTrue(decoded.size == ClipboardHistoryLogic.MAX_ENTRIES)
    }
}
