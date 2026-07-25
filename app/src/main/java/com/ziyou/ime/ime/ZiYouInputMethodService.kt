package com.ziyou.ime.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.ThemeManager
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.data.AssociationManager
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.level.LevelRepository
import com.ziyou.ime.level.LevelStats
import com.ziyou.ime.ui.SettingsActivity
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
class ZiYouInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "ZiYouIMS"

        // 键盘布局偏好持久化
        private const val PREF_NAME = "ziyou_keyboard"
        private const val KEY_KEYBOARD_TYPE = "keyboard_type"

        /** 九宫格键盘对应的专用 T9 方案 id */
        private const val T9_SCHEMA_ID = "t9"
        /** 退出九宫格时的默认回退方案 id */
        private const val DEFAULT_SCHEMA_ID = "luna_pinyin"

        /** 等待引擎就绪的轮询间隔（ms） */
        private const val ENGINE_READY_POLL_MS = 50L
        /** 视图同步类操作等待引擎就绪的超时（ms）：词库重部署可能耗时较长 */
        private const val ENGINE_READY_TIMEOUT_MS = 10_000L
        /** 按键处理等待引擎就绪的短超时（ms）：避免按键响应长时间挂起 */
        private const val KEY_ENGINE_READY_TIMEOUT_MS = 3_000L
    }

    /** 服务协程作用域，生命周期跟随Service */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Rime 引擎（经 DI 容器获取，便于替换/测试；生产实现为 RimeSession 单例）。 */
    private val rime: RimeEngine get() = AppContainer.rimeEngine

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

    /** 部署完成后的键盘状态重同步任务（去重：新部署消息到来时取消上一次） */
    private var deploySyncJob: Job? = null

    /** 输入逻辑控制器（与 Rime 交互、上屏、刷新 UI），经 DI 容器获取引擎。 */
    private val inputLogic by lazy {
        InputLogicController(AppContainer.rimeEngine, serviceScope, keyRecordStack, inputLogicCallbacks)
    }

    /** 提供给 [InputLogicController] 的回调：编辑器连接 + 主线程 UI 渲染。 */
    private val inputLogicCallbacks = object : InputLogicController.Callbacks {
        override fun currentInputConnection(): InputConnection? =
            this@ZiYouInputMethodService.currentInputConnection

        override fun renderContext(context: ContextProto?) =
            this@ZiYouInputMethodService.renderContext(context)
    }

    // ===== 生命周期 =====

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InputMethodService onCreate")

        // 初始化等级计分（热路径仅做内存自增，此处仅注入 applicationContext）
        LevelStats.init(applicationContext)

        // 初始化Rime引擎（如果还未初始化）
        serviceScope.launch {
            try {
                if (!rime.initialized) {
                    Log.i(TAG, "开始初始化Rime引擎...")
                    // 首次安装或版本升级（新增/修改方案）时使用 fullCheck，以编译新方案（如 t9）
                    val needsFullCheck = AssetDeployer.needsDeploy(applicationContext)
                    rime.initialize(applicationContext, fullCheck = needsFullCheck)
                    Log.i(TAG, "Rime引擎初始化完成 (fullCheck=$needsFullCheck)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rime引擎初始化失败: ${e.message}", e)
            }
        }

        // 监听Rime消息（方案切换、选项变更等）
        serviceScope.launch {
            rime.messageFlow.collectLatest { message ->
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

    /** 键盘视图装载器（承担键盘视图创建与九宫格复合布局组装）。 */
    private val keyboardLayoutManager by lazy { KeyboardLayoutManager(this, keyboardCallbacks) }

    /** 键盘视图交互回调，转发到 Service 内部处理方法。 */
    private val keyboardCallbacks = object : KeyboardLayoutManager.Callbacks {
        override fun onKeyPress(keyCode: Int, mask: Int) = handleSoftKeyPress(keyCode, mask)
        override fun onSwitchKeyboard(target: KeyboardType) = switchKeyboard(target)
        override fun onSwitchToQwertyEnglish() = switchToQwertyEnglish()
        override fun onComposingPreview(preview: String?) { preeditOverlay?.setText(preview) }
        override fun onPinyinSelect(pinyin: String) = handlePinyinSelect(pinyin)
        override fun onSideSymbolInput(value: String) = handleSideSymbolInput(value)
        override fun onAddSymbol() = openSideSymbolSettings()
    }

    /**
     * 安装指定类型的键盘到容器，并同步当前视图引用。
     * 键盘视图创建与九宫格复合布局的组装细节委托 [KeyboardLayoutManager]。
     */
    private fun installKeyboard(type: KeyboardType) {
        val container = keyboardContainer ?: return
        val installed = keyboardLayoutManager.install(container, type)
        keyboardView = installed.keyboardView
        pinyinSideBar = installed.pinyinSideBar
        nineGridBottomBar = installed.nineGridBottomBar
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
                // 同步失败（如词库重部署期间引擎不可用）时清除中→英标志，
                // 避免残留的 pendingEnglishMode 吞掉后续一次中英切换
                pendingEnglishMode = false
            }
        }
    }

    /**
     * 等待 Rime 引擎就绪（初始化完成）。
     *
     * 词库下载/启用后 [com.ziyou.ime.daemon.RimeSession.redeploy] 会销毁并重建引擎，
     * 窗口期内 `rime.api` 直接抛 IllegalStateException。所有非热路径的引擎访问
     * （状态同步、模式切换）先经本方法等待，避免在重部署期间直接失败且无重试。
     *
     * @return true 表示引擎已就绪；false 表示等待超时（调用方应放弃本次操作，
     *         由部署完成消息触发的重同步兑底）
     */
    private suspend fun awaitEngineReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!rime.initialized) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(ENGINE_READY_POLL_MS)
        }
        return true
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
                val current = rime.api.getCurrentSchema()
                if (current != T9_SCHEMA_ID) {
                    schemeBeforeT9 = current
                    rime.api.selectSchema(T9_SCHEMA_ID)
                }
                if (rime.api.getOption("ascii_mode")) {
                    rime.api.setOption("ascii_mode", false)
                }
            }
            KeyboardType.QWERTY -> {
                if (rime.api.getCurrentSchema() == T9_SCHEMA_ID) {
                    rime.api.selectSchema(schemeBeforeT9 ?: DEFAULT_SCHEMA_ID)
                    schemeBeforeT9 = null
                }
                // 九宫格“中→英”触发：强制英文模式，避免与 handleSoftKeyPress 竞态
                if (pendingEnglishMode) {
                    rime.api.setOption("ascii_mode", true)
                    pendingEnglishMode = false
                }
            }
        }
        val isAscii = rime.api.getOption("ascii_mode")
        // 引擎级联想（librime-predict）选项联动：与应用层联想总开关同步。
        // 当前预编译库未启用 predict 模块 / schema 未挂 predictor 时为无害 no-op，
        // 启用后无需改动即可由同一开关控制引擎预测（选项名见 predictor 源码 "prediction"）
        rime.api.setOption("prediction", AssociationManager.isEnabled(this))
        val context = rime.api.getContext()
        val pinyinHints = buildPinyinHints(context)
        withContext(Dispatchers.Main) {
            keyboardView?.isChineseMode = !isAscii
            candidatesView?.updateCandidates(context)
            // 编码区同源同步：九宫格按候选读音+实际击键还原预览，悬浮层与键盘视图
            // 共用同一串；全键盘回退到 Rime 原始 preedit
            val preview = buildPinyinPreview(context)
            preeditOverlay?.setText(preview ?: context?.composition?.preedit)
            if (preview != null) {
                keyboardView?.updateCompositionPreview(preview)
            } else {
                keyboardView?.updateComposition(context?.composition)
            }
            // 刷新左侧拼音侧栏（拼音候选 + 自定义符号）
            if (type == KeyboardType.NINE_GRID) {
                pinyinSideBar?.setSideSymbols(
                    SideSymbolRepository.getPinyinSideSymbols(this@ZiYouInputMethodService)
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
                // 词库下载后引擎可能正在重新部署，先等待就绪再同步，
                // 否则 rime.api 抛异常导致 t9 方案/ascii_mode 永不恢复，九宫格按键失效
                if (!awaitEngineReady(ENGINE_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "onStartInputView: Rime引擎未就绪（可能正在重新部署），待部署完成消息触发重同步")
                    return@launch
                }
                // 清除之前的编码
                rime.api.clearComposition()
                // 同步当前键盘对应的方案与状态（九宫格会切到 T9 方案）
                applyEngineForKeyboard(currentKeyboardType)
            } catch (e: Exception) {
                Log.e(TAG, "onStartInputView 处理异常: ${e.message}", e)
            }
        }

        // 每日签到：发放首用/连续天数奖励（幂等，后台线程执行，不阻塞输入视图）
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = LevelRepository.checkInToday(applicationContext)
                if (result.isFirstUseToday) {
                    // MVP：每日简报 UI 展示留待 UI 阶段接入，此处先记录
                    Log.d(TAG, "每日签到: 连续${result.state.streakDays}天 +${result.bonusPoints}分, " +
                        "昨日${result.yesterdayChars}字/${result.yesterdayPoints}分")
                }
            } catch (e: Exception) {
                Log.w(TAG, "每日签到异常: ${e.message}")
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

        // 输入视图隐藏，主动落盘累计的上屏计分，避免尾部数据丢失
        LevelStats.flush()

        serviceScope.launch {
            try {
                rime.api.clearComposition()
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
            inputLogic.processKey(rimeKeyCode, mask)
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
                        if (!awaitEngineReady(KEY_ENGINE_READY_TIMEOUT_MS)) {
                            Log.w(TAG, "切换中英文失败：Rime引擎未就绪（可能正在重新部署）")
                            return@launch
                        }
                        val currentAscii = rime.api.getOption("ascii_mode")
                        rime.api.setOption("ascii_mode", !currentAscii)
                        // 视图不再预翻转，统一在此按引擎结果回写（含九宫格底栏）
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
                        if (!awaitEngineReady(KEY_ENGINE_READY_TIMEOUT_MS)) {
                            Log.w(TAG, "切换中数模式失败：Rime引擎未就绪（可能正在重新部署）")
                            return@launch
                        }
                        val currentAscii = rime.api.getOption("ascii_mode")
                        rime.api.setOption("ascii_mode", !currentAscii)
                        // 视图不再预翻转，统一在此按引擎结果回写（含九宫格底栏）
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
                        inputLogic.restorePinyin(restoreCommand)
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
                    inputLogic.processKey(keyCode, mask)
                }
            }
        }
    }

    // ===== 候选词操作（委托 InputLogicController）=====

    /** 处理候选词点击（含引擎预测词，均经 Rime 选词路径）。 */
    private fun handleCandidateClick(index: Int) = inputLogic.selectCandidate(index)

    /** 处理翻页。@param forward true=下一页, false=上一页 */
    private fun handlePageChange(forward: Boolean) = inputLogic.changePage(forward)

    /**
     * 处理用户在拼音候选区点击选择拼音。
     * 在主线程同步更新状态机（保证与退格等其他栈操作时序一致），再委托控制器送 Rime 替换。
     */
    private fun handlePinyinSelect(pinyin: String) {
        val command = keyRecordStack.pushPinyinSelectAction(pinyin) ?: return
        inputLogic.selectPinyin(command)
    }

    /**
     * 处理左侧侧栏符号点击（无候选拼音时展示的自定义符号）。
     * 侧栏符号仅在无活跃编码时可见，直接上屏其内容即可。
     */
    private fun handleSideSymbolInput(value: String) {
        inputLogic.commitSideSymbol(value)
    }

    // ===== UI 渲染 =====

    /**
     * 根据最新 Rime 上下文刷新候选词、编码区与拼音侧栏（在主线程调用）。
     * 供 [InputLogicController] 通过回调驱动。
     */
    private fun renderContext(context: ContextProto?) {
        val pinyinHints = buildPinyinHints(context)
        // 更新候选词视图；预测态（引擎在 commit 后产生 prediction 候选：菜单非空且
        // 编码串为空）复用联想强调色，与普通候选词区分；未启用 predict 模块时该分支休眠
        val predictionMode = context?.menu?.candidates?.isNotEmpty() == true && context.input.isEmpty()
        candidatesView?.updateCandidates(context, predictionMode)
        // 编码区同源同步：九宫格按候选读音+实际击键还原预览，悬浮层与键盘视图
        // 共用同一串，确保编码区与候选区拼音一致；全键盘回退到 Rime 原始 preedit
        val preview = buildPinyinPreview(context)
        preeditOverlay?.setText(preview ?: context?.composition?.preedit)
        if (preview != null) {
            keyboardView?.updateCompositionPreview(preview)
        } else {
            keyboardView?.updateComposition(context?.composition)
        }
        // 左侧拼音侧栏：有候选拼音则展示拼音，否则展示自定义符号
        pinyinSideBar?.setPinyinCandidates(pinyinHints)
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
     * 生成九宫格拼音候选列表（委托 [PinyinHintProvider]）。
     * 仅九宫格布局有效，其余布局返回 null。
     */
    private fun buildPinyinHints(context: ContextProto?): List<String>? {
        if (currentKeyboardType != KeyboardType.NINE_GRID) return null
        return PinyinHintProvider.buildHints(context)
    }

    /**
     * 生成顶部编码区的"当前拼音"单串预览（委托 [PinyinHintProvider]，
     * 以高亮候选读音为消歧依据、以实际击键数为长度约束）。
     * 非九宫格返回 null，由候选视图沿用 Rime 原始 preedit。
     */
    private fun buildPinyinPreview(context: ContextProto?): String? {
        if (currentKeyboardType != KeyboardType.NINE_GRID) return null
        return PinyinHintProvider.buildPreview(context)
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
                        try {
                            // 重部署期间引擎不可用，跳过（部署完成后会整体重同步）
                            if (!rime.initialized) return@launch
                            val isAscii = rime.api.getOption("ascii_mode")
                            withContext(Dispatchers.Main) {
                                keyboardView?.isChineseMode = !isAscii
                                // 九宫格“数”键位于独立底栏视图，同样需同步，否则显示与引擎状态相反
                                nineGridBottomBar?.isChineseMode = !isAscii
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "同步 ascii_mode 状态异常: ${e.message}")
                        }
                    }
                }
            }
            is RimeMessage.SchemaMessage -> {
                Log.d(TAG, "Rime方案切换: ${message.schemaId}")
            }
            is RimeMessage.DeployMessage -> {
                Log.d(TAG, "Rime部署状态: ${message.status}")
                // 词库下载/启用后 RimeSession.redeploy 会整体重建引擎，方案与选项全部复位。
                // 待引擎就绪后重新同步当前键盘的方案与中英文状态，
                // 否则九宫格停留在默认方案上，中/数切换等按键表现为“失效”。
                deploySyncJob?.cancel()
                deploySyncJob = serviceScope.launch {
                    if (!awaitEngineReady(ENGINE_READY_TIMEOUT_MS)) {
                        Log.w(TAG, "部署后重同步超时：Rime引擎仍未就绪")
                        return@launch
                    }
                    try {
                        applyEngineForKeyboard(currentKeyboardType)
                        Log.i(TAG, "部署完成，已重同步键盘状态 (type=$currentKeyboardType)")
                    } catch (e: Exception) {
                        Log.w(TAG, "部署后重同步键盘状态异常: ${e.message}")
                    }
                }
            }
            is RimeMessage.UnknownMessage -> {
                Log.d(TAG, "Rime未知消息: type=${message.type}, value=${message.value}")
            }
        }
    }

    // ===== 清理 =====

    override fun onDestroy() {
        Log.i(TAG, "InputMethodService onDestroy")
        // 服务销毁前落盘剩余的上屏计分
        LevelStats.flush()
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
