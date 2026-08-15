package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Test

/** [CandidateFusion.fuse] 单元测试：引擎词在前、去重、截断与边界。 */
class CandidateFusionTest {

    @Test
    fun `引擎词原序在前LLM词按序追加`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("你好", "您好"),
            llmCandidates = listOf("世界", "中国"),
            limit = 5
        )
        assertEquals(listOf("你好", "您好", "世界", "中国"), fused)
    }

    @Test
    fun `与引擎词trim后相等的LLM词被去重`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("你好"),
            llmCandidates = listOf(" 你好 ", "世界"),
            limit = 5
        )
        assertEquals(listOf("你好", "世界"), fused)
    }

    @Test
    fun `LLM词之间重复同样去重`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = emptyList(),
            llmCandidates = listOf("今天", "今天", "明天"),
            limit = 5
        )
        assertEquals(listOf("今天", "明天"), fused)
    }

    @Test
    fun `总长截断到limit`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("一", "二", "三"),
            llmCandidates = listOf("四", "五", "六", "七"),
            limit = 5
        )
        assertEquals(listOf("一", "二", "三", "四", "五"), fused)
    }

    @Test
    fun `默认limit为5`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("一", "二"),
            llmCandidates = listOf("三", "四", "五", "六", "七")
        )
        assertEquals(5, fused.size)
        assertEquals(listOf("一", "二", "三", "四", "五"), fused)
    }

    @Test
    fun `引擎词数已达或超过limit时LLM词全部不追加`() {
        assertEquals(
            listOf("一", "二", "三", "四", "五"),
            CandidateFusion.fuse(
                listOf("一", "二", "三", "四", "五"), listOf("六"), limit = 5
            )
        )
        assertEquals(
            listOf("一", "二", "三"),
            CandidateFusion.fuse(listOf("一", "二", "三", "四"), listOf("五"), limit = 3)
        )
    }

    @Test
    fun `空输入与空白LLM片段`() {
        assertEquals(emptyList<String>(), CandidateFusion.fuse(emptyList(), emptyList()))
        assertEquals(
            listOf("你好"),
            CandidateFusion.fuse(emptyList(), listOf("  ", "", "你好"), limit = 5)
        )
    }

    @Test
    fun `exclude命中的LLM词被丢弃防复读回显`() {
        // 刚上屏的词（词窗口）不应被模型复读推回候选栏
        val fused = CandidateFusion.fuse(
            engineCandidates = emptyList(),
            llmCandidates = listOf("低头思故乡", "疑是地上霜。"),
            limit = 5,
            exclude = listOf("低头思故乡")
        )
        assertEquals(listOf("疑是地上霜。"), fused)
    }

    @Test
    fun `exclude按trim比较且不影响引擎词`() {
        // exclude 只作用于 LLM 词：引擎词即便命中排除集也原样保留
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("床前明月光"),
            llmCandidates = listOf(" 低头思故乡 ", "举头望明月"),
            limit = 5,
            exclude = listOf("床前明月光", "低头思故乡")
        )
        assertEquals(listOf("床前明月光", "举头望明月"), fused)
    }

    @Test
    fun `仅标点不同的LLM候选只保留首现`() {
        // 去重键剔除标点归一：「疑似地上霜」三种标点变体不得占多个位置，
        // 首现胜保留其原始文本
        val fused = CandidateFusion.fuse(
            engineCandidates = emptyList(),
            llmCandidates = listOf("疑似地上霜", "疑似地上霜。", "疑似地上霜？", "低头思故乡"),
            limit = 5
        )
        assertEquals(listOf("疑似地上霜", "低头思故乡"), fused)
    }

    @Test
    fun `与引擎词仅标点不同的LLM词被去重`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("你好"),
            llmCandidates = listOf("你好。", "你好，", "世界"),
            limit = 5
        )
        assertEquals(listOf("你好", "世界"), fused)
    }

    @Test
    fun `exclude与纯标点片段按归一键判定`() {
        // 排除词的标点变体同样命中；纯标点片段（归一后为空）直接丢弃
        val fused = CandidateFusion.fuse(
            engineCandidates = emptyList(),
            llmCandidates = listOf("低头思故乡。", "。", "举头望明月"),
            limit = 5,
            exclude = listOf("低头思故乡")
        )
        assertEquals(listOf("举头望明月"), fused)
    }

    @Test
    fun `引擎候选段内重复按归一键去重首现胜`() {
        // 诗词联想链场景：预测态 menu 可能出现同句重复条目
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("疑是地上霜", "疑是地上霜", "举头望明月"),
            llmCandidates = emptyList(),
            limit = 5
        )
        assertEquals(listOf("疑是地上霜", "举头望明月"), fused)
    }

    @Test
    fun `引擎候选仅标点不同的变体只保留首现`() {
        // 标点变体误判防护：归一键（剔标点）相等即视为重复，保留首现原文
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("疑是地上霜。", "疑是地上霜", "疑是地上霜？", "举头望明月"),
            llmCandidates = listOf("疑是地上霜"),
            limit = 5
        )
        assertEquals(listOf("疑是地上霜。", "举头望明月"), fused)
    }

    @Test
    fun `引擎段去重腾出的位置由后续候选与LLM词补齐`() {
        val fused = CandidateFusion.fuse(
            engineCandidates = listOf("床前明月光", "床前明月光", "疑是地上霜", "举头望明月"),
            llmCandidates = listOf("低头思故乡"),
            limit = 4
        )
        assertEquals(listOf("床前明月光", "疑是地上霜", "举头望明月", "低头思故乡"), fused)
    }
}
