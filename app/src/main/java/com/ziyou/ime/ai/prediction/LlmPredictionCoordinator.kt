package com.ziyou.ime.ai.prediction

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ziyou.ime.core.prediction.CommitWordWindow
import com.ziyou.ime.core.prediction.ContextLruCache
import com.ziyou.ime.core.prediction.TriggerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LLM 智能续写协调器：上屏事件 → 触发决策 → 异步请求 → 结果分发。
 *
 * 编排职责（见 docs/智能预测可行性方案.md §4.2/§4.3）：
 * - 词窗口维护与 LRU 缓存命中短路（命中即免网络请求）；
 * - [TriggerPolicy] 触发决策：Trigger 即发、Debounce 延迟发、Skip 不发；
 * - 单 in-flight Job：新触发先取消旧 Job；防抖到期时窗口已变则发射前放弃；
 * - epoch 过期守卫：结果到达时上下文已变（新上屏/新编码/离开预测态）直接丢弃，
 *   防迟到的 LLM 词挂到新一轮编码的候选栏上被误点进编辑器。
 *
 * 线程模型：[onCommitText] / [invalidate] / [reset] 仅在主线程调用
 * （上屏出口与 renderContext 均在主线程），内部状态无需加锁；
 * 网络请求在内部 scope（Main + 子协程切 IO，由 [LlmPredictor] 完成）执行。
 * 隐私红线：日志中禁止出现词窗口内容。
 */
class LlmPredictionCoordinator(private val appContext: Context) {

    companion object {
        private const val TAG = "LlmPredictCoord"

        /** 滚动窗口时长（ms）：与 [MAX_REQUESTS_PER_MINUTE] 共同构成费用熔断 */
        private const val RATE_WINDOW_MS = 60_000L

        /** 每分钟真实网络请求上限（缓存命中不占配额） */
        private const val MAX_REQUESTS_PER_MINUTE = 20
    }

    /** 内部协程作用域：SupervisorJob 隔离单次请求失败，Main 分发保证回调主线程 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 最近上屏词窗口（纯内存，零持久化） */
    private val window = CommitWordWindow()

    /** 上下文 LRU 缓存：重复上下文直接命中，省一次网络请求 */
    private val cache = ContextLruCache()

    /** 单调递增 epoch：每次发起/作废请求自增，结果到达时校验是否过期 */
    private var epoch = 0L

    /** 最近一次请求发起时刻（SystemClock.elapsedRealtime），节流判定基准 */
    private var lastAttemptMs = 0L

    /** 当前等待中/in-flight 的请求 Job（单 Job 保证） */
    private var requestJob: Job? = null

    /** 最近真实请求发起时刻（滚动一分钟窗口）：费用熔断判定，超限转 Skip */
    private val attemptTimes = ArrayDeque<Long>()

    /**
     * 结果分发回调（主线程）：携带请求发起时的 epoch、候选列表与触发时的
     * 上下文词快照。由 Service 设置；仅在仍无活跃编码时才应渲染（Service 侧
     * 二次校验）。上下文词供融合层排除「刚上屏的词被模型复读回显」。
     */
    var onResult: ((epoch: Long, candidates: List<String>, contextWords: List<String>) -> Unit)? = null

    /**
     * 上屏文本入口（主线程，输入热路径）。
     *
     * 热路径成本 = 一次开关读（SharedPreferences 内存缓存）+ 词窗口 O(1)/O(N)
     * 内存写 + 纯函数决策；零磁盘 IO、零网络。开关关闭时仅一次布尔读即返回。
     */
    fun onCommitText(text: String) {
        if (!LlmPredictionConfig.isEnabled(appContext)) return
        window.add(text)
        val words = window.words()
        // 缓存命中短路：立即作废 in-flight 请求并以新 epoch 分发（续写链即时延续）。
        // 异步投递而非同步回调：同一 commit 流程的引擎渲染（renderContext）必然在
        // 其后执行并清空视图侧 LLM 尾部，同步追加会被紧随的渲染清掉；投递到 Main
        // 队列后渲染先完成（lastPredictionMode/引擎候选已刷新），结果方能存活。
        // epoch 复检防多次命中时旧分发覆盖新分发。
        val cached = cache.get(words)
        if (cached != null) {
            requestJob?.cancel()
            requestJob = null
            val dispatchEpoch = ++epoch
            scope.launch {
                if (dispatchEpoch == epoch) onResult?.invoke(dispatchEpoch, cached, words)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (val decision = TriggerPolicy.decide(text, now - lastAttemptMs, words)) {
            is TriggerPolicy.TriggerDecision.Trigger -> scheduleRequest(0L)
            is TriggerPolicy.TriggerDecision.Debounce -> scheduleRequest(decision.delayMs)
            is TriggerPolicy.TriggerDecision.Skip -> Unit
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
    }

    /**
     * 当前窗口词序列快照（主线程只读）：供 Service 的自动补标点判定
     *（AutoPunctPolicy 需要知道前文末词是否已带标点）与融合排除。
     */
    fun contextWords(): List<String> = window.words()

    /**
     * 重置全部状态（onStartInput 切换输入框 / onFinishInputView 时调用）：
     * 词窗口与缓存纯内存零持久化，会话结束即清，防跨输入框上下文泄漏。
     */
    fun reset() {
        window.clear()
        cache.clear()
        attemptTimes.clear()
        invalidate()
    }

    /**
     * 发起一次请求（delayMs=0 为立即）：记录限流基准 → epoch 自增并捕获
     * 当前 epoch 与窗口快照 → 子协程内防抖延迟后二次校验再发。
     */
    private fun scheduleRequest(delayMs: Long) {
        // 费用熔断：滚动一分钟内真实请求超限则放弃（缓存命中不占配额）
        val now = SystemClock.elapsedRealtime()
        while (attemptTimes.isNotEmpty() && now - attemptTimes.first() > RATE_WINDOW_MS) {
            attemptTimes.removeFirst()
        }
        if (attemptTimes.size >= MAX_REQUESTS_PER_MINUTE) {
            Log.w(TAG, "LLM 预测请求达每分钟上限，本次放弃")
            return
        }
        attemptTimes.addLast(now)
        requestJob?.cancel()
        lastAttemptMs = now
        val requestEpoch = ++epoch
        val snapshot = window.words()
        requestJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            // 发射前放弃：防抖窗口内窗口已变（新的上屏），结果到达也必然过期
            if (window.words() != snapshot) return@launch
            val candidates = LlmPredictor.predict(appContext, snapshot).getOrElse { err ->
                // 失败文案已在 LlmPredictor 内脱敏（仅状态码/通用描述，不含用户词），
                // 可安全入日志；「开关已开但无续写词」排查首看本行（HTTP 401/超时/非 HTTPS 等）
                Log.w(TAG, "LLM 预测请求失败，本次放弃: ${err.message}")
                return@launch
            }
            // epoch 过期守卫：请求期间有新上屏/新编码/离开预测态则丢弃，不渲染
            if (requestEpoch != epoch || window.words() != snapshot) return@launch
            cache.put(snapshot, candidates)
            onResult?.invoke(requestEpoch, candidates, snapshot)
        }
    }
}
