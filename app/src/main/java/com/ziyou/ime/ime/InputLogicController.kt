package com.ziyou.ime.ime

import android.util.Log
import android.view.inputmethod.InputConnection
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.core.t9.ReplaceCommand
import com.ziyou.ime.level.LevelStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 输入逻辑控制器。
 *
 * 从 [ZiYouInputMethodService] 剥离"与 Rime 引擎交互、上屏文本、刷新 UI"的核心输入路径，
 * 使 Service 聚焦于 Android 生命周期与视图装配。
 *
 * 协作对象：
 * - [engine]：Rime 引擎（经 DI 容器提供，可替换/可测试）。
 * - [scope]：Service 协程作用域（`Dispatchers.Main`），控制器内部按需切到 Rime 线程 / 回到主线程。
 * - [keyRecordStack]：与 Service 共享的九宫格状态机（选词/退格需同步清理与替换）。
 * - [callbacks]：向 Service 反向获取 [InputConnection] 与执行主线程 UI 渲染。
 *
 * 线程模型与原实现一致：`engine.api.*` 为挂起调用（自动切到 Rime 线程），
 * UI 渲染经 [Callbacks.renderContext] 在主线程执行。
 */
class InputLogicController(
    private val engine: RimeEngine,
    private val scope: CoroutineScope,
    private val keyRecordStack: KeyRecordStack,
    private val callbacks: Callbacks
) {

    companion object {
        private const val TAG = "InputLogicController"
    }

    /**
     * 输入事务串行化锁：保证「一次按键 = processKey→getCommit→getContext」整体原子执行。
     *
     * [com.ziyou.ime.core.RimeDispatcher] 的单线程只保证**单次** dispatch 原子，不保证一次
     * 按键内的多次 Rime 调用连续执行。快速连击时，不同按键的调用可能在 Rime 线程上交错，
     * 导致 commit/context 与按键错配（偶发丢字 / 候选错乱）。用 [Mutex] 将每个输入操作整体
     * 串行化；Kotlin [Mutex] 公平排队，天然保持按键先后顺序。
     */
    private val inputMutex = Mutex()

    /** 控制器需要 Service 提供的能力：编辑器连接与主线程 UI 渲染。 */
    interface Callbacks {
        /** 当前编辑器输入连接（上屏 / 删除字符用）。 */
        fun currentInputConnection(): InputConnection?

        /** 主线程：根据最新 Rime 上下文刷新候选词、编码区与拼音侧栏。 */
        fun renderContext(context: ContextProto?)
    }

    /**
     * 上屏目标抽象（技能插件系统 Phase 3 输入路由）。
     *
     * 默认（[commitTarget] 为 null）直达宿主编辑器 [InputConnection]；
     * 技能面板申请输入焦点后切到面板目标（文本经 JS 注入面板输入框）。
     * Rime 编码/候选/选词全链路不变，仅最后一步 commit 落点不同。
     */
    interface CommitTarget {
        /** 提交文本到目标 */
        fun commit(text: CharSequence)

        /** 退格（Rime 无编码可删时的直接删字） */
        fun deleteBackward()
    }

    /**
     * 非空时上屏文本改道注入该目标（技能面板输入框）；null = 默认宿主编辑器。
     * 仅在主线程读写（技能面板开关与 Bridge 均在主线程）。
     */
    @Volatile
    var commitTarget: CommitTarget? = null

    /**
     * 核心按键处理：将按键发送给 Rime 引擎并处理返回结果。
     * 被 Rime 消费则取 commit 文本上屏 + 刷新 UI；未消费则退格删字符或可打印字符直接上屏。
     */
    suspend fun processKey(keyCode: Int, mask: Int) = inputMutex.withLock {
        try {
            val consumed = engine.api.processKey(keyCode, mask)
            Log.d(TAG, "processKey($keyCode, $mask) -> consumed=$consumed")

            if (consumed) {
                // Rime消费了这个按键，检查是否有commit文本
                val commit = engine.api.getCommit()
                commit?.text?.let { text ->
                    // 将文本提交到当前编辑器
                    commitAndCount(text)
                    Log.d(TAG, "commitText: $text")
                    keyRecordStack.clear()
                }
                // 更新候选词和编码区UI；若引擎已启用 librime-predict，
                // commit 后的预测词会出现在 context.menu 中随本次刷新一并展示
                updateUI()
            } else {
                // Rime未消费，某些键可能需要直接输出
                when {
                    // 退格键：Rime无编码可删时，直接删除目标（编辑器/技能面板）中的字符
                    keyCode == KeyCode.XK_BackSpace -> {
                        val target = commitTarget
                        if (target != null) {
                            target.deleteBackward()
                        } else {
                            callbacks.currentInputConnection()?.deleteSurroundingText(1, 0)
                        }
                        Log.d(TAG, "直接删除: deleteBackward (target=${target != null})")
                    }
                    // 可打印字符且Rime未处理，直接提交
                    keyCode in 0x20..0x7E && mask == 0 -> {
                        val char = keyCode.toChar().toString()
                        commitAndCount(char)
                        Log.d(TAG, "直接提交字符: $char")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processKey异常: ${e.message}", e)
        }
    }

    /** 处理候选词点击：选词、取 commit 上屏、刷新 UI。 */
    fun selectCandidate(index: Int) {
        scope.launch {
            inputMutex.withLock {
                try {
                    val success = engine.api.selectCandidate(index)
                    Log.d(TAG, "selectCandidate($index) -> $success")

                    if (success) {
                        val commit = engine.api.getCommit()
                        commit?.text?.let { text ->
                            commitAndCount(text)
                            Log.d(TAG, "候选词提交: $text")
                            keyRecordStack.clear()
                        }
                        updateUI()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "选择候选词异常: ${e.message}", e)
                }
            }
        }
    }

    /** 处理翻页。@param forward true=下一页, false=上一页 */
    fun changePage(forward: Boolean) {
        scope.launch {
            inputMutex.withLock {
                try {
                    // backward参数含义：true=向前翻（上一页）
                    val success = engine.api.changePage(backward = !forward)
                    if (success) {
                        updateUI()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "翻页异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 处理拼音侧栏选词：锁定首个未确定音节对应的 T9 段并送 Rime 替换。
     *
     * 调用方应已在主线程同步执行 [KeyRecordStack.pushPinyinSelectAction] 得到 [command]，
     * 以保证与退格等其他栈操作的时序一致。
     */
    fun selectPinyin(command: ReplaceCommand) {
        scope.launch {
            inputMutex.withLock {
                try {
                    // 用选定拼音替换编码中对应的 T9 键序列（含末尾分词符），锁定该音节
                    engine.api.replaceKey(command.caretPos, command.length, command.replacement)
                    // replaceKey 会把光标停在已锁定拼音之后（编码串中部），而 Rime 仅组织光标
                    // 之前的片段，导致候选只剩「已锁定音节」的单字。将光标移到编码串末尾，令 Rime
                    // 组织「已锁定拼音 + 后续未确定音节」的完整组合候选（如 guo'486 → 组词候选）。
                    engine.api.processKey(KeyCode.XK_End, 0)
                    updateUI()
                } catch (e: Exception) {
                    Log.e(TAG, "selectPinyin异常: ${e.message}", e)
                }
            }
        }
    }

    /** 九宫格智能退格：将已锁定拼音替换回原 T9 键并刷新 UI。 */
    fun restorePinyin(command: ReplaceCommand) {
        scope.launch {
            inputMutex.withLock {
                try {
                    engine.api.replaceKey(command.caretPos, command.length, command.replacement)
                    updateUI()
                } catch (e: Exception) {
                    Log.e(TAG, "restorePinyin异常: ${e.message}", e)
                }
            }
        }
    }

    /** 侧栏自定义符号直接上屏（无活跃编码时可见，直接提交内容）。 */
    fun commitSideSymbol(value: String) {
        if (value.isEmpty()) return
        commitAndCount(value)
    }

    /**
     * 统一的文本上屏出口：按 [commitTarget] 路由到技能面板或宿主编辑器。
     * 编辑器路径做等级计分埋点（仅统计 Unicode 码点数，脱敏不触碰内容）；
     * 面板路径不计分（文本未真正发送给应用）。
     */
    private fun commitAndCount(text: CharSequence) {
        val target = commitTarget
        if (target != null) {
            target.commit(text)
            return
        }
        callbacks.currentInputConnection()?.commitText(text, 1)
        if (text.isNotEmpty()) {
            LevelStats.onCommit(Character.codePointCount(text, 0, text.length))
        }
    }

    /**
     * 从 Rime 获取最新上下文并在主线程刷新 UI。
     * 引擎已启用 librime-predict 时，commit 后的预测词位于 context.menu 中，
     * 经本方法走既有候选渲染与选词路径，无需专用处理。
     */
    private suspend fun updateUI() {
        try {
            val context: ContextProto? = engine.api.getContext()
            withContext(Dispatchers.Main) {
                callbacks.renderContext(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateUI异常: ${e.message}", e)
        }
    }
}

