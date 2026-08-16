package com.ziyou.ime.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RagPromptBuilder] 单元测试：引用格式 / 长度截断 / 退化路径。 */
class RagPromptBuilderTest {

    private val base = "请用简体中文回答问题。"
    private val persona = "你是一位知识渊博的助手。"

    private fun chunk(text: String, source: String = "test.md", score: Double = 1.0) =
        RetrievedChunk(text = text, sourceName = source, itemId = "item1", score = score)

    @Test
    fun `无chunk无记忆时退化为base加persona`() {
        val prompt = RagPromptBuilder.build(base, persona)
        assertEquals("$base\n\n$persona", prompt)
    }

    @Test
    fun `人设为空时仅保留base`() {
        val prompt = RagPromptBuilder.build(base, "")
        assertEquals(base, prompt)
    }

    @Test
    fun `记忆摘要注入长期记忆区块`() {
        val prompt = RagPromptBuilder.build(base, persona, memorySummary = "用户喜欢简洁回答")
        assertTrue(prompt.contains("【长期记忆】"))
        assertTrue(prompt.contains("用户喜欢简洁回答"))
    }

    @Test
    fun `chunk按顺序编号并标注来源`() {
        val prompt = RagPromptBuilder.build(
            base, persona,
            chunks = listOf(chunk("知识甲", "a.md"), chunk("知识乙", "b.txt"))
        )
        assertTrue(prompt.contains("【参考资料】"))
        assertTrue(prompt.contains("[1]（来源：a.md）\n知识甲"))
        assertTrue(prompt.contains("[2]（来源：b.txt）\n知识乙"))
        assertTrue(prompt.contains("请优先基于上述参考资料回答问题"))
    }

    @Test
    fun `超预算的低分chunk被截断`() {
        val bigChunk = chunk("大".repeat(4000), "big.md")
        val nextChunk = chunk("次".repeat(3000), "next.md")
        // 大 chunk 在前占据大部分预算，后续超出剩余预算的 chunk 被丢弃
        val prompt = RagPromptBuilder.build(base, persona, chunks = listOf(bigChunk, nextChunk))
        assertTrue(prompt.length <= RagPromptBuilder.MAX_PROMPT_CHARS)
        assertTrue(prompt.contains("[1]（来源：big.md）"))
        assertFalse(prompt.contains("next.md"))
    }

    @Test
    fun `全部chunk超预算时不追加参考资料区块`() {
        val huge = chunk("超".repeat(RagPromptBuilder.MAX_PROMPT_CHARS + 100))
        val prompt = RagPromptBuilder.build(base, persona, chunks = listOf(huge))
        assertFalse(prompt.contains("【参考资料】"))
        assertEquals("$base\n\n$persona", prompt)
    }

    // ===== buildPolish（润色任务变体） =====

    @Test
    fun `润色prompt无参考资料时仍含人设与润色指令`() {
        val prompt = RagPromptBuilder.buildPolish(base, persona)
        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains(persona))
        assertTrue(prompt.contains("【润色任务】"))
        assertFalse(prompt.contains("【风格参考资料】"))
    }

    @Test
    fun `润色prompt注入风格参考资料且不要求引用编号`() {
        val prompt = RagPromptBuilder.buildPolish(
            base, persona, chunks = listOf(chunk("明月出天山", "李白诗集.txt")))
        assertTrue(prompt.contains("【风格参考资料】"))
        assertTrue(prompt.contains("[1]（来源：李白诗集.txt）"))
        assertTrue(prompt.contains("明月出天山"))
        // 润色场景仅借鉴风格，不复用问答路径的「标注编号」指令
        assertFalse(prompt.contains("请优先基于上述参考资料回答问题"))
    }

    @Test
    fun `润色指令约定编号输出格式与解析器对齐`() {
        val prompt = RagPromptBuilder.buildPolish(base, persona)
        assertTrue(prompt.contains("保留原文的核心意思与关键信息"))
        assertTrue(prompt.contains("1. <版本一文本>（风格说明，不超过15字）"))
        assertTrue(prompt.contains("除编号版本外不要输出任何其他内容"))
    }

    @Test
    fun `润色prompt受总预算约束`() {
        val bigChunk = chunk("大".repeat(RagPromptBuilder.MAX_PROMPT_CHARS))
        val prompt = RagPromptBuilder.buildPolish(base, persona, chunks = listOf(bigChunk))
        // 超预算 chunk 丢弃，但润色指令始终追加；总长受控
        assertTrue(prompt.length <= RagPromptBuilder.MAX_PROMPT_CHARS + 600)
        assertTrue(prompt.contains("【润色任务】"))
        assertFalse(prompt.contains("【风格参考资料】"))
    }
}
