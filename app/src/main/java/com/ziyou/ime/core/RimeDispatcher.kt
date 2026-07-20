package com.ziyou.ime.core

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rime引擎单线程调度器
 *
 * 所有librime API调用必须在同一线程执行（librime非线程安全）。
 * 此调度器创建一个专属的单线程Executor，通过协程分发机制
 * 确保所有Rime操作顺序执行，避免数据竞争。
 *
 * 使用方式：
 * ```
 * val result = rimeDispatcher.dispatch {
 *     RimeNative.processRimeKey(keycode, mask)
 * }
 * ```
 */
class RimeDispatcher {

    companion object {
        private const val TAG = "RimeDispatcher"
        /** 任务等待超时时间（毫秒） */
        private const val JOB_TIMEOUT_MS = 5000L
    }

    /** 专属单线程Executor */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RimeDispatcher-Thread").apply {
            isDaemon = true
        }
    }

    /** 协程调度器，绑定到单线程Executor */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** 是否已关闭 */
    private val isShutdown = AtomicBoolean(false)

    /**
     * 在Rime专属线程上执行操作
     * @param block 要执行的操作（内部可安全调用RimeNative方法）
     * @return 操作结果
     */
    suspend fun <T> dispatch(block: () -> T): T {
        if (isShutdown.get()) {
            throw IllegalStateException("RimeDispatcher 已关闭")
        }
        return withContext(dispatcher) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Rime操作执行异常: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * 在Rime专属线程上执行操作（带超时）
     * @param timeoutMs 超时毫秒数
     * @param block 要执行的操作
     * @return 操作结果，超时返回null
     */
    suspend fun <T> dispatchWithTimeout(timeoutMs: Long = JOB_TIMEOUT_MS, block: () -> T): T? {
        if (isShutdown.get()) return null
        return try {
            withTimeout(timeoutMs) {
                dispatch(block)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Rime操作超时 (${timeoutMs}ms)")
            null
        }
    }

    /**
     * 关闭调度器，释放线程资源
     * 调用后不再接受新任务
     */
    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            executor.shutdown()
            Log.i(TAG, "RimeDispatcher 已关闭")
        }
    }
}
