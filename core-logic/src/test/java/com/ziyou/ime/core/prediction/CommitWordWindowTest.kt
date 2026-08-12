package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CommitWordWindow] 单元测试：收录过滤、双上限淘汰、时间序与清空。 */
class CommitWordWindowTest {

    @Test
    fun `正常词按时间序收录`() {
        val window = CommitWordWindow()
        window.add("今天")
        window.add("天气")
        window.add("真好")
        assertEquals(listOf("今天", "天气", "真好"), window.words())
    }

    @Test
    fun `仅空白忽略，标点与符号原样保留`() {
        val window = CommitWordWindow()
        window.add("")
        window.add("   ")
        assertTrue(window.words().isEmpty())
        // 标点携带句法信号：既是续写上下文，也是 AutoPunctPolicy 判定
        // 「前文是否已有标点」的唯一依据，独立标点提交必须入窗
        window.add("你好")
        window.add("，")
        assertEquals(listOf("你好", "，"), window.words())
    }

    @Test
    fun `句末标点原样保留携带句子边界信号`() {
        // 句末标点是续写模型判断「续新句还是接旧句」的关键上下文，不得过滤
        val window = CommitWordWindow()
        window.add("床前明月光")
        window.add("。")
        assertEquals(listOf("床前明月光", "。"), window.words())
        // 全部句末标点集均可入窗；词尾自带标点同样完整保留
        window.clear()
        window.add("真的吗")
        window.add("？")
        window.add("太好了！")
        assertEquals(listOf("真的吗", "？", "太好了！"), window.words())
    }

    @Test
    fun `词首尾空白收录时trim`() {
        val window = CommitWordWindow()
        window.add("  输入法  ")
        assertEquals(listOf("输入法"), window.words())
    }

    @Test
    fun `超过最大词数从最旧淘汰`() {
        val window = CommitWordWindow()
        repeat(CommitWordWindow.MAX_WORDS + 2) { window.add("词$it") }
        val words = window.words()
        assertEquals(CommitWordWindow.MAX_WORDS, words.size)
        // 最旧两个被淘汰，最新词在队尾
        assertEquals("词2", words.first())
        assertEquals("词${CommitWordWindow.MAX_WORDS + 1}", words.last())
    }

    @Test
    fun `总字符数超限从最旧淘汰`() {
        val window = CommitWordWindow()
        // 每词 20 字符：第 4 个词加入后总长 80 > 64，须淘汰最旧至 ≤64
        repeat(4) { window.add("一二三四五六七八九十一二三四五六七八九十") }
        val words = window.words()
        assertTrue(words.sumOf { it.length } <= CommitWordWindow.MAX_TOTAL_CHARS)
        assertEquals(listOf(
            "一二三四五六七八九十一二三四五六七八九十",
            "一二三四五六七八九十一二三四五六七八九十",
            "一二三四五六七八九十一二三四五六七八九十"
        ), words)
    }

    @Test
    fun `单词超过总预算被忽略`() {
        val window = CommitWordWindow()
        window.add("词")
        window.add("一".repeat(CommitWordWindow.MAX_TOTAL_CHARS + 1))
        // 超长单词不入窗，既有内容不受影响
        assertEquals(listOf("词"), window.words())
    }

    @Test
    fun `clear清空窗口`() {
        val window = CommitWordWindow()
        window.add("你好")
        window.add("世界")
        window.clear()
        assertTrue(window.words().isEmpty())
        // 清空后可继续正常收录
        window.add("再见")
        assertEquals(listOf("再见"), window.words())
    }
}
