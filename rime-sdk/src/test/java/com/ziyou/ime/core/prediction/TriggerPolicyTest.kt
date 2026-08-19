package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TriggerPolicy.decide] 单元测试：五条规则按序短路的全分支穷举。 */
class TriggerPolicyTest {

    private val words = listOf("今天")

    @Test
    fun `窗口为空时Skip`() {
        // 规则 1 优先级最高：即使其余条件都满足也不触发
        val decision = TriggerPolicy.decide("你好。", 10_000L, emptyList())
        assertEquals(TriggerPolicy.TriggerDecision.Skip, decision)
    }

    @Test
    fun `距上次请求不足最小间隔时Skip`() {
        // 规则 2：硬限流优先于句末标点强信号
        val decision = TriggerPolicy.decide(
            "你好。", TriggerPolicy.MIN_INTERVAL_MS - 1, words
        )
        assertEquals(TriggerPolicy.TriggerDecision.Skip, decision)
    }

    @Test
    fun `间隔恰为最小值时限流放行`() {
        val decision = TriggerPolicy.decide("你好。", TriggerPolicy.MIN_INTERVAL_MS, words)
        assertEquals(TriggerPolicy.TriggerDecision.Trigger, decision)
    }

    @Test
    fun `句末标点强信号立即Trigger`() {
        for (punct in listOf("。", "！", "？", "!", "?", "…")) {
            val decision = TriggerPolicy.decide("你好$punct", 10_000L, words)
            assertEquals("标点 $punct 应 Trigger", TriggerPolicy.TriggerDecision.Trigger, decision)
        }
    }

    @Test
    fun `trim后无汉字时Skip`() {
        // 规则 4：纯英文/数字/非句末符号不值得请求
        assertEquals(
            TriggerPolicy.TriggerDecision.Skip,
            TriggerPolicy.decide("hello", 10_000L, words)
        )
        assertEquals(
            TriggerPolicy.TriggerDecision.Skip,
            TriggerPolicy.decide(" 12345 ", 10_000L, words)
        )
        assertEquals(
            TriggerPolicy.TriggerDecision.Skip,
            TriggerPolicy.decide(" ，、", 10_000L, words)
        )
    }

    @Test
    fun `汉字上屏且无强信号时Debounce`() {
        val decision = TriggerPolicy.decide("你好", 10_000L, words)
        assertEquals(TriggerPolicy.TriggerDecision.Debounce(TriggerPolicy.DEBOUNCE_MS), decision)
    }

    @Test
    fun `含汉字的混合文本走Debounce`() {
        // 中英混合含汉字且无句末标点：按普通上屏防抖处理
        val decision = TriggerPolicy.decide("Kotlin开发，", 10_000L, words)
        assertTrue(decision is TriggerPolicy.TriggerDecision.Debounce)
        assertEquals(TriggerPolicy.DEBOUNCE_MS, (decision as TriggerPolicy.TriggerDecision.Debounce).delayMs)
    }

    @Test
    fun `句末标点判定优先于汉字检查`() {
        // 纯标点「。」无汉字，但规则 3 在规则 4 之前短路为 Trigger
        val decision = TriggerPolicy.decide("。", 10_000L, words)
        assertEquals(TriggerPolicy.TriggerDecision.Trigger, decision)
    }

    @Test
    fun `扩展A区生僻字视为汉字走Debounce`() {
        // 汉字判定覆盖扩展 A 区（\u3400-\u4DBF），生僻字上屏不被误判为无汉字
        val decision = TriggerPolicy.decide("\u3400\u4DBF", 10_000L, words)
        assertEquals(TriggerPolicy.TriggerDecision.Debounce(TriggerPolicy.DEBOUNCE_MS), decision)
    }
}
