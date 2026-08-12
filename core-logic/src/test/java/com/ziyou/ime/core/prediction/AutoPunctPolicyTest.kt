package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Test

/** [AutoPunctPolicy.decidePrefix] 单元测试：四条规则的全分支穷举。 */
class AutoPunctPolicyTest {

    @Test
    fun `上下文为空不插入`() {
        assertEquals("", AutoPunctPolicy.decidePrefix(emptyList(), "疑似地上霜"))
    }

    @Test
    fun `常规场景插入中文逗号`() {
        // 「床前明月光」后采纳「疑似地上霜」→ 前置逗号
        assertEquals(
            AutoPunctPolicy.DEFAULT_PUNCT,
            AutoPunctPolicy.decidePrefix(listOf("床前明月光"), "疑似地上霜")
        )
    }

    @Test
    fun `前文末尾已有标点不重复插入`() {
        // 逗号/顿号/冒号等任何标点结尾都跳过，避免「，，」叠加（只看末字符）
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("你好，"), "世界"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("香蕉、"), "橘子"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("注意："), "安全"))
    }

    @Test
    fun `前文末尾为句末标点不插入`() {
        // 句末标点后是新句子，不应以逗号连接
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("你好。"), "世界"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("真的吗？"), "是的"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("太好了！"), "我们"))
    }

    @Test
    fun `独立标点提交入窗后判定生效`() {
        // 用户手动打过逗号（独立「，」提交入窗）：末词即标点，不再插
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("你好", "，"), "世界"))
    }

    @Test
    fun `采纳词自带前导标点不插入`() {
        // LLM 候选可能已含前导标点，标点已就位不重复插
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("床前明月光"), "，低头思故乡"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("床前明月光"), " 。"))
    }

    @Test
    fun `以最后一个窗口词为判定依据`() {
        // 多词窗口只看末词：末词以字结尾 → 插；末词以标点结尾 → 不插
        assertEquals(
            AutoPunctPolicy.DEFAULT_PUNCT,
            AutoPunctPolicy.decidePrefix(listOf("窗前", "明月光"), "疑似地上霜")
        )
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("明月光", "。"), "低头"))
    }

    @Test
    fun `空白末词与空白采纳词不插入`() {
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("  "), "世界"))
        assertEquals("", AutoPunctPolicy.decidePrefix(listOf("你好"), ""))
    }
}
