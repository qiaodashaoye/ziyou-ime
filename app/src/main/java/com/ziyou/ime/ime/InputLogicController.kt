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
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.core.t9.ReplaceCommand
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.sdk.input.CommitSink
import com.ziyou.ime.sdk.input.EnterKeyBehavior
import com.ziyou.ime.sdk.input.InputHostAdapter
import com.ziyou.ime.sdk.input.InputSession
import com.ziyou.ime.sdk.state.CandidatesService
import com.ziyou.ime.sdk.state.PreeditController
import com.ziyou.ime.sdk.state.SchemaService
import kotlinx.coroutines.CoroutineScope

/**
 * 输入逻辑控制器（业务薄层）。
 *
 * SDK 拆分后（docs/SDK模块拆分重构方案.md §3.3）：通用输入管线（按键事务串行、
 * processKeyBulk、候选/翻页/T9 消歧、分段确认状态机同步）下沉到
 * [InputSession]（:ime-sdk）；本类只保留**业务**职责：
 * - [CommitTarget] 上屏改道路由（技能面板输入框）；
 * - 编辑器 [CommitSink] 实现（InputConnection + 回车语义 + 上屏监听）；
 * - [commitListeners] / [commitTextObservers] 横切回调（等级计分 / LLM 续写）；
 * - 图片富媒体上屏与侧栏符号等直通出口。
 *
 * 协作对象：
 * - [engine]：Rime 引擎（经 DI 容器提供，可替换/可测试）。
 * - [scope]：Service 协程作用域（`Dispatchers.Main`）。
 * - [keyRecordStack]：与 Service 共享的九宫格状态机（选词/退格需同步清理与替换）。
 * - [callbacks]：向 Service 反向获取 [InputConnection] 与执行主线程 UI 渲染。
 */
