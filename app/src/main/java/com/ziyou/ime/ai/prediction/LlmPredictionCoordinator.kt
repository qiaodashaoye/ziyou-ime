package com.ziyou.ime.ai.prediction

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ziyou.ime.core.prediction.AdoptionRecord
import com.ziyou.ime.core.prediction.CommitWordWindow
import com.ziyou.ime.core.prediction.ContextLruCache
import com.ziyou.ime.core.prediction.RequestRateWindow
import com.ziyou.ime.core.prediction.TriggerPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/**
 * LLM 智能续写协调器：上屏事件 → 触发决策 → 异步请求 → 结果分发。
 *
 * 编排职责（见 docs/智能预测可行性方案.md §4.2/§4.3 与
 * docs/联想功能优化调研与方案.md Phase 0~3）：
 * - 词窗口维护与 LRU 缓存命中短路（命中即免网络请求）；缓存**跨会话持久化**
 *   （[PredictionCacheStore]：首需时装载、脏变更异步落盘，热路径零磁盘 IO）；
 * - [TriggerPolicy] 触发决策：Trigger 即发、Debounce 延迟发、Skip 不发；
 * - 单 in-flight Job：新触发先取消旧 Job；防抖到期时窗口已变则发射前放弃；
 * - epoch 过期守卫：结果到达时上下文已变（新上屏/新编码/离开预测态）直接丢弃，
 *   防迟到的 LLM 词挂到新一轮编码的候选栏上被误点进编辑器；
 * - **流式分发**（§4.7）：SSE 逐行候选增量交付 [onResult]（累计列表语义），
 *   首条感知延迟从整包返回降至首行到达；
 * - **预测式预取**（§4.5）：引擎预测候选渲染后预热其下一轮上下文；
 * - **词窗口预热**（§4.4）：会话开始空闲期按缓存热度预热高频上下文；
 * - **采纳词对攒批**（§4.6 形态 B）：主线程 O(1) 计数，防抖落盘供构建期固化。
 *
 * 线程模型：[onCommitText] / [invalidate] / [reset] / [recordAdoption] / [flush]
 * 仅在主线程调用（上屏出口与 renderContext 均在主线程），内部状态无需加锁；
 * 网络请求与落盘在内部 scope（Main + 单线程 IO 池）执行。
 * 隐私红线：日志中禁止出现词窗口内容；持久化仅候选词与脱敏词对计数。
 */
class LlmPredictionCoordinator(private val appContext: Context) {

    companion object {
        private const val TAG = "LlmPredictCoord"

        /** 词窗口预热条数（§4.4：按缓存热度取最近访问的高频上下文） */
        private const val PREWARM_CONTEXT_COUNT = 3

        /** 预热在装载完成后的 settling 延迟（ms）：磁盘装载刚结束宁稍候不抢资源（S9） */
        private const val PREWARM_SETTLE_DELAY_MS = 300L

        /** 预热等待装载的兜底超时（ms）：异常防御，超时降级放弃本次预热 */
        private const val PREWARM_LOAD_TIMEOUT_MS = 10_000L

        /** 采纳攒批落盘防抖时长（ms）：与 LevelStats 同策略，热路径只内存计数 */
        private const val ADOPTION_FLUSH_DEBOUNCE_MS = 10_000L

        /** 导出等待装载的兜底超时（ms）：与预热同策略，超时降级以当前内存态导出（S7） */
        private const val EXPORT_LOAD_TIMEOUT_MS = 10_000L
    }

