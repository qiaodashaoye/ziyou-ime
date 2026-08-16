package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailureBackoff] 单元测试：门槛前不阻塞、指数冷却递增与封顶、成功复位
 * （耗电审计 P0：连续失败退避防空烧配额与射频）。
 */
class FailureBackoffTest {

    @Test
    fun `门槛前失败不进入冷却`() {
        val backoff = FailureBackoff(threshold = 3)
        backoff.recordFailure(1_000L)
        backoff.recordFailure(2_000L)
        assertFalse(backoff.isBlocked(3_000L))
        assertEquals(2, backoff.consecutiveFailures())
    }

    @Test
    fun `第三次失败进入30秒冷却`() {
        val backoff = FailureBackoff()
        repeat(3) { backoff.recordFailure(0L) }
        assertTrue(backoff.isBlocked(10_000L))
        assertTrue(backoff.isBlocked(29_999L))
        assertFalse(backoff.isBlocked(30_000L))
    }

    @Test
    fun `冷却时长按30_60_120指数递增并封顶`() {
        val backoff = FailureBackoff()
        // 第 3 次失败：30s
        repeat(3) { backoff.recordFailure(0L) }
        assertFalse(backoff.isBlocked(30_000L))
        // 第 4 次失败：60s
        backoff.recordFailure(30_000L)
        assertTrue(backoff.isBlocked(89_999L))
        assertFalse(backoff.isBlocked(90_000L))
        // 第 5 次失败：120s
        backoff.recordFailure(90_000L)
        assertTrue(backoff.isBlocked(209_999L))
        assertFalse(backoff.isBlocked(210_000L))
        // 第 6 次失败仍为 120s 封顶
        backoff.recordFailure(210_000L)
        assertTrue(backoff.isBlocked(329_999L))
        assertFalse(backoff.isBlocked(330_000L))
    }

    @Test
    fun `成功完全复位计数与冷却`() {
        val backoff = FailureBackoff()
        repeat(4) { backoff.recordFailure(0L) }
        assertTrue(backoff.isBlocked(1_000L))
        backoff.recordSuccess()
        assertEquals(0, backoff.consecutiveFailures())
        assertFalse(backoff.isBlocked(1_000L))
        // 复位后重新从门槛开始累计
        repeat(2) { backoff.recordFailure(2_000L) }
        assertFalse(backoff.isBlocked(3_000L))
    }

    @Test
    fun `自定义参数生效`() {
        val backoff = FailureBackoff(threshold = 1, baseCooldownMs = 100L, maxCooldownMs = 150L)
        backoff.recordFailure(0L)
        assertTrue(backoff.isBlocked(99L))
        backoff.recordFailure(100L)
        // 100<<1=200 被 maxCooldownMs 收限到 150
        assertTrue(backoff.isBlocked(249L))
        assertFalse(backoff.isBlocked(250L))
    }
}
