package com.ziyou.ime.core.prediction

/**
 * LLM 预测请求连续失败退避器（纯状态机，可穷举单测）。
 *
 * 连续失败达到 [threshold] 后进入冷却期（指数递增：30s→60s→120s 封顶），
 * 冷却期内 [isBlocked] 返回 true，调用方应放弃发起请求——网络不可达/鉴权
 * 失败时避免空烧限流配额、CPU 与射频（耗电审计 P0）。
 *
 * 语义约定：
 * - 仅统计「请求真正发出后的失败」（防抖放弃/退账不计）；
 * - 任何一次成功即完全复位（计数清零 + 冷却解除）；
 * - 缓存命中不经网络，由调用方按成功路径复位与否自行决定
 *   （协调器将其视为成功：端到端链路是通的）。
 *
 * 非线程安全：调用方（应用层协调器）保证只在主线程访问。
 */
class FailureBackoff(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val baseCooldownMs: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS
) {

    companion object {
        /** 进入退避前的连续失败次数门槛 */
        const val DEFAULT_THRESHOLD = 3

        /** 首次退避冷却时长（ms）：30 秒 */
        const val DEFAULT_BASE_COOLDOWN_MS = 30_000L

        /** 冷却时长上限（ms）：120 秒（30→60→120 后不再增长） */
        const val DEFAULT_MAX_COOLDOWN_MS = 120_000L
    }

    /** 连续失败计数（成功后清零） */
    private var consecutiveFailures = 0

    /** 冷却截止时间（SystemClock.elapsedRealtime 语义，与调用方一致） */
    private var cooldownUntilMs = 0L

    /** 当前时刻是否处于冷却期（应放弃发起请求） */
    fun isBlocked(nowMs: Long): Boolean = nowMs < cooldownUntilMs

    /**
     * 记录一次请求失败：计数自增，跨过 [threshold] 后按指数递增冷却
     * （第 3 次失败 30s，第 4 次 60s，第 5 次起 120s 封顶）。
     */
    fun recordFailure(nowMs: Long) {
        consecutiveFailures++
        if (consecutiveFailures >= threshold) {
            // 指数级数封顶在 2 位移位（30s<<2=120s），与 maxCooldownMs 双重收限
            val level = minOf(consecutiveFailures - threshold, 2)
            val cooldown = (baseCooldownMs shl level).coerceAtMost(maxCooldownMs)
            cooldownUntilMs = nowMs + cooldown
        }
    }

    /** 记录一次请求成功：完全复位（计数清零 + 冷却解除）。 */
    fun recordSuccess() {
        consecutiveFailures = 0
        cooldownUntilMs = 0L
    }

    /** 当前连续失败计数（只读，供统计/测试断言）。 */
    fun consecutiveFailures(): Int = consecutiveFailures
}