    /** 内部协程作用域：SupervisorJob 隔离单次请求失败，Main 分发保证回调主线程 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 单线程 IO 池：缓存/攒批落盘与装载串行执行，避免并发文件读写 */
    private val persistDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "LlmPredict-IO") }.asCoroutineDispatcher()

    /** 最近上屏词窗口（纯内存，零持久化） */
    private val window = CommitWordWindow()

    /**
     * 上下文 LRU 缓存：扩容至 [ContextLruCache.CAPACITY] 条并跨会话持久化。
     * 装载语义：首次需要时（首条上屏/预热/预取）在 IO 线程一次性读入；
     * 装载完成前的 put 允许先行写入（装载 restore 覆盖时丢失的窗口数据
     * 本就属于「装载前」会话，无正确性损失）。
     */
    private val cache = ContextLruCache()

    /** 缓存装载是否已完成（@Volatile：IO 线程装载完成后写、主线程读作落盘守卫，S4） */
    @Volatile
    private var cacheLoaded = false

    /** 缓存装载完成信号（S9 预热事件触发 + 装载仅发起一次的守卫双重角色） */
    private var cacheLoadSignal = CompletableDeferred<Unit>()

    /** 采纳词对攒批（运行时不排序，仅构建期固化数据源） */
    val adoption = AdoptionRecord()

    /** 攒批是否已从磁盘装载（@Volatile：主线程写、IO 线程读判重） */
    @Volatile
    private var adoptionLoaded = false

    /** 攒批装载完成信号（S7 导出需等装载完成再落盘，防部分数据覆盖历史） */
    private var adoptionLoadSignal = CompletableDeferred<Unit>()

    /** 攒批落盘防抖 Job（每次记录重置计时，到期且脏才写盘） */
    private var adoptionFlushJob: Job? = null

    /** 单调递增 epoch：每次发起/作废请求自增，结果到达时校验是否过期 */
    private var epoch = 0L

    /** 最近一次请求发起时刻（SystemClock.elapsedRealtime），节流判定基准 */
    private var lastAttemptMs = 0L

    /** 当前等待中/in-flight 的请求 Job（单 Job 保证） */
    private var requestJob: Job? = null

    /** 当前 in-flight 请求是否预取发起（防预取结果经 onCommitText 命中路径二次分发） */
    private var inFlightPrefetch = false

    /** 滚动一分钟费用熔断窗口（[RequestRateWindow] 纯逻辑，防抖放弃可退账，S2） */
    private val rateWindow = RequestRateWindow()

    /**
     * 结果分发回调（主线程）：携带请求发起时的 epoch、候选列表（**累计语义**：
     * 流式下每次为截至当前的全部候选）与触发时的上下文词快照。由 Service 设置；
     * 仅在仍无活跃编码时才应渲染（Service 侧二次校验）。上下文词供融合层排除
     * 「刚上屏的词被模型复读回显」。
     */
    var onResult: ((epoch: Long, candidates: List<String>, contextWords: List<String>) -> Unit)? = null

    /**
     * 上屏文本入口（主线程，输入热路径）。
     *
     * 热路径成本 = 一次开关读（SharedPreferences 内存缓存）+ 词窗口 O(1)/O(N)
     * 内存写 + 纯函数决策；零磁盘 IO、零网络。开关关闭时仅一次布尔读即返回。
     */
    fun onCommitText(text: String) {
        if (!LlmPredictionConfig.isEnabled(appContext)) {
            // 功能关闭：消费挂起采纳标记，避免跨开关期误结算链式轮次
            LlmPredictionStats.onLookupSkipped()
            return
        }
        ensureCacheLoaded()
        window.add(text)
        val words = window.words()
        // 缓存命中短路：立即作废 in-flight 请求并以新 epoch 分发（续写链即时延续）。
        // 异步投递而非同步回调：同一 commit 流程的引擎渲染（renderContext）必然在
        // 其后执行并清空视图侧 LLM 尾部，同步追加会被紧随的渲染清掉；投递到 Main
        // 队列后渲染先完成（lastPredictionMode/引擎候选已刷新），结果方能存活。
        // epoch 复检防多次命中时旧分发覆盖新分发。
        val cached = cache.get(words)
        if (cached != null) {
            LlmPredictionStats.onCacheHit()
            requestJob?.cancel()
            requestJob = null
            val dispatchEpoch = ++epoch
            scope.launch {
                if (dispatchEpoch == epoch) onResult?.invoke(dispatchEpoch, cached, words)
            }
            return
        }
        LlmPredictionStats.onCacheMiss()
        val now = SystemClock.elapsedRealtime()
        when (val decision = TriggerPolicy.decide(text, now - lastAttemptMs, words)) {
            is TriggerPolicy.TriggerDecision.Trigger -> scheduleRequest(0L)
            is TriggerPolicy.TriggerDecision.Debounce -> scheduleRequest(decision.delayMs)
            is TriggerPolicy.TriggerDecision.Skip -> Unit
        }
    }

    /**
     * 预测式预取（§4.5，主线程）：引擎预测候选渲染后由 Service 调用。
     *
     * 以「当前词窗口 + 预测词」为假设下一轮上下文，未命中缓存则按既有
     * 节流发起请求。用户真的点击采纳时，commitTextObservers 会使窗口变为
     * 同一序列从而缓存命中，链式联想第二轮零等待。
     *
     * 旁路纪律（预取是优化项，不得干扰主链路）：
     * - 有 in-flight 真实请求时不抢占（单 Job 资源让位主链路）；
     * - 遵守 [TriggerPolicy.MIN_INTERVAL_MS] 硬限流与每分钟费用熔断；
     * - 缓存已命中时静默返回：不 bump epoch、不分发（采纳后自然经
     *   onCommitText 命中路径分发，此处提前分发反而与真实动作竞争）。
     *
     * @param predictedWords 引擎预测候选文本（仅取首个，与 max_iterations 链式语义对齐）
     */
    fun prefetch(predictedWords: List<String>) {
        if (!LlmPredictionConfig.isEnabled(appContext)) return
        if (predictedWords.isEmpty()) return
        if (requestJob?.isActive == true) return
        ensureCacheLoaded()
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptMs < TriggerPolicy.MIN_INTERVAL_MS) return
        val base = window.words()
        if (base.isEmpty()) return
        val next = predictedWords.first().trim().filter { it.isLetterOrDigit() }
        if (next.isEmpty()) return
        val words = base + next
        if (cache.get(words) != null) {
            LlmPredictionStats.onPrefetchWarmHit()
            return
        }
        LlmPredictionStats.onPrefetchIssued()
        scheduleRequest(0L, prefetchContext = words)
    }

    /**
     * 词窗口预热（§4.4，主线程）：会话开始后由 Service 调用。
     *
     * 等待缓存装载完成信号（S9：事件驱动替代固定 1.5s 延迟——磁盘快时空等
     * 浪费、磁盘慢时预热落空两头不讨好）后，取最近访问热度键中首个未命中者
     * 静默请求：结果仅入缓存不渲染（窗口为空时分发也无渲染目标）。单 Job
     * 资源让位主链路；调用方（Service）负责每次服务实例至多调用一次。
     */
    fun prewarm() {
        if (!LlmPredictionConfig.isEnabled(appContext)) return
        val signal = ensureCacheLoaded()
        scope.launch {
            val loaded = withTimeoutOrNull(PREWARM_LOAD_TIMEOUT_MS) { signal.await() }
            if (loaded == null) return@launch
            delay(PREWARM_SETTLE_DELAY_MS)
            if (requestJob?.isActive == true) return@launch
            val target = cache.recentKeys(PREWARM_CONTEXT_COUNT).firstOrNull { words ->
                words.isNotEmpty() && cache.get(words) == null
            } ?: return@launch
            scheduleRequest(0L, prefetchContext = target)
        }
    }

    /**
     * 记录一次预测候选采纳（主线程，采纳出口调用）：词对计数 O(1) 内存写 +
     * 重置落盘防抖计时。数据不参与运行时排序（构建期固化，§4.6 形态 B）。
     *
     * @param prev 前文词（词窗口末位汉字词，由调用方经
     *        [AdoptionRecord.isLearnableWord] 过滤后传入）
     * @param next 被采纳的候选词
     */
    fun recordAdoption(prev: String, next: String) {
        if (!LlmPredictionConfig.isEnabled(appContext)) return
        LlmPredictionStats.onAdoption()
        ensureAdoptionLoaded()
        adoption.record(prev, next)
        adoptionFlushJob?.cancel()
        adoptionFlushJob = scope.launch {
            delay(ADOPTION_FLUSH_DEBOUNCE_MS)
            flushAdoptionIfDirty()
        }
    }

    /**
     * 主动落盘待持久化数据（与 LevelStats.flush 同点位：onFinishInputView /
     * onDestroy）：缓存快照 + 采纳攒批，均在 IO 线程异步执行，不阻塞调用方。
     */
    fun flush() {
        persistCache()
        scope.launch { flushAdoptionIfDirty() }
        // 验收指标单行输出（仅计数与延迟，无用户词内容）：真机验证口径见
        // docs/联想功能优化调研与方案.md §7.7；重置后进入下一统计周期
        Log.i(TAG, "LLM 预测统计: ${LlmPredictionStats.dumpAndReset()}")
    }

    /**
     * 清除全部持久化数据（设置页「清除」入口）：缓存与攒批的内存态 + 磁盘文件。
     */
    fun clearPersistedData() {
        cache.clear()
        adoption.clear()
        scope.launch(persistDispatcher) {
            PredictionCacheStore.delete(appContext)
            AdoptionStore.delete(appContext)
        }
    }

    /**
     * 作废当前待发射/in-flight 请求（新编码开始 / 离开预测态时调用）。
     * 必须轻量（主线程）：只做 epoch 自增 + cancel Job，不触碰 IO。
     */
    fun invalidate() {
        epoch++
        requestJob?.cancel()
        requestJob = null
        inFlightPrefetch = false
    }

    /**
     * 当前窗口词序列快照（主线程只读）：供 Service 的自动补标点判定
     *（AutoPunctPolicy 需要知道前文末词是否已带标点）与融合排除。
     */
    fun contextWords(): List<String> = window.words()

    /** 采纳攒批是否为空（主线程只读）：设置页导出入口的空态判定（S7） */
    fun hasAdoptionData(): Boolean = adoption.size() > 0

    /**
     * 导出采纳攒批数据文件（S7 攒批固化半自动化）：强制把当前内存态（含未
     * 到防抖时点的脏数据）落盘后经 [onReady] 在主线程交付磁盘文件；空态
     * 交付 null。等待装载完成再写盘，防装载前的部分内存态覆盖磁盘历史数据
     *（与 [ensureAdoptionLoaded] 的全量落盘语义一致）。
     */
    fun exportAdoptions(onReady: (File?) -> Unit) {
        if (!hasAdoptionData()) {
            onReady(null)
            return
        }
        val signal = ensureAdoptionLoaded()
        scope.launch {
            withTimeoutOrNull(EXPORT_LOAD_TIMEOUT_MS) { signal.await() }
            // 此处已回主线程（scope 为 Main）：主线程取快照与既有约定一致
            val snapshot = adoption.snapshot()
            withContext(persistDispatcher) {
                AdoptionStore.save(appContext, snapshot)
            }
            onReady(AdoptionStore.file(appContext))
        }
    }

    /**
     * 重置会话状态（onStartInput 切换输入框 / onFinishInputView 时调用）。
     *
     * 词窗口与限流窗口纯内存零持久化，会话结束即清，防跨输入框上下文泄漏；
     * **LRU 缓存不再随会话清空**（§4.3 持久化后的语义变更：重复语境跨会话
     * 复用才是缓存价值所在），缓存内容不含编辑器原文，无泄漏面。
     */
    fun reset() {
        window.clear()
        rateWindow.clear()
        invalidate()
    }

    /**
     * 首次需要缓存时装载磁盘快照（IO 线程，幂等）。
     * 主线程仅发起协程，零 IO；装载完成后 [cacheLoaded] 置位并 complete
     * 装载信号（S9 预热事件触发点）。返回装载信号供等待方复用。
     */
    private fun ensureCacheLoaded(): CompletableDeferred<Unit> {
        val signal = cacheLoadSignal
        if (signal.isCompleted) return signal
        if (loadRequested) return signal
        loadRequested = true
        scope.launch(persistDispatcher) {
            val entries = PredictionCacheStore.load(appContext)
            if (entries.isNotEmpty()) cache.restore(entries)
            cacheLoaded = true
            signal.complete(Unit)
        }
        return signal
    }

    /** 装载是否已发起（与 [cacheLoaded] 完成态分离：S4 落盘守卫以完成态为准） */
    private var loadRequested = false

    /** 异步落盘缓存快照（IO 线程）；装载未完成时跳过避免写入空快照覆盖旧数据（S4：守卫以装载完成态为准） */
    private fun persistCache() {
        if (!cacheLoaded) return
        val snapshot = cache.snapshot()
        scope.launch(persistDispatcher) {
            PredictionCacheStore.save(appContext, snapshot)
        }
    }

    /**
     * 首次采纳时装载磁盘攒批（IO 线程，幂等）：全量落盘语义要求内存态
     * 含历史数据，否则首次 flush 会以新进程的部分数据覆盖旧文件。
     * 装载完成前的少量新记录若被 restore 覆盖，仅损失计数，无正确性影响。
     * 返回装载信号供导出等等待方复用（S7）。
     */
    private fun ensureAdoptionLoaded(): CompletableDeferred<Unit> {
        val signal = adoptionLoadSignal
        if (signal.isCompleted || adoptionLoaded) return signal
        adoptionLoaded = true
        scope.launch(persistDispatcher) {
            adoption.restore(AdoptionStore.load(appContext))
            signal.complete(Unit)
        }
        return signal
    }

    /** 攒批脏检查落盘（IO 线程）：仅在确有新增时写盘 */
    private fun flushAdoptionIfDirty() {
        if (!adoption.isDirty()) return
        val snapshot = adoption.snapshot()
        scope.launch(persistDispatcher) {
            AdoptionStore.save(appContext, snapshot)
        }
    }

    /**
     * 发起一次请求（delayMs=0 为立即）：记录限流基准 → epoch 自增并捕获
     * 当前 epoch 与窗口快照 → 子协程内防抖延迟后二次校验再发。
     *
     * @param prefetchContext 非空时为预取请求：使用该上下文而非当前窗口，
     *        且结果到达时不经缓存命中短路二次分发（[inFlightPrefetch]）
     */
    private fun scheduleRequest(delayMs: Long, prefetchContext: List<String>? = null) {
        // 费用熔断：滚动一分钟内真实请求超限则放弃（缓存命中不占配额，S2 经 RequestRateWindow）
        val now = SystemClock.elapsedRealtime()
        if (!rateWindow.tryRecord(now)) {
            Log.w(TAG, "LLM 预测请求达每分钟上限，本次放弃")
            return
        }
        requestJob?.cancel()
        lastAttemptMs = now
        LlmPredictionStats.onRequestStarted(now)
        val requestEpoch = ++epoch
        val snapshot = prefetchContext ?: window.words()
        inFlightPrefetch = prefetchContext != null
        // 流式增量局部累计器：仅在 requestJob 内部读写（launch 体内与回调
        // 投递均在 Main 上下文），无需加锁；不复用 cache（请求完成前未写入）
        val streamed = ArrayList<String>(5)
        // 首批交付标记：首条感知延迟每次请求只记账一次（流式首行或整包）
        var firstDelivered = false
        requestJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            // 发射前放弃：防抖窗口内窗口已变（新的上屏），结果到达也必然过期；
            // 请求未真正发出 → 退还限流配额（S2：防放弃请求挤占真实请求额度）；
            // 预取请求以上下文快照为准，窗口变化不使其作废（其本就面向未来上下文）
            if (prefetchContext == null && window.words() != snapshot) {
                rateWindow.refundLast()
                return@launch
            }
            val prefetch = inFlightPrefetch
            // 流式增量分发（IO 线程回调 → 主线程投递）：累计列表经 epoch 复检后
            // 交付 onResult；epoch 已过期（新上屏/新编码）则静默吞掉后续增量
            val result = LlmPredictor.predictStream(appContext, snapshot) { fresh ->
                streamed.addAll(fresh)
                val cumulative = streamed.toList()
                scope.launch {
                    // 预取请求面向未来上下文，中间增量同样不得渲染（仅入缓存）
                    if (!prefetch && requestEpoch == epoch) {
                        if (!firstDelivered) {
                            firstDelivered = true
                            LlmPredictionStats.onFirstCandidate(SystemClock.elapsedRealtime())
                        }
                        onResult?.invoke(requestEpoch, cumulative, snapshot)
                    }
                }
            }
            val candidates = result.getOrElse { err ->
                // 失败文案已在 LlmPredictor 内脱敏（仅状态码/通用描述，不含用户词）；
                // 「开关已开但无续写词」排查首看本行（HTTP 401/超时/非 HTTPS 等）
                Log.w(TAG, "LLM 预测请求失败，本次放弃: ${err.message}")
                return@launch
            }
            // epoch 过期守卫：请求期间有新上屏/新编码/离开预测态则丢弃，不渲染不缓存
            if (requestEpoch != epoch) return@launch
            if (prefetchContext == null && window.words() != snapshot) return@launch
            cache.put(snapshot, candidates)
            persistCache()
            if (prefetch) {
                // 预取结果入缓存即达成目标：不分发（防与用户实际动作的分发竞争），
                // 也不触发新的预取（防自循环）。用户采纳后自然经缓存命中取用
                return@launch
            }
            if (!firstDelivered) {
                // 非流式/单批路径：整包交付即首批
                LlmPredictionStats.onFirstCandidate(SystemClock.elapsedRealtime())
            }
            onResult?.invoke(requestEpoch, candidates, snapshot)
        }
    }
}
