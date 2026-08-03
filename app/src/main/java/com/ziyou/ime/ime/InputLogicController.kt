package com.ziyou.ime.ime

import android.content.ClipDescription
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.image.ImageSupportLevel
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.core.t9.ReplaceCommand
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
 * - [commitListeners]：编辑器路径上屏后的横切监听（如等级计分），由组合根装配注入，
 *   本类不再硬编码依赖具体业务单例；回调参数为脱敏的 Unicode 码点数。
 *
 * 线程模型与原实现一致：`engine.api.*` 为挂起调用（自动切到 Rime 线程），
 * UI 渲染经 [Callbacks.renderContext] 在主线程执行。
 */
class InputLogicController(
    private val engine: RimeEngine,
    private val scope: CoroutineScope,
    private val keyRecordStack: KeyRecordStack,
    private val callbacks: Callbacks,
    private val commitListeners: List<(codePoints: Int) -> Unit> = emptyList()
) {

    companion object {
        private const val TAG = "InputLogicController"

        /** 慢按键告警阈值（ms）：引擎单键耗时超过即打 Log.w，便于发现耗时退化 */
        private const val SLOW_KEY_WARN_MS = 100L

        /**
         * 编码串长度上限：真机压测（REA-AN00）显示 T9 混合数字编码超过 ~30 键后，
         * script_translator 组句搜索的音节切分组合爆炸（单键耗时 300~450ms 且随长度
         * 超线性增长，全部串行在 Rime 线程上造成按键积压）。达上限后丢弃新增
         * 编码键（退格/回车/空格选词等功能键不受限），与主流输入法编码上限行为一致。
         */
        internal const val MAX_INPUT_LENGTH = 30
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

    /** 控制器需要 Service 提供的能力：编辑器连接、编辑器信息与主线程 UI 渲染。 */
    interface Callbacks {
        /** 当前编辑器输入连接（上屏 / 删除字符用）。 */
        fun currentInputConnection(): InputConnection?

        /** 当前编辑器信息（commitContent 富媒体提交需读取其可接收的 MIME 类型）。 */
        fun currentEditorInfo(): EditorInfo?

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

        /** 回车（Rime 无编码消费时的回车路由，如 AI 面板触发发送），默认无操作 */
        fun onEnter() {}
    }

    /**
     * 非空时上屏文本改道注入该目标（技能面板输入框）；null = 默认宿主编辑器。
     * 仅在主线程读写（技能面板开关与 Bridge 均在主线程）。
     */
    @Volatile
    var commitTarget: CommitTarget? = null

    /**
     * 最近一次引擎上下文的编码串长度（processKey/updateUI 在 [inputMutex] 内更新）。
     * 仅作超限预判缓存；选词/面板清编码等旁路可能使其过期，拒键前必经实时确认。
     */
    private var lastInputLength = 0

    /**
     * 最近一次引擎上下文的高亮候选缓存（每次引擎交互成功后用返回上下文更新）。
     * 按键路径的分段确认同步专用：分段确认后引擎菜单已切到下一音节，
     * 只有**按键前**的高亮候选才是本次被确认的候选（空格选高亮项）；
     * 缓存零额外引擎调用，不破坏热路径性能约束。
     */
    private var lastHighlightedCandidate: CandidateProto? = null

    /**
     * 核心按键处理：将按键发送给 Rime 引擎并处理返回结果。
     * 被 Rime 消费则取 commit 文本上屏 + 刷新 UI；未消费则退格删字符、回车按编辑器语义
     * 落地（见 [handleEnterKey]）或可打印字符直接上屏。
     * 热路径走 [RimeApi.processKeyBulk]：processKey/getCommit/getContext 单次引擎调度完成，
     * 相比逐个调用减少 2 次主线程↔Rime 线程往返与 2 次 JNI 跨界。
     */
    suspend fun processKey(keyCode: Int, mask: Int) = inputMutex.withLock {
        try {
            // 编码超限防护：继续追加编码键只会放大组句搜索耗时（见 MAX_INPUT_LENGTH）。
            // 缓存命中时实时取一次引擎编码长度确认（防选词/清编码旁路造成的过期缓存误拒），
            // 确认路径仅在病态长编码区触发，不影响正常输入热路径。
            if (lastInputLength >= MAX_INPUT_LENGTH && isComposingKey(keyCode, mask)) {
                val realLength = engine.api.getContext()?.input?.length ?: 0
                lastInputLength = realLength
                if (realLength >= MAX_INPUT_LENGTH) {
                    Log.w(TAG, "编码长度达上限($MAX_INPUT_LENGTH)，丢弃编码键 $keyCode")
                    return@withLock
                }
            }

            val startMs = SystemClock.elapsedRealtime()
            val result = engine.api.processKeyBulk(keyCode, mask)
            val costMs = SystemClock.elapsedRealtime() - startMs
            if (costMs >= SLOW_KEY_WARN_MS) {
                Log.w(TAG, "慢按键: processKeyBulk($keyCode) 耗时 ${costMs}ms" +
                    " (编码长度=${result.context?.input?.length ?: lastInputLength})")
            }

            if (result.consumed) {
                lastInputLength = result.context?.input?.length ?: 0
                // Rime消费了这个按键，检查是否有commit文本
                result.commit?.text?.let { text ->
                    // 将文本提交到当前编辑器
                    commitAndCount(text)
                    keyRecordStack.clear()
                }
                if (result.commit == null) {
                    // 消费但无 commit：可能是按键触发的分段确认（空格选高亮候选、
                    // select_keys 数字选词等，所选候选仅覆盖编码前缀）。与点击候选
                    // 同源的引擎语义，须同步九宫格状态机确认段，否则编码区预览
                    // 因确认偏移不可信回退为 Rime 原始 preedit（已选汉字后残留数字）。
                    // 被确认的候选 = 按键前的高亮候选（分段确认后菜单已切到下一音节）
                    syncStackAfterKeyPartialConfirm(result.context, lastHighlightedCandidate)
                }
                // 更新按键前高亮缓存（供下一次按键的分段确认同步）
                lastHighlightedCandidate = highlightedCandidate(result.context)
                // 用随批量结果返回的上下文刷新候选词与编码区UI；若引擎已启用
                // librime-predict，commit 后的预测词会出现在 context.menu 中随本次刷新一并展示
                withContext(Dispatchers.Main) {
                    callbacks.renderContext(result.context)
                }
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
                    }
                    // 回车键：Rime 只在有编码时消费 Return，无编码时由本层按目标语义落地
                    keyCode == KeyCode.XK_Return -> handleEnterKey()
                    // 可打印字符且Rime未处理，直接提交
                    keyCode in 0x20..0x7E && mask == 0 -> {
                        val char = keyCode.toChar().toString()
                        commitAndCount(char)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processKey异常: ${e.message}", e)
        }
    }

    /**
     * 处理候选词点击：选词、取 commit 上屏、刷新 UI。
     *
     * 分段确认（主流输入法行为）：所选候选仅覆盖编码前缀时（如 nihao 选“你”），
     * 引擎无 commit，但内部已确认该段（preedit 变为“你hao”，候选切到下一段）。
     * 此时需把所选候选的注音音节同步进九宫格状态机（[KeyRecordStack.confirmLeading]），
     * 否则后续侧栏选拼音的替换偏移会错位；同步失败则整栈 clear 降级。
     *
     * @param tapped 视图层传来的被点候选本体（含注音 comment）；优先于引擎当前页
     *        menu 查找，保证跨页点击旧页候选时分段确认同步仍能取到注音。
     */
    fun selectCandidate(globalIndex: Int, tapped: CandidateProto? = null) {
        scope.launch {
            inputMutex.withLock {
                try {
                    val ctx = engine.api.getContext()
                    val menu = ctx?.menu
                    // 视图传来的是 Rime 全局候选索引（累积缓冲位置换算而来）。
                    // 用 global 模式选择，支持跨页累积缓冲中任意可见候选，
                    // 避免页内局部索引与视图累积索引双重转换导致的选词错位。
                    // 选词前取所选候选的注音（comment），供分段确认后同步状态机：
                    // 首选视图透传的被点候选；回退到全局索引落在当前页时从
                    // menu.candidates 直接取（跨页取不到则为 null，分段确认将
                    // 降级为清栈，syncStackAfterPartialSelect 对 null 安全）。
                    val selected = tapped ?: if (menu != null && menu.pageSize > 0) {
                        val pageStartGlobal = menu.pageNumber * menu.pageSize
                        menu.candidates.getOrNull(globalIndex - pageStartGlobal)
                    } else {
                        null
                    }
                    val success = engine.api.selectCandidate(globalIndex, global = true)
                    Log.d(TAG, "selectCandidate(global=$globalIndex) -> $success")

                    if (success) {
                        val commit = engine.api.getCommit()
                        val text = commit?.text
                        if (text != null) {
                            commitAndCount(text)
                            Log.d(TAG, "候选词提交: $text")
                            keyRecordStack.clear()
                        } else {
                            // 分段确认后立即补发 End：Navigator 消费后调用 BeginEditing 给已选段
                            // 打上 kSelectedBeforeEditing 标记。否则 express_editor 的首个退格
                            // 会走 ReopenPreviousSelection 撤销刚确认的选择而非删字，导致
                            // 退格重打/智能退格的删字计数与引擎实际行为错位（栈-引擎失配）。
                            engine.api.processKey(KeyCode.XK_End, 0)
                            // 无 commit = 分段确认，同步九宫格状态机（全键盘栈为空，天然跳过）
                            withContext(Dispatchers.Main) { syncStackAfterPartialSelect(selected) }
                        }
                        updateUI()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "选择候选词异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 分段确认后同步状态机（主线程）：按所选候选的注音音节合并栈头为确认段；
     * 注音缺失或与击键不匹配时整栈 clear 降级（宁可降级不可错位）。
     */
    private fun syncStackAfterPartialSelect(candidate: CandidateProto?) {
        if (keyRecordStack.isEmpty()) return
        val syllables = syllablesOf(candidate)
        val synced = candidate != null && syllables.isNotEmpty() &&
            keyRecordStack.confirmLeading(candidate.text, syllables)
        if (!synced) {
            Log.w(TAG, "分段确认同步失败，清栈降级 (comment=${candidate?.comment})")
            keyRecordStack.clear()
        }
    }

    /** 候选注音音节解析（comment 按 ' / 空格切分，仅保留纯字母段）。 */
    private fun syllablesOf(candidate: CandidateProto?): List<String> =
        candidate?.comment?.trim()
            ?.split('\'', ' ')
            ?.filter { seg -> seg.isNotEmpty() && seg.all { it.isLetter() } }
            .orEmpty()

    /**
     * 按键路径的分段确认同步（空格选高亮候选 / select_keys 等）：
     * 引擎已出现确认前缀（preedit 头部汉字，selStart > 0）而栈尚未记录时，
     * 以按键前高亮候选（[selected]）的注音为音节依据合并栈头。确认段文本取自
     * preedit 确认前缀（可能长于候选文本）；普通编码态（无确认前缀）与栈已有
     * 确认段的后续分段确认不在本路径处理（后者降级行为与历史一致）。
     */
    private fun syncStackAfterKeyPartialConfirm(context: ContextProto?, selected: CandidateProto?) {
        if (keyRecordStack.isEmpty() || keyRecordStack.hasConfirmed()) return
        val composition = context?.composition ?: return
        val selStart = composition.selStart
        if (selStart <= 0) return
        val preedit = composition.preedit ?: return
        val codePoints = preedit.codePointCount(0, preedit.length)
        if (selStart > codePoints) return
        val confirmedText = preedit.substring(0, preedit.offsetByCodePoints(0, selStart))
        val syllables = syllablesOf(selected)
        if (syllables.isEmpty()) return
        if (!keyRecordStack.confirmLeading(confirmedText, syllables)) {
            Log.w(TAG, "按键分段确认同步失败，清栈降级 (comment=${selected?.comment})")
            keyRecordStack.clear()
        }
    }

    /** 上下文当前高亮候选（无高亮时取首位），供按键路径的分段确认同步。 */
    private fun highlightedCandidate(context: ContextProto?): CandidateProto? {
        val menu = context?.menu ?: return null
        val candidates = menu.candidates
        if (candidates.isEmpty()) return null
        val index = menu.highlightedCandidateIndex.takeIf { it in candidates.indices } ?: 0
        return candidates[index]
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
     *
     * 注：引擎存在已确认段时禁走本路径（replaceKey 底层 set_input 会清空确认段），
     * 改走 [retypeUnconfirmed]，由调用方按 [KeyRecordStack.hasConfirmed] 路由。
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

    /**
     * 安全删除末位未确认原始键（引擎存在已确认段时替代普通 BackSpace）。
     *
     * express_editor 的 BackSpace = RevertLastEdit，其 PopInput 后会调用
     * ReopenPreviousSegment：删掉**最后一个**未确认键时，引擎重组会在已确认段后
     * 补空段，Trim 弹掉空段后尾段恰为已确认段（kSelected），会被 Reopen 作废
     * （length 为原始整段跨度，original_end_pos != caret → kVoid），导致已确认
     * 汉字被打回数字候选态（kSelectedBeforeEditing 标记只保护 ReopenPreviousSelection
     * 分支，管不到这里）。改用与 [retypeUnconfirmed] 同源的无 Reopen 序列：
     * End 归位 → KP_Left 左移一格 → Delete 前向删除。
     *
     * 调用方（Service 退格分支）应已同步弹出栈尾未确认键；仅在
     * [KeyRecordStack.hasConfirmed] 为 true 时路由到本方法。
     */
    fun deleteUnconfirmedBackward() {
        scope.launch {
            inputMutex.withLock {
                try {
                    engine.api.processKey(KeyCode.XK_End, 0)
                    engine.api.processKey(KeyCode.XK_KP_Left, 0)
                    engine.api.processKey(KeyCode.XK_Delete, 0)
                    updateUI()
                } catch (e: Exception) {
                    Log.e(TAG, "deleteUnconfirmedBackward异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 「退格重打」：引擎存在已确认段时替代 replaceKey 的编码更新路径。
     *
     * replaceKey 底层 set_input 会清空引擎内全部已确认段（已确认的“你”会被打回拼音）；
     * 本方法改用逐键方式更新未确认部分，引擎已确认前缀全程保留。
     *
     * 删除必须用「KP_Left 定位 + Delete 前向删」而**不能用 BackSpace**：
     * express_editor 的 BackSpace = RevertLastEdit，其 PopInput 后会调用
     * ReopenPreviousSegment——删到确认边界时 Trim 弹掉空尾段后，尾段恰为
     * 已确认段（kSelected），会被 Reopen 作废（length 为原始整段跨度，
     * original_end_pos != caret → kVoid），导致已确认汉字被打回候选态；
     * 而 Delete（DeleteChar → DeleteInput）全程无任何 Reopen 逻辑。
     * KP_Left 经 Navigator::LeftByChar 精确按字符左移（Stacked 布局下
     * selector 不绑定 KP_Left，不会被截胡），且 BeginMove 顺带维护编辑标记。
     *
     * 非热路径，持 [inputMutex] 串行执行，末尾一次性刷 UI。
     *
     * @param deleteCount 待删除的未确认原始键数（重打前 [KeyRecordStack.unconfirmedRawChars] 长度）
     * @param retype      重打后的未确认编码串（重打后 [KeyRecordStack.unconfirmedRawChars]）
     */
    fun retypeUnconfirmed(deleteCount: Int, retype: String) {
        scope.launch {
            inputMutex.withLock {
                try {
                    // 光标归位到编码串末尾（常规流程下已在末尾，防御性归位，
                    // 保证后续 KP_Left 计数以末尾为基准）
                    engine.api.processKey(KeyCode.XK_End, 0)
                    // 光标精确左移到确认边界，再前向删除全部未确认原始键
                    repeat(deleteCount) { engine.api.processKey(KeyCode.XK_KP_Left, 0) }
                    repeat(deleteCount) { engine.api.processKey(KeyCode.XK_Delete, 0) }
                    // 删除后光标正好在编码串末尾（确认边界），逐键重打新串
                    for (ch in retype) engine.api.processKey(ch.code, 0)
                    updateUI()
                } catch (e: Exception) {
                    Log.e(TAG, "retypeUnconfirmed异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 回车键落地（Rime 未消费，即当前无编码）。
     *
     * - 上屏目标为面板时路由给面板（如 AI 面板触发发送）。
     * - 否则按 [EnterKeyBehavior] 解析宿主编辑器语义：声明了搜索 / 发送 / 前往等动作的
     *   单行输入框走 `performEditorAction`；多行或无动作的输入框补发一对
     *   ENTER 按键事件（而非 `commitText("\n")`），使换行符插入与「监听回车键」的
     *   编辑器（搜索框、聊天框、终端类应用）都能正确响应。
     */
    private suspend fun handleEnterKey() {
        val target = commitTarget
        if (target != null) {
            withContext(Dispatchers.Main) { target.onEnter() }
            return
        }
        val ic = callbacks.currentInputConnection() ?: return
        val action = EnterKeyBehavior.actionOf(callbacks.currentEditorInfo())
        if (action != EnterKeyBehavior.ACTION_NEWLINE) {
            ic.performEditorAction(action)
            return
        }
        sendEnterKeyEvents(ic)
    }

    /**
     * 向编辑器补发一对（按下 + 抬起）ENTER 物理按键事件。
     *
     * 标记 [KeyEvent.FLAG_SOFT_KEYBOARD] + [KeyEvent.FLAG_KEEP_TOUCH_MODE] 并使用
     * [KeyCharacterMap.VIRTUAL_KEYBOARD] 设备号，与系统输入法 `sendDownUpKeyEvents`
     * 行为一致：编辑器既可按普通换行处理，也可在 `onKeyDown` 中识别为回车。
     */
    private fun sendEnterKeyEvents(ic: InputConnection) {
        val downTime = SystemClock.uptimeMillis()
        ic.sendKeyEvent(enterKeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN))
        ic.sendKeyEvent(enterKeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP))
    }

    private fun enterKeyEvent(downTime: Long, eventTime: Long, action: Int) = KeyEvent(
        downTime,
        eventTime,
        action,
        KeyEvent.KEYCODE_ENTER,
        0,
        0,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
    )

    /** 侧栏自定义符号直接上屏（无活跃编码时可见，直接提交内容）。 */
    fun commitSideSymbol(value: String) {
        if (value.isEmpty()) return
        commitAndCount(value)
    }

    /**
     * 直接向宿主编辑器上屏（绕过 [commitTarget] 路由），用于「面板打开期间仍需
     * 把内容送进真实输入框」的场景，如 AI 答案上屏。仍回调 [commitListeners] 计分。
     */
    fun commitDirectToEditor(text: CharSequence) {
        if (text.isEmpty()) return
        callbacks.currentInputConnection()?.commitText(text, 1)
        val codePoints = Character.codePointCount(text, 0, text.length)
        commitListeners.forEach { it(codePoints) }
    }

    /**
     * 当前编辑器是否接受图片富媒体（据此决定“发送图片”是否可用）。
     * 检测委托 [EditorImageSupport]：[EditorInfo] 的 contentMimeTypes 动态检测
     * 为主（微信等聊天框会声明可接收类型），ImageCapableApp 白名单兜底；
     * 未命中时返回 false，此时图片出口应转为保存到相册。
     */
    fun acceptsImageContent(): Boolean =
        EditorImageSupport.detect(callbacks.currentEditorInfo()) == ImageSupportLevel.SEND

    /**
     * 直接向宿主编辑器提交图片（绕过 [commitTarget] 路由，Commit Content API，
     * Android 7.1+），用于「面板打开期间仍需把图片送进真实输入框」的场景，
     * 如 AI 答案转图发送；与 [commitDirectToEditor] 对称。
     *
     * @param uri 由本应用 FileProvider 暴露的 content:// URI
     * @param mimeType 如 "image/png"
     * @param description 无障碍描述（可空）
     * @return 是否提交成功（编辑器不支持 / 无连接时返回 false）
     *
     * 注：本方法仅“把图片交给输入框”，是否自动发送、是否弹确认框由接收方（如微信）决定，
     * 第三方输入法无法绕过接收方的确认逻辑直接发送。
     */
    fun commitImageToEditor(uri: Uri, mimeType: String, description: CharSequence? = null): Boolean =
        commitImageInternal(uri, mimeType, description)

    /** 富媒体提交内部实现：构造 InputContentInfo 并经 Commit Content API 提交。 */
    private fun commitImageInternal(uri: Uri, mimeType: String, description: CharSequence?): Boolean {
        val ic = callbacks.currentInputConnection() ?: return false
        val editorInfo = callbacks.currentEditorInfo() ?: return false
        val info = InputContentInfoCompat(
            uri,
            ClipDescription(description ?: "image", arrayOf(mimeType)),
            null
        )
        return try {
            InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                info,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "commitImage异常: ${e.message}", e)
            false
        }
    }

    /**
     * 统一的文本上屏出口：按 [commitTarget] 路由到技能面板或宿主编辑器。
     * 编辑器路径回调 [commitListeners]（如等级计分，仅传递脱敏码点数不触碰内容）；
     * 面板路径不回调（文本未真正发送给应用）。
     */
    private fun commitAndCount(text: CharSequence) {
        val target = commitTarget
        if (target != null) {
            target.commit(text)
            return
        }
        callbacks.currentInputConnection()?.commitText(text, 1)
        if (text.isNotEmpty()) {
            val codePoints = Character.codePointCount(text, 0, text.length)
            commitListeners.forEach { it(codePoints) }
        }
    }

    /**
     * 是否为会追加编码串的键（超限防护的限制对象）：无修饰键的可打印 ASCII，
     * 含数字/字母/撇号分词符；排除空格（T9 选首候选）与 XK_* 功能键（退格/回车/方向等）。
     */
    private fun isComposingKey(keyCode: Int, mask: Int): Boolean =
        mask == 0 && keyCode in 0x21..0x7E

    /**
     * 从 Rime 获取最新上下文并在主线程刷新 UI。
     * 引擎已启用 librime-predict 时，commit 后的预测词位于 context.menu 中，
     * 经本方法走既有候选渲染与选词路径，无需专用处理。
     */
    private suspend fun updateUI() {
        try {
            val context: ContextProto? = engine.api.getContext()
            lastInputLength = context?.input?.length ?: 0
            // 同步按键前高亮缓存（选词/翻页等旁路后的最新高亮）
            lastHighlightedCandidate = highlightedCandidate(context)
            withContext(Dispatchers.Main) {
                callbacks.renderContext(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateUI异常: ${e.message}", e)
        }
    }
}

