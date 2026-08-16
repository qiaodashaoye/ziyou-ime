package com.ziyou.ime.core.prediction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeavyRequestGate] 单元测试：预取/预热门控的环境判定
 * （耗电审计 P0：计量网络与低电量未充电时放弃重请求）。
 */
class HeavyRequestGateTest {

    @Test
    fun `WiFi高电量未充电允许`() {
        assertTrue(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = false, batteryPercent = 80, isCharging = false))
    }

    @Test
    fun `计量网络一律拒绝`() {
        assertFalse(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = true, batteryPercent = 100, isCharging = true))
        assertFalse(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = true, batteryPercent = 80, isCharging = false))
    }

    @Test
    fun `低电量未充电拒绝`() {
        assertFalse(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = false,
            batteryPercent = HeavyRequestGate.MIN_BATTERY_PERCENT - 1,
            isCharging = false))
    }

    @Test
    fun `低电量门槛值边界允许`() {
        assertTrue(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = false,
            batteryPercent = HeavyRequestGate.MIN_BATTERY_PERCENT,
            isCharging = false))
    }

    @Test
    fun `充电状态豁免低电量限制`() {
        assertTrue(HeavyRequestGate.allowHeavyRequest(
            isMeteredNetwork = false, batteryPercent = 5, isCharging = true))
    }
}
