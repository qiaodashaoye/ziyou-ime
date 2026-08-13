package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RequestRateWindow] 单元测试：滚动窗口记账、超限拒绝、退账与清空
 * （分析报告 S2 / P6：防抖放弃的请求不得占用限流配额）。
 */
class RequestRateWindowTest {

    @Test
    fun `窗口内未超限记账成功`() {
        val window = RequestRateWindow(maxPerWindow = 3)
        assertTrue(window.tryRecord(1_000L))
        assertTrue(window.tryRecord(2_000L))
        assertTrue(window.tryRecord(3_000L))
        assertEquals(3, window.size(3_000L))
    }

    @Test
    fun `超限拒绝记账`() {
        val window = RequestRateWindow(maxPerWindow = 2)
        assertTrue(window.tryRecord(1_000L))
        assertTrue(window.tryRecord(2_000L))
        assertFalse(window.tryRecord(3_000L))
        assertEquals(2, window.size(3_000L))
    }

    @Test
    fun `滚出窗口的旧记录释放配额`() {
        val window = RequestRateWindow(maxPerWindow = 2, windowMs = 60_000L)
        assertTrue(window.tryRecord(0L))
        assertTrue(window.tryRecord(10_000L))
        assertFalse(window.tryRecord(20_000L))
        // 首条已滚出窗口（>60s），配额释放
        assertTrue(window.tryRecord(61_000L))
        // 10s 处的记录此刻仍在窗口内（51s < 60s），加上新记的一条共 2 条
        assertEquals(2, window.size(61_000L))
    }

    @Test
    fun `退账释放最近一次记账`() {
        val window = RequestRateWindow(maxPerWindow = 2)
        assertTrue(window.tryRecord(1_000L))
        assertTrue(window.tryRecord(2_000L))
        assertFalse(window.tryRecord(3_000L))
        // 防抖放弃 → 退账最近一次 → 配额恢复
        window.refundLast()
        assertEquals(1, window.size(3_000L))
        assertTrue(window.tryRecord(4_000L))
    }

    @Test
    fun `空窗口退账静默无副作用`() {
        val window = RequestRateWindow()
        window.refundLast()
        window.refundLast()
        assertEquals(0, window.size(0L))
        assertTrue(window.tryRecord(1L))
    }

    @Test
    fun `退账只还最近一条不影响更早记账`() {
        val window = RequestRateWindow(maxPerWindow = 3)
        assertTrue(window.tryRecord(1_000L))
        assertTrue(window.tryRecord(2_000L))
        assertTrue(window.tryRecord(3_000L))
        window.refundLast()
        assertEquals(2, window.size(3_000L))
        // 再满额后仍按滚动窗口语义拒绝
        assertTrue(window.tryRecord(4_000L))
        assertFalse(window.tryRecord(5_000L))
    }

    @Test
    fun `clear 清空全部记账`() {
        val window = RequestRateWindow(maxPerWindow = 1)
        assertTrue(window.tryRecord(1_000L))
        assertFalse(window.tryRecord(2_000L))
        window.clear()
        assertTrue(window.tryRecord(3_000L))
    }

    @Test
    fun `默认配置为一分钟二十次`() {
        assertEquals(60_000L, RequestRateWindow.DEFAULT_WINDOW_MS)
        assertEquals(20, RequestRateWindow.DEFAULT_MAX_PER_WINDOW)
        val window = RequestRateWindow()
        repeat(20) { i -> assertTrue(window.tryRecord(i * 1_000L)) }
        assertFalse(window.tryRecord(20_000L))
    }
}
