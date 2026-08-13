package com.ziyou.ime.core.prediction

/**
 * LLM 预测请求的滚动一分钟限流窗口（纯逻辑，可穷举单测）。
 *
 * 与真实网络请求一一记账（缓存命中不占配额），滚动 [WINDOW_MS] 内
 * 超过 [maxPerWindow] 即拒绝；防抖请求在发射前放弃时经 [refundLast]
 * 退账——否则放弃的请求仍占配额，极端连打下会挤占真实请求额度
 * （分析报告 S2 / P6）。
 *
 * 本类非线程安全：调用方（应用层协调器）保证只在主线程访问。
 */
class RequestRateWindow(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {

    companion object {
        /** 滚动窗口时长（ms）：一分钟 */
        const val DEFAULT_WINDOW_MS = 60_000L

        /** 每窗口真实网络请求上限 */
        const val DEFAULT_MAX_PER_WINDOW = 20
    }

    /** 窗口内各次记账时刻（时间序：队首最旧） */
    private val attemptTimes = ArrayDeque<Long>()

    /**
     * 尝试记账一次请求。先清出已滚出窗口的旧记录，未超限则记入并返回
     * true；超限返回 false（调用方放弃本次请求）。
     */
    fun tryRecord(nowMs: Long): Boolean {
        prune(nowMs)
        if (attemptTimes.size >= maxPerWindow) return false
        attemptTimes.addLast(nowMs)
        return true
    }

    /**
     * 退还最近一次记账（防抖到期前放弃发射的请求退账）。
     * 无记账时静默 no-op。
     */
    fun refundLast() {
        if (attemptTimes.isNotEmpty()) attemptTimes.removeLast()
    }

    /** 当前窗口内有效记账数（供测试与观测） */
    fun size(nowMs: Long): Int {
        prune(nowMs)
        return attemptTimes.size
    }

    /** 清空全部记账（会话重置时调用） */
    fun clear() = attemptTimes.clear()

    /** 淘汰已滚出窗口的旧记录 */
    private fun prune(nowMs: Long) {
        while (attemptTimes.isNotEmpty() && nowMs - attemptTimes.first() > windowMs) {
            attemptTimes.removeFirst()
        }
    }
}
