package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StreamCandidateText] 单元测试：增量解析、清洗规则与上限语义。
 *
 * 覆盖联想优化方案 §4.7 的关键纪律：仅按换行切分（保句读）、
 * 剥序号前缀与前导标点、任意截断位置的增量等价于整包解析。
 */
class StreamCandidateTextTest {

    @Test
    fun `整段内容按换行切分为多条候选`() {
        val result = StreamCandidateText.parseWhole("疑是地上霜\n低头思故乡。")
        assertEquals(listOf("疑是地上霜", "低头思故乡。"), result)
    }

    @Test
    fun `不得按逗号切分以免丢失句读`() {
        // 句内逗号必须保留在候选内部，不产生碎片
        val result = StreamCandidateText.parseWhole("疑是地上霜，低头思故乡。")
        assertEquals(listOf("疑是地上霜，低头思故乡。"), result)
    }

    @Test
    fun `剥离列表序号前缀`() {
        val result = StreamCandidateText.parseWhole("1. 好的\n② 没问题\n三、 收到")
        assertEquals(listOf("好的", "没问题", "收到"), result)
    }

    @Test
    fun `剥离前导标点但保留句尾标点`() {
        val result = StreamCandidateText.parseWhole("，疑似地上霜\n低头思故乡。")
        assertEquals(listOf("疑似地上霜", "低头思故乡。"), result)
    }

    @Test
    fun `空白与纯标点行被过滤`() {
        val result = StreamCandidateText.parseWhole("\n。。。  \n好的\n\t\n")
        assertEquals(listOf("好的"), result)
    }

    @Test
    fun `整段解析最多五条且单条截断二十字`() {
        val long = "一二三四五六七八九十一二三四五六七八九十一" // 21 字
        val lines = (1..7).joinToString("\n") { "候选$it" } + "\n" + long
        val result = StreamCandidateText.parseWhole(lines)
        assertEquals(StreamCandidateText.MAX_CANDIDATES, result.size)
        val longParsed = StreamCandidateText.parseWhole(long)
        assertEquals(StreamCandidateText.MAX_CANDIDATE_CHARS, longParsed.single().length)
    }

    @Test
    fun `增量分片到达与整包解析结果一致`() {
        val whole = "1. 今天天气\n不错啊\n出去走走吧。"
        val expected = StreamCandidateText.parseWhole(whole)
        // 按任意粒度切片喂入（含词中间截断）
        val parser = StreamCandidateText()
        val collected = ArrayList<String>()
        for (chunk in listOf("1. 今天", "天气\n不", "错啊\n出去", "走走吧。")) {
            collected.addAll(parser.offer(chunk))
        }
        collected.addAll(parser.flush())
        assertEquals(expected, collected)
    }

    @Test
    fun `offer仅在行完整时发射且末行残片留缓冲`() {
        val parser = StreamCandidateText()
        assertEquals(emptyList<String>(), parser.offer("疑似"))
        assertEquals(emptyList<String>(), parser.offer("地上霜"))
        assertEquals(listOf("疑似地上霜"), parser.offer("\n"))
        // 无换行结尾的末行留在缓冲，不发射；flush 时才交付
        assertEquals(emptyList<String>(), parser.offer("低头思故乡"))
        assertEquals(listOf("低头思故乡"), parser.flush())
        assertEquals(2, parser.emittedCount())
    }

    @Test
    fun `flush冲刷末行残行`() {
        val parser = StreamCandidateText()
        parser.offer("好的")
        assertEquals(listOf("好的"), parser.flush())
        assertEquals(1, parser.emittedCount())
        // 二次 flush 缓冲已空
        assertEquals(emptyList<String>(), parser.flush())
    }

    @Test
    fun `达到条数上限后静默忽略后续内容`() {
        val parser = StreamCandidateText()
        val lines = (1..8).joinToString("\n") { "候选$it\n" }
        parser.offer(lines)
        parser.flush()
        assertEquals(StreamCandidateText.MAX_CANDIDATES, parser.emittedCount())
        assertEquals(emptyList<String>(), parser.offer("更多\n"))
        assertEquals(emptyList<String>(), parser.flush())
    }

    @Test
    fun `cleanLine对空串与纯符号返回空`() {
        assertEquals("", StreamCandidateText.cleanLine(""))
        assertEquals("", StreamCandidateText.cleanLine("，。！？"))
        assertEquals("好的", StreamCandidateText.cleanLine(" ，好的 "))
    }
}
