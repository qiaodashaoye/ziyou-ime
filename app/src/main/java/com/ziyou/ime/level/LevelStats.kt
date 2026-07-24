package com.ziyou.ime.level

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上屏计分的热路径入口（内存计数 + 防抖批量落盘）。
 *
 * 设计约束（详见《等级体系可行性方案》第 6.2 节 / 热路径性能规范）：
 * - [onCommit] 处于输入热路径，仅做 O(1) 原子自增，**绝不在此写磁盘**。
 * - 达到提交次数或字符数阈值时才触发一次后台异步落盘（[LevelRepository.accumulate]）。
 * - IME 生命周期节点（隐藏输入视图 / 销毁）主动调用 [flush]，避免尾部数据丢失。
 *
 * 持有 applicationContext 不会造成泄漏。计数为脱敏聚合值，不含任何输入内容。
 */
object LevelStats {

    private const val TAG = "LevelStats"

    /** 累计到达此提交次数即落盘。 */
    private const val FLUSH_COMMIT_THRESHOLD = 50

    /** 累计到达此字符数即落盘（长文本粘贴等场景更快落盘）。 */
    private const val FLUSH_CHAR_THRESHOLD = 200

    /** 自上次落盘以来累计的上屏字符数。 */
    private val pendingChars = AtomicInteger(0)

    /** 自上次落盘以来的提交次数（用于防抖判定）。 */
    private val commitsSinceFlush = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    /** 后台落盘协程作用域（IO 线程）。 */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 初始化（在 IME 服务 onCreate 或 Application 中调用一次）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 热路径：文本上屏时调用。仅做内存自增，达到阈值才异步落盘。
     * @param codePointCount 本次上屏文本的 Unicode 码点数（脱敏，不含内容）
     */
    fun onCommit(codePointCount: Int) {
        if (codePointCount <= 0) return
        val chars = pendingChars.addAndGet(codePointCount)
        val commits = commitsSinceFlush.incrementAndGet()
        if (commits >= FLUSH_COMMIT_THRESHOLD || chars >= FLUSH_CHAR_THRESHOLD) {
            flush()
        }
    }

    /**
     * 主动落盘：将累计的待处理字符结算入 [LevelRepository]。
     * 在 onFinishInputView / onDestroy 等生命周期节点调用，或由 [onCommit] 达阈值触发。
     */
    fun flush() {
        val ctx = appContext ?: return
        // 原子取出并清零，避免与后续 onCommit 竞争重复计数
        val chars = pendingChars.getAndSet(0)
        commitsSinceFlush.set(0)
        if (chars <= 0) return
        flushScope.launch {
            try {
                LevelRepository.accumulate(ctx, chars)
            } catch (e: Exception) {
                Log.w(TAG, "落盘失败，回滚待处理字符数: ${e.message}")
                // 落盘失败则把字符数补回，等待下次落盘，避免丢分
                pendingChars.addAndGet(chars)
            }
        }
    }
}