class InputLogicController(
    engine: RimeEngine,
    scope: CoroutineScope,
    keyRecordStack: KeyRecordStack,
    private val callbacks: Callbacks,
    private val commitListeners: List<(codePoints: Int) -> Unit> = emptyList(),
    private val commitTextObservers: List<(String) -> Unit> = emptyList()
) {

    companion object {
        private const val TAG = "InputLogicController"

        /** 编码上限常量转引 SDK（既有测试与调用方经本伴生对象访问，语义不变）。 */
        const val MAX_INPUT_LENGTH = InputSession.MAX_INPUT_LENGTH
    }

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
     * 上屏路由适配器（注入 [InputSession]）：每次取用按 [commitTarget] 现场解析——
     * 面板目标在场时改道注入面板，否则落到宿主编辑器 sink（含计分/文本观察者）。
     */
    private val hostAdapter = object : InputHostAdapter {
        override fun currentEditorInfo(): EditorInfo? = callbacks.currentEditorInfo()

        override fun currentCommitSink(): CommitSink? {
            val target = commitTarget
            return if (target != null) object : CommitSink {
                // 面板路径不回调计分/文本观察者——文本未真正进入应用编辑器
                override fun commitText(text: CharSequence) = target.commit(text)
                override fun deleteBackward() = target.deleteBackward()
                override fun onEnter() = target.onEnter()
            } else {
                editorSink
            }
        }

        override fun renderContext(context: ContextProto?) = callbacks.renderContext(context)
    }

    /** SDK 状态服务：编码区快照（StateFlow 事实源，视图可订阅）。 */
    val preeditController = PreeditController.newInstance(engine)

    /** SDK 状态服务：候选词快照与选/删/翻页操作。 */
    val candidatesService = CandidatesService.newInstance(engine)

    /** SDK 状态服务：方案/选项/用户数据。 */
    val schemaService = SchemaService.newInstance(engine)

    /** 通用输入管线（SDK）：按键事务、候选/翻页/T9 消歧、分段确认同步。 */
    private val session = InputSession(
        engine, scope, keyRecordStack, hostAdapter,
        preeditController = preeditController,
        candidatesService = candidatesService
    )

    /**
     * 宿主编辑器上屏 sink：commitText/deleteSurroundingText/回车语义落地，
     * 编辑器路径回调 [commitListeners]（如等级计分，仅传递脱敏码点数不触碰内容）
     * 与 [commitTextObservers]（LLM 续写，语义隔离）。
     */
    private val editorSink = object : CommitSink {
        override fun commitText(text: CharSequence) {
            callbacks.currentInputConnection()?.commitText(text, 1)
            notifyCommitObservers(text)
        }

        override fun deleteBackward() {
            callbacks.currentInputConnection()?.deleteSurroundingText(1, 0)
        }

        override fun onEnter() {
            val ic = callbacks.currentInputConnection() ?: return
            val action = EnterKeyBehavior.actionOf(callbacks.currentEditorInfo())
            if (action != EnterKeyBehavior.ACTION_NEWLINE) {
                ic.performEditorAction(action)
                return
            }
            sendEnterKeyEvents(ic)
        }
    }

    /** 编辑器路径上屏后的观察者通知（码点数脱敏监听 + 文本观察者）。 */
    private fun notifyCommitObservers(text: CharSequence) {
        if (text.isEmpty()) return
        val codePoints = Character.codePointCount(text, 0, text.length)
        commitListeners.forEach { it(codePoints) }
        // 文本观察者仅供 LLM 续写，语义与脱敏 commitListeners 隔离（热路径内存遍历）
        commitTextObservers.forEach { it(text.toString()) }
    }

    // ==================== 通用输入路径（委托 SDK InputSession） ====================

    /** 核心按键处理（热路径，委托 SDK）。 */
    suspend fun processKey(keyCode: Int, mask: Int) = session.processKey(keyCode, mask)

    /** 处理候选词点击（含分段确认状态机同步，委托 SDK）。 */
    fun selectCandidate(globalIndex: Int, tapped: CandidateProto? = null) =
        session.selectCandidate(globalIndex, tapped)

    /** 处理翻页（委托 SDK）。 */
    fun changePage(forward: Boolean) = session.changePage(forward)

    /** 拼音侧栏选词：锁定 T9 音节（委托 SDK）。 */
    fun selectPinyin(command: ReplaceCommand) = session.selectPinyin(command)

    /** 九宫格智能退格：锁定拼音还原为 T9 键（委托 SDK）。 */
    fun restorePinyin(command: ReplaceCommand) = session.restorePinyin(command)

    /** 安全删除末位未确认原始键（委托 SDK）。 */
    fun deleteUnconfirmedBackward() = session.deleteUnconfirmedBackward()

    /** 「退格重打」：已确认段存在时的编码更新路径（委托 SDK）。 */
    fun retypeUnconfirmed(deleteCount: Int, retype: String) =
        session.retypeUnconfirmed(deleteCount, retype)

    /** 清理引擎残留的预测态（委托 SDK）。 */
    fun clearStalePrediction() = session.clearStalePrediction()

    // ==================== 业务直通出口（保留在本层） ====================

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
        notifyCommitObservers(text)
    }

    /**
     * 自动补标点提交（预测候选采纳流程，见 AutoPunctPolicy）：走与普通上屏
     * 相同的 [commitAndCount] 出口（commitTarget 路由 + 计分 + 文本观察者）。
     * 调用方（Service）必须在主线程**同步**先于 selectCandidate 协程入队前调用，
     * 由 Main 队列 FIFO 保证标点先于预测词落编辑器。
     */
    fun commitAutoPunctuation(punct: String) = commitAndCount(punct)

    /**
     * 当前编辑器是否接受图片富媒体（据此决定"发送图片"是否可用）。
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
     * 注：本方法仅"把图片交给输入框"，是否自动发送、是否弹确认框由接收方（如微信）决定，
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
        hostAdapter.currentCommitSink()?.commitText(text)
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
}
