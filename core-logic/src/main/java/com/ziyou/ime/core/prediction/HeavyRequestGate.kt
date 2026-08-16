package com.ziyou.ime.core.prediction

/**
 * LLM 预测重请求（预取/预热）门控（纯函数，可穷举单测）。
 *
 * 预取与预热是优化项而非用户直接触发的核心路径，在以下环境应静默放弃，
 * 把射频与 CPU 预算让给真实上屏触发的请求（耗电审计 P0）：
 * - 计量网络（移动数据）：每次请求伴随 TLS 握手与射频尾巴，费用敏感；
 * - 低电量且未充电：电量 < [MIN_BATTERY_PERCENT] 时非核心功能降级。
 *
 * 真实上屏触发的请求不受本门控约束（用户核心体验），仅受
 * [TriggerPolicy] / [RequestRateWindow] / [FailureBackoff] 节流。
 */
object HeavyRequestGate {

    /** 低电量门槛（%）：低于该值且未充电时禁用预取/预热 */
    const val MIN_BATTERY_PERCENT = 20

    /**
     * 是否允许发起预取/预热请求。
     *
     * @param isMeteredNetwork 当前活跃网络是否为计量网络（移动数据）
     * @param batteryPercent 当前电量百分比（0~100；读取失败传 100 不误杀）
     * @param isCharging 是否处于充电状态（充电时不限制，反正是外部供电）
     */
    fun allowHeavyRequest(
        isMeteredNetwork: Boolean,
        batteryPercent: Int,
        isCharging: Boolean
    ): Boolean {
        if (isMeteredNetwork) return false
        if (!isCharging && batteryPercent < MIN_BATTERY_PERCENT) return false
        return true
    }
}
