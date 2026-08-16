package com.ziyou.ime.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PolishResultParser] 单元测试：编号解析 / 分隔符容忍 / 说明提取 /
 * 杂散行丢弃 / 零编号兜底 / 候选上限。
 */
class PolishResultParserTest {

    @Test
    fun `标准编号候选解析出文本与风格说明`() {
        val raw = """
            1. 举杯邀明月，仗剑去国千里（豪放洒脱）
            2. 今夜月色正好，不如出门走走（清新自然）
        """.trimIndent()
        val variants = PolishResultParser.parse(raw)
        assertEquals(2, variants.size)
        assertEquals("举杯邀明月，仗剑去国千里", variants[0].text)
        assertEquals("豪放洒脱", variants[0].note)
        assertEquals("今夜月色正好，不如出门走走", variants[1].text)
        assertEquals("清新自然", variants[1].note)
    }

    @Test
    fun `全角分隔符与顿号编号均被容忍`() {
        val raw = "1．版本甲文本\n2、版本乙文本\n3)版本丙文本"
        val variants = PolishResultParser.parse(raw)
        assertEquals(3, variants.size)
        assertEquals("版本甲文本", variants[0].text)
        assertEquals("版本乙文本", variants[1].text)
        assertEquals("版本丙文本", variants[2].text)
    }

    @Test
    fun `无风格说明时候选正文完整保留`() {
        val variants = PolishResultParser.parse("1. 只有正文没有说明")
        assertEquals(1, variants.size)
        assertEquals("只有正文没有说明", variants[0].text)
        assertEquals("", variants[0].note)
    }

    @Test
    fun `半角括号说明同样被提取`() {
        val variants = PolishResultParser.parse("1. 改写文本(简洁版)")
        assertEquals("改写文本", variants[0].text)
        assertEquals("简洁版", variants[0].note)
    }

    @Test
    fun `杂散非编号行被丢弃`() {
        val raw = """
            好的，以下是为您润色的版本：
            1. 候选正文一
            希望你喜欢！
            2. 候选正文二
        """.trimIndent()
        val variants = PolishResultParser.parse(raw)
        assertEquals(2, variants.size)
        assertEquals("候选正文一", variants[0].text)
        assertEquals("候选正文二", variants[1].text)
    }

    @Test
    fun `无任何编号行时整体兜底为单候选`() {
        val raw = "春风又绿江南岸，明月何时照我还。"
        val variants = PolishResultParser.parse(raw)
        assertEquals(1, variants.size)
        assertEquals(raw, variants[0].text)
        assertEquals("", variants[0].note)
    }

    @Test
    fun `空白输入返回空列表`() {
        assertTrue(PolishResultParser.parse("").isEmpty())
        assertTrue(PolishResultParser.parse("   \n  ").isEmpty())
    }

    @Test
    fun `候选数超出上限时截断`() {
        val raw = (1..8).joinToString("\n") { "$it. 候选$it" }
        val variants = PolishResultParser.parse(raw)
        assertEquals(PolishResultParser.MAX_VARIANTS, variants.size)
        assertEquals("候选5", variants.last().text)
    }

    @Test
    fun `正文内含括号但行尾说明仅取末尾`() {
        val variants = PolishResultParser.parse("1. 他说「你好」（对话感）然后离开（白描）")
        assertEquals("他说「你好」（对话感）然后离开", variants[0].text)
        assertEquals("白描", variants[0].note)
    }
}
