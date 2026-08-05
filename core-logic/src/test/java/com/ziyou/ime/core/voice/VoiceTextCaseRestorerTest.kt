package com.ziyou.ime.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VoiceTextCaseRestorer] 行为测试：中英混合识别产出的大小写恢复。
 * 核心场景源自缺陷报告："I LOVE 中国" 应输出为 "I love 中国"。
 */
class VoiceTextCaseRestorerTest {

    @Test
    fun `中英混合全大写英文恢复为正常句子`() {
        assertEquals("I love 中国", VoiceTextCaseRestorer.restore("I LOVE 中国"))
    }

    @Test
    fun `纯英文句首大写其余小写`() {
        assertEquals("Hello world", VoiceTextCaseRestorer.restore("HELLO WORLD"))
    }

    @Test
    fun `常见缩写保留全大写`() {
        assertEquals("AI 和 app 都很好用", VoiceTextCaseRestorer.restore("AI 和 APP 都很好用"))
        assertEquals("OK 就这样", VoiceTextCaseRestorer.restore("OK 就这样"))
    }

    @Test
    fun `句末标点后的英文句首大写`() {
        assertEquals("你好。Nice to meet you", VoiceTextCaseRestorer.restore("你好。NICE TO MEET YOU"))
        assertEquals("Yes! I am fine.", VoiceTextCaseRestorer.restore("YES! I AM FINE."))
    }

    @Test
    fun `中文之后的英文词按句首规则不受中文干扰`() {
        // "谢谢 THANKS" 未结束句子：THANKS 非句首，转小写
        assertEquals("谢谢 thanks", VoiceTextCaseRestorer.restore("谢谢 THANKS"))
    }

    @Test
    fun `单字母 i 恒大写而句中冠词 a 保持小写`() {
        assertEquals("I need a pen", VoiceTextCaseRestorer.restore("I NEED A PEN"))
        // 句首冠词由句首规则重新大写
        assertEquals("A dog is here", VoiceTextCaseRestorer.restore("A DOG IS HERE"))
        // 小写 i 也会被纠正为 I
        assertEquals("I love it", VoiceTextCaseRestorer.restore("i LOVE IT"))
    }

    @Test
    fun `混合大小写原样保留`() {
        assertEquals("我的 iPhone 丢了", VoiceTextCaseRestorer.restore("我的 iPhone 丢了"))
    }

    @Test
    fun `纯中文与空串零改动`() {
        assertEquals("今天天气不错", VoiceTextCaseRestorer.restore("今天天气不错"))
        assertEquals("", VoiceTextCaseRestorer.restore(""))
    }

    @Test
    fun `数字与符号不干扰大小写判定`() {
        assertEquals("第 1 名 is me", VoiceTextCaseRestorer.restore("第 1 名 IS ME"))
    }

    @Test
    fun `确定性：同一输入多次处理结果一致`() {
        val input = "I LOVE 中国 AND AI"
        val once = VoiceTextCaseRestorer.restore(input)
        assertEquals(once, VoiceTextCaseRestorer.restore(input))
        // 幂等：对已恢复文本再处理不变化（流式 partial 反复改写不抖动）
        assertEquals(once, VoiceTextCaseRestorer.restore(once))
    }
}
