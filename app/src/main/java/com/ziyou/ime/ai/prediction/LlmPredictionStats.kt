package com.ziyou.ime.ai.prediction

/**
 * LLM 预测链路的验收指标统计（联想优化方案 §7.7 真机验证支撑）。
 *
 * 纯内存计数器 + 单行 key=value 汇总，**不含任何用户词内容**（隐私红线）；
 * 由 [LlmPredictionCoordinator] 在主线程打点，[dumpAndReset] 经 Log 输出后清零。
 * 真机验收口径：
 * - 缓存命中率       = hits/(hits+misses)            ≥40%
 * - 首条候选感知延迟 = 请求发起→首批候选交付的 p50    <1000ms
 * - 请求数下降       = reqs 对照无缓存基线            ≥50%
 * - 链式第2/3轮命中率 = chainHits/chainRounds（采纳后下一轮查询命中缓存）≥60%
 *
 * 本类非线程安全：仅主线程访问（协调器的打点与 flush 均在主线程）。
 */
object LlmPredictionStats {

    /** 首条延迟采样上限（滚动窗口，防长会话内存增长） */
    private const val MAX_LATENCY_SAMPLES = 100

    /** 缓存查询命中/未命中次数（功能开启时的每次上屏查询计一次） */
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /** 链式轮次：预测采纳后的下一次查询为一轮；命中即链式零等待 */
    private var chainRounds = 0L
    private var chainHits = 0L

    /** 预取：发起次数与「发起时已在缓存」（免请求）次数 */
    private var prefetchIssued = 0L
    private var prefetchWarmHits = 0L

    /** 真实网络请求次数（缓存命中不计） */
    private var requestsSent = 0L

    /** 请求发起→首批候选交付的延迟样本（ms） */
    private val firstLatencies = ArrayDeque<Long>()

    /** 是否有未消费的预测采纳（下次查询时结算链式轮次） */
    private var pendingAdoption = false

    /** 当前 in-flight 请求的发起时刻与首批交付标记（防一次请求多次记账） */
    private var requestStartMs = 0L
    private var firstDelivered = false

    /** 预测采纳发生（Service 采纳出口打点）：下次查询结算链式轮次 */
    fun onAdoption() {
        pendingAdoption = true
    }

    /** 缓存命中：结算可能的链式轮次 */
    fun onCacheHit() {
        cacheHits++
        settleChainRound(hit = true)
    }

    /** 缓存未命中（无论后续触发决策如何，查询本身计 miss） */
    fun onCacheMiss() {
        cacheMisses++
        settleChainRound(hit = false)
    }

    /** 功能关闭时的上屏：消费挂起采纳标记，避免跨开关期误结算 */
    fun onLookupSkipped() {
        pendingAdoption = false
    }

    /** 预取发起（未命中缓存、将要或已经发起请求） */
    fun onPrefetchIssued() {
        prefetchIssued++
    }

    /** 预取时上下文已在缓存（免请求即达成预热目标） */
    fun onPrefetchWarmHit() {
        prefetchWarmHits++
    }

    /** 真实请求发起（记录基准时刻，首批交付时结算延迟） */
    fun onRequestStarted(nowMs: Long) {
        requestsSent++
        requestStartMs = nowMs
        firstDelivered = false
    }

    /** 请求的首批候选交付（流式首行或整包）：记账一次首条延迟 */
    fun onFirstCandidate(nowMs: Long) {
        if (firstDelivered || requestStartMs <= 0L) return
        firstDelivered = true
        firstLatencies.addLast(nowMs - requestStartMs)
        while (firstLatencies.size > MAX_LATENCY_SAMPLES) {
            firstLatencies.removeFirst()
        }
    }

    /**
     * 输出单行统计并清零（flush 时经 Log 交付）。
     * 格式示例：`hits=12 misses=8 hitRate=60.0% chain=3/5 prefetch=2/4 reqs=4 p50ms=432`
     */
    fun dumpAndReset(): String {
        val lookups = cacheHits + cacheMisses
        val hitRate = if (lookups > 0) cacheHits * 100.0 / lookups else 0.0
        val summary = buildString {
            append("hits=").append(cacheHits)
            append(" misses=").append(cacheMisses)
            append(" hitRate=").append("%.1f%%".format(hitRate))
            append(" chain=").append(chainHits).append('/').append(chainRounds)
            append(" prefetch=").append(prefetchWarmHits).append('/').append(prefetchIssued)
            append(" reqs=").append(requestsSent)
            append(" p50ms=").append(percentile(50))
        }
        reset()
        return summary
    }

    /** 全部计数清零（测试与 dump 复用） */
    fun reset() {
        cacheHits = 0
        cacheMisses = 0
        chainRounds = 0
        chainHits = 0
        prefetchIssued = 0
        prefetchWarmHits = 0
        requestsSent = 0
        firstLatencies.clear()
        pendingAdoption = false
        requestStartMs = 0
        firstDelivered = false
    }

    /** 结算链式轮次：采纳后的首次查询即一轮，随后清挂起标记 */
    private fun settleChainRound(hit: Boolean) {
        if (pendingAdoption) {
            chainRounds++
            if (hit) chainHits++
            pendingAdoption = false
        }
    }

    /** 延迟样本分位数（无样本返回 -1） */
    private fun percentile(p: Int): Long {
        if (firstLatencies.isEmpty()) return -1L
        val sorted = firstLatencies.toList().sorted()
        val index = ((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
