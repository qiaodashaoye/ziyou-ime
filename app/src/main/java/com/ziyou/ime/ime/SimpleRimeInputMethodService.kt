package com.ziyou.ime.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.ThemeManager
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.daemon.RimeSession
import com.ziyou.ime.data.KeyRecordStack
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.ui.SettingsActivity
import com.ziyou.ime.util.T9PinYinUtils
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

        // 键盘布局偏好持久化
        private const val PREF_NAME = "ziyou_keyboard"
        private const val KEY_KEYBOARD_TYPE = "keyboard_type"

        /** 九宫格键盘对应的专用 T9 方案 id */
        private const val T9_SCHEMA_ID = "t9"
        /** 退出九宫格时的默认回退方案 id */
        private const val DEFAULT_SCHEMA_ID = "luna_pinyin"
    }

    /** 服务协程作用域，生命周期跟随Service */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 键盘容器：承载当前键盘视图，便于在不同布局之间切换 */
    private var keyboardContainer: FrameLayout? = null

    /** 当前键盘视图引用（全键盘 / 九宫格等的共同基类） */
    private var keyboardView: BaseKeyboardView? = null

    /** 当前键盘布局类型 */
    private var currentKeyboardType: KeyboardType = KeyboardType.QWERTY

    /** 进入九宫格（T9）前的方案 id，用于退出时恢复 */
    private var schemeBeforeT9: String? = null

    /** 候选词视图引用 */
    private var candidatesView: SimpleCandidatesView? = null

    /** 编码区视图引用（固定在候选词列表上方） */
    private var preeditOverlay: PreeditOverlayView? = null

    /** 九宫格左侧拼音侧栏引用（仅九宫格布局下存在） */
    private var pinyinSideBar: PinyinSideBarView? = null

    /** 九宫格底栏引用（仅九宫格布局下存在，用于同步 isChineseMode） */
    private var nineGridBottomBar: NineGridBottomBarView? = null

    /**
     * 九宫格“中→英”专用标志。
     * 当为 true 时，applyEngineForKeyboard 强制设置 ascii_mode=true，
     * handleSoftKeyPress 跳过 KEYCODE_SWITCH_LANGUAGE 的异步 toggle，避免竞态。
     */
    private var pendingEnglishMode = false

    /** 九宫格输入状态追踪栈（拼音消歧与智能回退） */
    private val keyRecordStack = KeyRecordStack()

    // ===== 生命周期 =====

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InputMethodService onCreate")

        // 初始化Rime引擎（如果还未初始化）
        serviceScope.launch {
            try {
                if (!RimeSession.initialized) {
                    Log.i(TAG, "开始初始化Rime引擎...")
                    // 首次安装或版本升级（新增/修改方案）时使用 fullCheck，以编译新方案（如 t9）
                    val needsFullCheck = AssetDeployer.needsDeploy(applicationContext)
                    RimeSession.initialize(applicationContext, fullCheck = needsFullCheck)
                    Log.i(TAG, "Rime引擎初始化完成 (fullCheck=$needsFullCheck)")
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
     *
     * 候选词区域使用垂直 LinearLayout，编码区 [PreeditOverlayView] 固定在顶部，
     * 候选词列表 [SimpleCandidatesView] 在下方独立滚动，实现编码区与候选词的职责分离。
     * 不使用 onCreateCandidatesView()——该 API 的系统级显隐控制不可靠，
     * 将候选词放在 onCreateInputView() 内可确保始终可见。
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")

        val theme = ThemeManager.getCurrentTheme(this)

        // 创建根容器：垂直LinearLayout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 候选词容器：垂直 LinearLayout，编码区固定在顶部，候选词列表在下方独立滚动
        val candidatesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        preeditOverlay = PreeditOverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyTheme(theme)
        }
        candidatesContainer.addView(preeditOverlay)

        candidatesView = SimpleCandidatesView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onCandidateClick = { index -> handleCandidateClick(index) }
            onPageChange = { forward -> handlePageChange(forward) }
            applyTheme(theme)
        }
        candidatesContainer.addView(candidatesView)

        rootLayout.addView(candidatesContainer)

        // 键盘容器（底部）：承载可切换的键盘视图
        keyboardContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(keyboardContainer)

        // 按上次保存的布局创建键盘
        currentKeyboardType = loadKeyboardType()
        installKeyboard(currentKeyboardType)

        return rootLayout
    }

    // ===== 键盘布局管理 =====

    /**
     * 根据类型创建键盘视图。新增键盘类型时仅需在此登记。
     */
    private fun createKeyboardView(type: KeyboardType): BaseKeyboardView = when (type) {
        KeyboardType.QWERTY -> SimpleKeyboardView(this)
        KeyboardType.NINE_GRID -> NineGridKeyboardView(this)
    }

    /**
     * 安装指定类型的键盘到容器，并完成回调绑定、主题与状态同步。
     *
     * 九宫格布局额外在键盘左侧挂载 [PinyinSideBarView] 拼音侧栏，
     * 通过横向 [LinearLayout] 以 `侧栏 : 键盘 ≈ 18 : 82` 的权重排布
     * （参考 yuyansdk CandidatesContainer 的 `skbWidth * 0.18`）。
     */
    private fun installKeyboard(type: KeyboardType) {
        val container = keyboardContainer ?: return
        val theme = ThemeManager.getCurrentTheme(this)
        val view = createKeyboardView(type).apply {
            // 应用当前主题
            applyTheme(theme)
            // 按键回调
            onKeyPress = { keyCode, mask -> handleSoftKeyPress(keyCode, mask) }
            // 键盘切换回调
            onSwitchKeyboard = { target -> switchKeyboard(target) }
            // 九宫格“中→英”专用回调：强制英文 + 切到 26 键
            onSwitchToQwertyEnglish = { switchToQwertyEnglish() }
            // 编码预览（如九宫格多击未提交字母）实时反馈到编码区悬浮层
            onComposingPreview = { preview -> preeditOverlay?.setText(preview) }
        }
        container.removeAllViews()
        pinyinSideBar = null
        nineGridBottomBar = null

        if (type == KeyboardType.NINE_GRID) {
            // 九宫格主网格只显示前三行（1-9 数字键 + 右侧功能列）
            val grid = view as NineGridKeyboardView
            grid.setGridRowCount(3)

            // 左侧拼音侧栏（高度与三行网格匹配，底部与数字键行对齐）
            val sideBar = PinyinSideBarView(this).apply {
                applyTheme(theme)
                setSideSymbols(SideSymbolRepository.getPinyinSideSymbols(this@SimpleRimeInputMethodService))
                onPinyinSelect = { pinyin -> handlePinyinSelect(pinyin) }
                onSymbolInput = { value -> handleSideSymbolInput(value) }
                onAddSymbol = { openSideSymbolSettings() }
            }
            // 横向容器：[侧栏][三行网格]
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            topRow.addView(sideBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.8f))
            grid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 8.2f)
            topRow.addView(grid)

            // 底栏视图：全宽横跨屏幕，延伸至屏幕最左侧边缘
            val bottomBar = NineGridBottomBarView(this).apply {
                applyTheme(theme)
                onKeyPress = { keyCode, mask -> handleSoftKeyPress(keyCode, mask) }
                onSwitchKeyboard = { target -> switchKeyboard(target) }
                onSwitchToQwertyEnglish = { switchToQwertyEnglish() }
                isChineseMode = view.isChineseMode
            }
            nineGridBottomBar = bottomBar

            // 纵向容器：[上方：侧栏+三行网格][下方：全宽底栏]
            val verticalContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            verticalContainer.addView(topRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            verticalContainer.addView(bottomBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            container.addView(verticalContainer)
            pinyinSideBar = sideBar

            // 布局完成后同步底栏按键宽度与上方网格保持一致
            grid.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    grid.gridUnitWidth?.let { unitWidth ->
                        bottomBar.forcedUnitWidth = unitWidth
                        bottomBar.requestLayout()
                    }
                }
            })
        } else {
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(view)
        }
        keyboardView = view
        currentKeyboardType = type
    }

    /**
     * 九宫格“中→英”专用切换：强制 ascii_mode=true 并切到 QWERTY。
     * 不走 handleSoftKeyPress 异步路径，避免与 applyEngineForKeyboard 竞态。
     */
    private fun switchToQwertyEnglish() {
        pendingEnglishMode = true
        switchKeyboard(KeyboardType.QWERTY)
    }

    /**
     * 切换键盘布局，重建视图并同步方案 / 中英文模式 / 编码区。
     */
    private fun switchKeyboard(type: KeyboardType) {
        if (type == currentKeyboardType && keyboardView != null) return
        keyRecordStack.clear()
        installKeyboard(type)
        saveKeyboardType(type)
        // 清除切换前残留的预览
        preeditOverlay?.setText(null)
        serviceScope.launch {
            try {
                applyEngineForKeyboard(type)
            } catch (e: Exception) {
                Log.w(TAG, "切换键盘同步方案异常: ${e.message}")
            }
        }
    }

    /**
     * 根据当前键盘类型同步 Rime 方案与状态：
     * - 九宫格：切到专用 T9 方案并保证中文模式（多击字母才能匹配拼音候选）
     * - 全键盘：若当前仍为 T9 方案，恢复到进入九宫格前的方案
     * 最后把状态同步到 UI。
     */
    private suspend fun applyEngineForKeyboard(type: KeyboardType) {
        when (type) {
            KeyboardType.NINE_GRID -> {
                val current = RimeSession.api.getCurrentSchema()
                if (current != T9_SCHEMA_ID) {
                    schemeBeforeT9 = current
                    RimeSession.api.selectSchema(T9_SCHEMA_ID)
                }
                if (RimeSession.api.getOption("ascii_mode")) {
                    RimeSession.api.setOption("ascii_mode", false)
                }
            }
            KeyboardType.QWERTY -> {
                if (RimeSession.api.getCurrentSchema() == T9_SCHEMA_ID) {
                    RimeSession.api.selectSchema(schemeBeforeT9 ?: DEFAULT_SCHEMA_ID)
                    schemeBeforeT9 = null
                }
                // 九宫格“中→英”触发：强制英文模式，避免与 handleSoftKeyPress 竞态
                if (pendingEnglishMode) {
                    RimeSession.api.setOption("ascii_mode", true)
                    pendingEnglishMode = false
                }
            }
        }
        val isAscii = RimeSession.api.getOption("ascii_mode")
        val context = RimeSession.api.getContext()
        val pinyinHints = buildPinyinHints(context)
        withContext(Dispatchers.Main) {
            keyboardView?.isChineseMode = !isAscii
            candidatesView?.updateCandidates(context)
            // 编码区悬浮层：九宫格显示拼音预览，全键盘回退到 Rime 原始 preedit
            preeditOverlay?.setText(
                buildPinyinPreview(context, pinyinHints) ?: context?.composition?.preedit
            )
            keyboardView?.updateComposition(context?.composition)
            // 刷新左侧拼音侧栏（拼音候选 + 自定义符号）
            if (type == KeyboardType.NINE_GRID) {
                pinyinSideBar?.setSideSymbols(
                    SideSymbolRepository.getPinyinSideSymbols(this@SimpleRimeInputMethodService)
                )
                pinyinSideBar?.setPinyinCandidates(pinyinHints)
            }
        }
    }

    private fun loadKeyboardType(): KeyboardType {
        val name = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(KEY_KEYBOARD_TYPE, null)
        return KeyboardType.fromName(name)
    }

    private fun saveKeyboardType(type: KeyboardType) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putString(KEY_KEYBOARD_TYPE, type.name)
            .apply()
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
                // 同步当前键盘对应的方案与状态（九宫格会切到 T9 方案）
                applyEngineForKeyboard(currentKeyboardType)
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

        // 丢弃未提交的多击预览并清空编码区
        keyboardView?.resetInputState()
        preeditOverlay?.setText(null)

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
                // 九宫格“中→英”已通过 switchToQwertyEnglish 处理，跳过异步 toggle
                if (pendingEnglishMode) {
                    pendingEnglishMode = false
                    return
                }
                serviceScope.launch {
                    try {
                        val currentAscii = RimeSession.api.getOption("ascii_mode")
                        RimeSession.api.setOption("ascii_mode", !currentAscii)
                        // 键盘视图已在View内部切换了显示，这里同步
                        keyboardView?.isChineseMode = currentAscii // 反转
                        nineGridBottomBar?.isChineseMode = currentAscii
                        Log.d(TAG, "切换中英文: ascii_mode=${!currentAscii}")
                    } catch (e: Exception) {
                        Log.e(TAG, "切换中英文异常: ${e.message}", e)
                    }
                }
            }

            // 中文/数字模式切换：中文时发送数字 0–9 直接上屏，英文时正常输入
            KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> {
                serviceScope.launch {
                    try {
                        val currentAscii = RimeSession.api.getOption("ascii_mode")
                        RimeSession.api.setOption("ascii_mode", !currentAscii)
                        keyboardView?.isChineseMode = currentAscii // 反转
                        nineGridBottomBar?.isChineseMode = currentAscii
                        Log.d(TAG, "切换中数模式: ascii_mode=${!currentAscii}")
                    } catch (e: Exception) {
                        Log.e(TAG, "切换中数模式异常: ${e.message}", e)
                    }
                }
            }

            // 符号键盘切换（暂未实现完整符号键盘）
            KeyCode.KEYCODE_SYMBOL -> {
                Log.d(TAG, "符号键盘切换（待实现）")
            }

            // 普通按键：发送给Rime引擎
            else -> {
                // 九宫格模式下的智能退格
                if (keyCode == KeyCode.XK_BackSpace && currentKeyboardType == KeyboardType.NINE_GRID && !keyRecordStack.isEmpty()) {
                    val restoreCommand = keyRecordStack.popAndRestore()
                    if (restoreCommand != null) {
                        // 撤销拼音选择：将已锁定拼音替换回原 T9 键
                        serviceScope.launch {
                            RimeSession.api.replaceKey(
                                restoreCommand.caretPos,
                                restoreCommand.length,
                                restoreCommand.replacement
                            )
                            updateUI()
                        }
                        return  // 不发送普通 BackSpace
                    }
                    // restoreCommand 为 null 表示弹出的是普通 T9Key/Apostrophe，继续执行正常退格
                }
                // 九宫格模式下追踪 T9 按键
                if (currentKeyboardType == KeyboardType.NINE_GRID) {
                    when {
                        keyCode in '2'.code..'9'.code -> keyRecordStack.pushT9Key(keyCode.toChar())
                        keyCode == '\''.code -> keyRecordStack.pushApostrophe()
                    }
                }
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
                    keyRecordStack.clear()
                }
                // 更新候选词和编码区UI
                updateUI()
            } else {
                // Rime未消费，某些键可能需要直接输出
                when {
                    // 退格键：Rime无编码可删时，直接删除编辑器中的字符
                    keyCode == KeyCode.XK_BackSpace -> {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                        Log.d(TAG, "直接删除: deleteSurroundingText(1, 0)")
                    }
                    // 可打印字符且Rime未处理，直接提交
                    keyCode in 0x20..0x7E && mask == 0 -> {
                        val char = keyCode.toChar().toString()
                        currentInputConnection?.commitText(char, 1)
                        Log.d(TAG, "直接提交字符: $char")
                    }
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
                        keyRecordStack.clear()
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
            val pinyinHints = buildPinyinHints(context)

            // 切换到主线程更新UI
            withContext(Dispatchers.Main) {
                // 更新候选词视图
                candidatesView?.updateCandidates(context)
                // 编码区悬浮层：九宫格显示拼音预览，全键盘回退到 Rime 原始 preedit
                preeditOverlay?.setText(
                    buildPinyinPreview(context, pinyinHints) ?: context?.composition?.preedit
                )
                // 更新键盘编码区
                keyboardView?.updateComposition(context?.composition)
                // 左侧拼音侧栏：有候选拼音则展示拼音，否则展示自定义符号
                pinyinSideBar?.setPinyinCandidates(pinyinHints)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateUI异常: ${e.message}", e)
        }
    }

    /**
     * 处理用户在拼音候选区点击选择拼音
     * 将 Rime 编码中对应的 T9 键序列替换为所选拼音
     */
    private fun handlePinyinSelect(pinyin: String) {
        // 锁定「首个未确定音节」对应的 T9 数字段，得到编码替换指令。
        // 在主线程同步更新状态机，保证与退格等其他栈操作的时序一致。
        val command = keyRecordStack.pushPinyinSelectAction(pinyin) ?: return
        serviceScope.launch {
            // 用选定拼音替换编码中对应的 T9 键序列（含末尾分词符），锁定该音节
            RimeSession.api.replaceKey(command.caretPos, command.length, command.replacement)
            // replaceKey 会把光标停在已锁定拼音之后（编码串中部），而 Rime 仅组织光标
            // 之前的片段，导致候选只剩「已锁定音节」的单字。将光标移到编码串末尾，令 Rime
            // 组织「已锁定拼音 + 后续未确定音节」的完整组合候选（如 guo'486 → 组词候选）。
            RimeSession.api.processKey(KeyCode.XK_End, 0)
            updateUI()
        }
    }

    /**
     * 处理左侧侧栏符号点击（无候选拼音时展示的自定义符号）。
     * 侧栏符号仅在无活跃编码时可见，因此直接上屏其内容即可。
     * —— 等价 yuyansdk 中 `inputView.responseKeyEvent(SoftKey(label = symbol))`。
     */
    private fun handleSideSymbolInput(value: String) {
        if (value.isEmpty()) return
        currentInputConnection?.commitText(value, 1)
    }

    /**
     * 打开侧栏符号自定义管理页面。
     * —— 等价 yuyansdk 中 `AppUtil.launchSettingsToPrefix(context, arguments)`。
     */
    private fun openSideSymbolSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_OPEN_SIDE_SYMBOLS, true)
        }
        startActivity(intent)
    }

    /**
     * 生成九宫格拼音候选列表。
     * 优先使用 T9PinYinUtils 从 T9 编码直接生成拼音（更精确），
     * 回退到从候选 comment 提取。
     */
    private fun buildPinyinHints(context: ContextProto?): List<String>? {
        if (currentKeyboardType != KeyboardType.NINE_GRID) return null
        if (context == null) return null
        // 优先：从 Rime 原始输入串提取「首个未消歧的数字段」，用本地 T9 表还原候选拼音。
        // 输入串以数字（2-9）与已锁定拼音 + 分词符（'）混排，如 "guo'486"。
        val digitSegment = context.input
            .split('\'', ' ')
            .firstOrNull { seg -> seg.isNotEmpty() && seg.all { it in '2'..'9' } }
        if (digitSegment != null) {
            val pinyins = T9PinYinUtils.t9KeyToPinyin(digitSegment).filter { it.isNotBlank() }
            if (pinyins.isNotEmpty()) return pinyins.take(8)
        }
        // 回退：从候选词 comment（spelling_hints）提取真实拼音
        val candidates = context.menu?.candidates ?: return null
        if (candidates.isEmpty()) return null
        val hints = LinkedHashSet<String>()
        for (candidate in candidates) {
            val py = candidate.comment.trim()
            if (py.isNotEmpty()) hints.add(py)
            if (hints.size >= 8) break
        }
        return hints.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * 生成顶部编码区的「当前拼音」单串预览（与主流九宫格一致：顶部只展示当前解释的拼音）。
     *
     * 取高亮候选的拼音读音（spelling_hints comment）作为当前解释；无候选时回退到首个拼音提示。
     * 非九宫格返回 null，由候选视图沿用 Rime 原始 preedit。
     */
    private fun buildPinyinPreview(context: ContextProto?, hints: List<String>?): String? {
        if (currentKeyboardType != KeyboardType.NINE_GRID) return null
        val menu = context?.menu
        val candidates = menu?.candidates
        val highlighted = menu?.highlightedCandidateIndex ?: -1
        if (candidates != null && highlighted in candidates.indices) {
            val comment = candidates[highlighted].comment.trim()
            if (comment.isNotEmpty()) return comment
        }
        return hints?.firstOrNull()
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
                            keyboardView?.isChineseMode = !isAscii
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
        keyboardContainer = null
        candidatesView = null
        preeditOverlay = null
        pinyinSideBar = null
        nineGridBottomBar = null
        super.onDestroy()
    }
}
