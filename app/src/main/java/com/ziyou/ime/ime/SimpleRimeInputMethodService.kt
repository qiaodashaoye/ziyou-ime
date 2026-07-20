package com.ziyou.ime.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.daemon.RimeSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 字由输入法服务主类
 *
 * 作为Android InputMethodService子类，核心职责：
 * 1. 管理Rime引擎生命周期（onCreate启动，onDestroy销毁）
 * 2. 处理按键事件，转发给RimeApi
 * 3. 管理输入视图（键盘+候选词）的显示/隐藏
 * 4. 将Rime输出（commit text）提交到编辑器
 * 5. 同步Rime上下文状态到UI（候选词、编码区）
 */
class SimpleRimeInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "SimpleRimeIMS"
    }

    /** 服务协程作用域，生命周期跟随Service */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 键盘视图引用 */
    private var keyboardView: SimpleKeyboardView? = null

    /** 候选词视图引用 */
    private var candidatesView: SimpleCandidatesView? = null

    // ===== 生命周期 =====

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InputMethodService onCreate")

        // 初始化Rime引擎（如果还未初始化）
        serviceScope.launch {
            try {
                if (!RimeSession.initialized) {
                    Log.i(TAG, "开始初始化Rime引擎...")
                    RimeSession.initialize(applicationContext, fullCheck = true)
                    Log.i(TAG, "Rime引擎初始化完成")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rime引擎初始化失败: ${e.message}", e)
            }
        }

        // 监听Rime消息（方案切换、选项变更等）
        serviceScope.launch {
            RimeSession.messageFlow.collectLatest { message ->
                handleRimeMessage(message)
            }
        }
    }

    /**
     * 创建输入视图（包含候选词栏和键盘）
     * 使用纯代码动态创建，不依赖XML布局资源
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")

        // 创建根容器：垂直LinearLayout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 创建候选词视图（顶部）
        candidatesView = SimpleCandidatesView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 候选词点击回调
            onCandidateClick = { index -> handleCandidateClick(index) }
            // 翻页回调
            onPageChange = { forward -> handlePageChange(forward) }
        }
        rootLayout.addView(candidatesView)

        // 创建键盘视图（底部）
        keyboardView = SimpleKeyboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 按键回调
            onKeyPress = { keyCode, mask -> handleSoftKeyPress(keyCode, mask) }
        }
        rootLayout.addView(keyboardView)

        return rootLayout
    }

    /**
     * 开始输入时调用
     * 清除之前的编码状态，准备新的输入会话
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")

        serviceScope.launch {
            try {
                // 清除之前的编码
                RimeSession.api.clearComposition()
                // 同步ASCII模式状态到键盘
                val isAscii = RimeSession.api.getOption("ascii_mode")
                withContext(Dispatchers.Main) {
                    keyboardView?.setChineseMode(!isAscii)
                }
                // 更新UI
                updateUI()
            } catch (e: Exception) {
                Log.e(TAG, "onStartInputView 处理异常: ${e.message}", e)
            }
        }
    }

    /**
     * 输入视图即将隐藏时调用
     * 清除当前编码
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")

        serviceScope.launch {
            try {
                RimeSession.api.clearComposition()
            } catch (e: Exception) {
                Log.w(TAG, "清除编码异常: ${e.message}")
            }
        }
    }

    // ===== 物理按键处理 =====

    /**
     * 处理物理键盘按键按下事件
     * 将Android KeyEvent转换为Rime keysym并发送
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)

        // 将Android keyCode转换为Rime keysym
        val isShifted = event.isShiftPressed
        val rimeKeyCode = KeyCode.androidKeyCodeToRimeKeyCode(keyCode, isShifted)
        if (rimeKeyCode == 0) {
            // 无法映射的键，交给系统处理
            return super.onKeyDown(keyCode, event)
        }

        val mask = KeyCode.getModifierMask(event)

        // 异步处理按键
        serviceScope.launch {
            processRimeKey(rimeKeyCode, mask)
        }
        return true
    }

    // ===== 软键盘按键处理 =====

    /**
     * 处理软键盘按键事件
     * @param keyCode Rime keysym 或自定义功能码
     * @param mask 修饰键mask
     */
    private fun handleSoftKeyPress(keyCode: Int, mask: Int) {
        when (keyCode) {
            // 中英文切换：设置Rime选项
            KeyCode.KEYCODE_SWITCH_LANGUAGE -> {
                serviceScope.launch {
                    try {
                        val currentAscii = RimeSession.api.getOption("ascii_mode")
                        RimeSession.api.setOption("ascii_mode", !currentAscii)
                        // 键盘视图已在View内部切换了显示，这里同步
                        keyboardView?.setChineseMode(currentAscii) // 反转
                        Log.d(TAG, "切换中英文: ascii_mode=${!currentAscii}")
                    } catch (e: Exception) {
                        Log.e(TAG, "切换中英文异常: ${e.message}", e)
                    }
                }
            }

            // 符号键盘切换（暂未实现完整符号键盘）
            KeyCode.KEYCODE_SYMBOL -> {
                Log.d(TAG, "符号键盘切换（待实现）")
            }

            // 普通按键：发送给Rime引擎
            else -> {
                serviceScope.launch {
                    processRimeKey(keyCode, mask)
                }
            }
        }
    }

    /**
     * 核心按键处理：将按键发送给Rime引擎，处理返回结果
     *
     * @param keyCode Rime keysym
     * @param mask 修饰键mask
     */
    private suspend fun processRimeKey(keyCode: Int, mask: Int) {
        try {
            val consumed = RimeSession.api.processKey(keyCode, mask)
            Log.d(TAG, "processKey($keyCode, $mask) -> consumed=$consumed")

            if (consumed) {
                // Rime消费了这个按键，检查是否有commit文本
                val commit = RimeSession.api.getCommit()
                commit?.text?.let { text ->
                    // 将文本提交到当前编辑器
                    currentInputConnection?.commitText(text, 1)
                    Log.d(TAG, "commitText: $text")
                }
                // 更新候选词和编码区UI
                updateUI()
            } else {
                // Rime未消费，某些键可能需要直接输出
                // 如果是可打印字符且Rime未处理，直接提交
                if (keyCode in 0x20..0x7E && mask == 0) {
                    val char = keyCode.toChar().toString()
                    currentInputConnection?.commitText(char, 1)
                    Log.d(TAG, "直接提交字符: $char")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processRimeKey异常: ${e.message}", e)
        }
    }

    // ===== 候选词操作 =====

    /**
     * 处理候选词点击
     * @param index 候选词在当前页的索引
     */
    private fun handleCandidateClick(index: Int) {
        serviceScope.launch {
            try {
                val success = RimeSession.api.selectCandidate(index)
                Log.d(TAG, "selectCandidate($index) -> $success")

                if (success) {
                    // 选词后检查是否有commit
                    val commit = RimeSession.api.getCommit()
                    commit?.text?.let { text ->
                        currentInputConnection?.commitText(text, 1)
                        Log.d(TAG, "候选词提交: $text")
                    }
                    // 更新UI
                    updateUI()
                }
            } catch (e: Exception) {
                Log.e(TAG, "选择候选词异常: ${e.message}", e)
            }
        }
    }

    /**
     * 处理翻页操作
     * @param forward true=下一页, false=上一页
     */
    private fun handlePageChange(forward: Boolean) {
        serviceScope.launch {
            try {
                // backward参数含义：true=向前翻（上一页）
                val success = RimeSession.api.changePage(backward = !forward)
                if (success) {
                    updateUI()
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻页异常: ${e.message}", e)
            }
        }
    }

    // ===== UI更新 =====

    /**
     * 从Rime获取最新上下文并更新UI
     * 包括候选词列表、编码区显示
     */
    private suspend fun updateUI() {
        try {
            val context: ContextProto? = RimeSession.api.getContext()

            // 切换到主线程更新UI
            withContext(Dispatchers.Main) {
                // 更新候选词视图
                candidatesView?.updateCandidates(context)
                // 更新键盘编码区
                keyboardView?.updateComposition(context?.composition)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateUI异常: ${e.message}", e)
        }
    }

    // ===== Rime消息处理 =====

    /**
     * 处理Rime引擎异步消息
     * 例如：方案切换通知、选项变更通知
     */
    private fun handleRimeMessage(message: RimeMessage) {
        when (message) {
            is RimeMessage.OptionMessage -> {
                Log.d(TAG, "Rime选项变更: ${message.option}")
                // ascii_mode变更时同步键盘显示
                if (message.option == "ascii_mode" || message.option == "!ascii_mode") {
                    serviceScope.launch {
                        val isAscii = RimeSession.api.getOption("ascii_mode")
                        withContext(Dispatchers.Main) {
                            keyboardView?.setChineseMode(!isAscii)
                        }
                    }
                }
            }
            is RimeMessage.SchemaMessage -> {
                Log.d(TAG, "Rime方案切换: ${message.schemaId}")
            }
            is RimeMessage.DeployMessage -> {
                Log.d(TAG, "Rime部署状态: ${message.status}")
            }
            is RimeMessage.UnknownMessage -> {
                Log.d(TAG, "Rime未知消息: type=${message.type}, value=${message.value}")
            }
        }
    }

    // ===== 清理 =====

    override fun onDestroy() {
        Log.i(TAG, "InputMethodService onDestroy")
        // 取消所有协程
        serviceScope.cancel()
        // 释放视图引用
        keyboardView = null
        candidatesView = null
        super.onDestroy()
    }
}
