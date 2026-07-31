package com.ziyou.ime.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.DisplayModeManager
import com.ziyou.ime.config.SchemaPreference
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinTheme
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.core.RimeNative
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.data.AssociationManager
import com.ziyou.ime.data.ClipboardHistoryRepository
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.data.SymbolRepository
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.level.LevelRepository
import com.ziyou.ime.level.LevelStats
import com.ziyou.ime.ui.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File

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

    /** 显示形态控制器（停靠/悬浮解析、切换与悬浮 insets，从 Service 拆分） */
    private val displayModeCtrl by lazy {
        DisplayModeController(this, displayModeHost)
    }

    /** 提供给 [DisplayModeController] 的回调：切换前清理 + 切换后重建/重同步。 */
    private val displayModeHost = object : DisplayModeController.Host {
        override fun beforeModeSwitch() {
            keyboardView?.resetInputState()
        }

        override fun onModeSwitched(mode: DisplayMode) {
            setInputView(buildInputView(mode))
            scheduleEngineSync()
        }
    }

    /** 技能面板协调器（面板生命周期与三态布局编排，从 Service 拆分） */
    private val skillPanels by lazy {
        SkillPanelCoordinator(this, skillPanelHost)
    }

    /** 提供给 [SkillPanelCoordinator] 的宿主能力：容器访问、上屏出口与输入路由切换。 */
    private val skillPanelHost = object : SkillPanelCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun isFloatingMode(): Boolean =
            displayModeCtrl.currentMode == DisplayMode.FLOATING

        override fun currentEditorInfo(): EditorInfo? = currentInputEditorInfo

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun commitText(text: String) = inputLogic.commitSideSymbol(text)

        override fun setCommitTarget(target: InputLogicController.CommitTarget?) {
            inputLogic.commitTarget = target
        }

        override fun onPanelWillOpen() = clearCompositionForPanel()

        override fun editorAcceptsImage(): Boolean = inputLogic.acceptsImageContent()

        override fun commitImageToEditor(file: File, description: String): Boolean = try {
            val uri = FileProvider.getUriForFile(
                this@ZiYouInputMethodService, "$packageName.imecontent", file)
            inputLogic.commitImageToEditor(uri, "image/png", description)
        } catch (e: Exception) {
            Log.e(TAG, "技能图片提交异常: ${e.message}", e)
            false
        }
    }

    /** AI 问答面板协调器（面板生命周期与键盘收放编排，与技能面板同一拆分纪律） */
    private val aiPanels by lazy {
        AiPanelCoordinator(this, aiPanelHost)
    }

    /** 提供给 [AiPanelCoordinator] 的宿主能力：容器访问与输入路由切换。 */
    private val aiPanelHost = object : AiPanelCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun setCommitTarget(target: InputLogicController.CommitTarget?) {
            inputLogic.commitTarget = target
        }

        override fun commitAnswerToEditor(text: String) = inputLogic.commitDirectToEditor(text)

        override fun commitAnswerImageToEditor(content: CharSequence) = submitAnswerImage(content)

        override fun editorAcceptsImage(): Boolean = inputLogic.acceptsImageContent()

        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 涂鸦画板面板协调器（面板生命周期与键盘收放编排，与 AI/技能面板同一拆分纪律） */
    private val doodlePanels by lazy {
        DoodlePanelCoordinator(this, doodlePanelHost)
    }

    /** 提供给 [DoodlePanelCoordinator] 的宿主能力：容器访问与图片发送出口。 */
    private val doodlePanelHost = object : DoodlePanelCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun sendDoodleImage(snapshot: Bitmap) =
            sendDoodleAsImage(snapshot)

        override fun saveDoodleImage(snapshot: Bitmap) =
            saveDoodleAsImage(snapshot)

        override fun imageSupportsSend(): Boolean = inputLogic.acceptsImageContent()

        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 粘贴板历史面板协调器（面板生命周期与键盘收放编排，与 AI/技能/涂鸦面板同一拆分纪律） */
    private val clipboardPanels by lazy {
        ClipboardPanelCoordinator(this, clipboardPanelHost)
    }

    /** 提供给 [ClipboardPanelCoordinator] 的宿主能力：容器访问与粘贴出口。 */
    private val clipboardPanelHost = object : ClipboardPanelCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun isFloatingMode(): Boolean =
            displayModeCtrl.currentMode == DisplayMode.FLOATING

        override fun pasteToEditor(text: String) = inputLogic.commitDirectToEditor(text)

        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 工具面板协调器（Logo 键入口，面板生命周期与键盘收放编排，与其他面板同一拆分纪律） */
    private val toolPanels by lazy {
        ToolPanelCoordinator(this, toolPanelHost)
    }

    /** 提供给 [ToolPanelCoordinator] 的宿主能力：容器访问与功能码分发出口。 */
    private val toolPanelHost = object : ToolPanelCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun dispatchToolKey(keyCode: Int) = handleSoftKeyPress(keyCode, 0)

        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 键盘选择面板协调器（功能栏「键盘切换」入口，与其他面板同一拆分纪律） */
    private val keyboardPickers by lazy {
        KeyboardPickerCoordinator(this, keyboardPickerHost)
    }

    /** 提供给 [KeyboardPickerCoordinator] 的宿主能力：容器访问与布局切换出口。 */
    private val keyboardPickerHost = object : KeyboardPickerCoordinator.Host {
        override fun contentLayout(): LinearLayout? = this@ZiYouInputMethodService.contentLayout

        override fun keyboardContainer(): FrameLayout? =
            this@ZiYouInputMethodService.keyboardContainer

        override fun candidatesContainer(): LinearLayout? =
            this@ZiYouInputMethodService.candidatesContainer

        override fun keyboardView(): BaseKeyboardView? = this@ZiYouInputMethodService.keyboardView

        override fun currentKeyboardType(): KeyboardType =
            this@ZiYouInputMethodService.currentKeyboardType

        override fun switchKeyboard(type: KeyboardType) =
            this@ZiYouInputMethodService.switchKeyboard(type)

        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 剪贴板变更监听：复制即收录历史（持强引用，onCreate 注册 / onDestroy 注销） */
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboardToHistory()
    }

    /**
     * 面板（技能 / AI 问答 / 涂鸦画板 / 粘贴板）打开前的统一清理：
     * 清除活跃编码与候选/编码区展示，避免面板期间残留 preedit/候选。
     */
    private fun clearCompositionForPanel() {
        keyboardView?.resetInputState()
        keyRecordStack.clear()
        renderContext(null)
        serviceScope.launch {
            try {
                if (rime.initialized) rime.api.clearComposition()
            } catch (e: Exception) {
                Log.w(TAG, "打开面板清除编码异常: ${e.message}")
            }
        }
    }

    /** 输入视图内容根容器（技能面板/编码区/候选/键盘 自上而下堆叠） */
    private var contentLayout: LinearLayout? = null

    /** 候选区容器（编码区 + 候选词列表），供技能面板展开态整体隐藏/恢复 */
    private var candidatesContainer: LinearLayout? = null

    /** 进入符号键盘前的布局类型，用于「返回」键恢复（符号键盘为临时面板） */
    private var keyboardBeforeSymbol: KeyboardType? = null

    /** 进入数字键盘前的布局类型，用于「返回」键恢复（数字键盘为临时面板） */
    private var keyboardBeforeNumber: KeyboardType? = null

    /** 候选词视图引用 */
    private var candidatesView: SimpleCandidatesView? = null

    /** 候选区功能按钮栏引用（与编码区+候选词列表整体叠放，无候选词时显示） */
    private var candidateToolbar: CandidateToolbarView? = null

    /** 编码区视图引用（固定在候选词列表上方） */
    private var preeditOverlay: PreeditOverlayView? = null

    /** 九宫格左侧拼音侧栏引用（仅九宫格布局下存在） */
    private var pinyinSideBar: PinyinSideBarView? = null

    /**
     * 九宫格“中→英”专用标志。
     * 当为 true 时，applyEngineForKeyboard 强制设置 ascii_mode=true，
     * handleSoftKeyPress 跳过 KEYCODE_SWITCH_LANGUAGE 的异步 toggle，避免竞态。
     */
    private var pendingEnglishMode = false

    /** 九宫格输入状态追踪栈（拼音消歧与智能回退） */
    private val keyRecordStack = KeyRecordStack()

    /** 引擎状态同步任务（latest-wins 串行化：新同步请求到来时取消上一次，
     *  避免快速切换键盘/部署完成/形态切换的并发同步交错导致迟到写入） */
    private var engineSyncJob: Job? = null

    /** 输入逻辑控制器（与 Rime 交互、上屏、刷新 UI），经 DI 容器获取引擎与上屏监听。 */
    private val inputLogic by lazy {
        InputLogicController(
            AppContainer.rimeEngine, serviceScope, keyRecordStack,
            inputLogicCallbacks, AppContainer.commitListeners
        )
    }

    /** 提供给 [InputLogicController] 的回调：编辑器连接 + 主线程 UI 渲染。 */
    private val inputLogicCallbacks = object : InputLogicController.Callbacks {
        override fun currentInputConnection(): InputConnection? =
            this@ZiYouInputMethodService.currentInputConnection

        override fun currentEditorInfo(): EditorInfo? = currentInputEditorInfo

        override fun renderContext(context: ContextProto?) =
            this@ZiYouInputMethodService.renderContext(context)
    }

    // ===== 生命周期 =====

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InputMethodService onCreate")

        // 初始化等级计分（热路径仅做内存自增，此处仅注入 applicationContext）
        LevelStats.init(applicationContext)

        // 预热符号键盘的 YAML 分类缓存（后台线程，首次切到数学/序号等分类时零 IO）
        serviceScope.launch(Dispatchers.IO) {
            try {
                SymbolRepository.preload(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "预热符号分类缓存失败: ${e.message}")
            }
        }

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

        // 监听剪贴板变更：复制即收录粘贴板历史
        // （Android 10+ 后台读剪贴板仅默认输入法豁免，非默认时读到 null 自然跳过）
        try {
            getSystemService(ClipboardManager::class.java)
                ?.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.w(TAG, "注册剪贴板监听失败: ${e.message}")
        }

        // 皮肤快照就绪/变更监听：背景图异步补齐、设置页切换/自定义保存后
        // 重建输入视图套用新皮肤（与形态切换同源路径），并重同步引擎状态
        SkinManager.addListener(skinChangeListener)

        // 监听Rime消息（方案切换、选项变更等）
        serviceScope.launch {
            rime.messageFlow.collectLatest { message ->
                handleRimeMessage(message)
            }
        }
    }

    /**
     * 读取当前剪贴板并收录进粘贴板历史（监听回调 + onStartInputView 兜底同步）。
     * 去重/截断/容量裁剪由 :core-logic 的 ClipboardHistoryLogic 保证：
     * 与头条重复的捕获不触发落盘，高频调用零 IO；
     * Android 13+ 密码管理器等标记的敏感内容（EXTRA_IS_SENSITIVE）不入库。
     */
    private fun captureClipboardToHistory() {
        try {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                clip.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return
            }
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            ClipboardHistoryRepository.addEntry(this, text)
        } catch (e: Exception) {
            Log.w(TAG, "读取剪贴板异常: ${e.message}")
        }
    }

    /**
     * 创建输入视图（包含候选词栏和键盘）
     *
     * 候选词区域使用垂直 LinearLayout，编码区 [PreeditOverlayView] 固定在顶部，
     * 候选词列表 [SimpleCandidatesView] 在下方独立滚动，实现编码区与候选词的职责分离。
     * 不使用 onCreateCandidatesView()——该 API 的系统级显隐控制不可靠，
     * 将候选词放在 onCreateInputView() 内可确保始终可见。
     *
     * 显示形态（[DisplayMode]）在此解析：FLOATING 时内容被包裹进
     * [FloatingPanelContainer] 悬浮面板，面板外触摸经 onComputeInsets 裁剪后穿透。
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")
        return buildInputView(displayModeCtrl.refresh())
    }

    /**
     * 按显示形态构建完整输入视图（候选容器 + 键盘容器，FLOATING 时外套悬浮面板）。
     * 形态切换（[switchDisplayMode]）时也经本方法重建，与 onCreateInputView 同源。
     */
    private fun buildInputView(mode: DisplayMode): View {
        // 重建前释放技能/AI/涂鸦/粘贴板/工具面板（旧容器即将废弃，WebView/进行中请求/离屏 bitmap 必须显式释放）
        skillPanels.close()
        aiPanels.close()
        doodlePanels.close()
        clipboardPanels.close()
        toolPanels.close()
        val skin = SkinManager.getCurrentSkin(this)
        // 悬浮形态下键盘/候选/编码区统一缩放，停靠形态保持 1.0 零影响
        val scale = if (mode == DisplayMode.FLOATING) DisplayModeManager.FLOATING_SCALE else 1f

        // 内容根容器：垂直 LinearLayout（停靠形态直接作为输入视图根）
        // 垂直堆叠顺序（顶→底）：[技能面板(打开时)] → 编码区 → 候选词 → 键盘
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 皮肤背景图统一设在根容器（含压暗遮罩），各子视图不感知背景图；
            // 无背景图时保持透明，由各视图自绘纯色背景（与迁移前一致）
            background = skin.createBackgroundDrawable()
        }
        contentLayout = root

        // 候选词容器：垂直 LinearLayout，编码区固定在顶部，候选词列表在下方独立滚动
        val candidatesArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        candidatesContainer = candidatesArea

        preeditOverlay = PreeditOverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            scaleFactor = scale
            applySkin(skin)
        }

        candidatesView = SimpleCandidatesView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            scaleFactor = scale
            onCandidateClick = { index -> handleCandidateClick(index) }
            onPageChange = { forward -> handlePageChange(forward) }
            applySkin(skin)
        }

        // 编码区 + 候选词列表垂直堆叠，作为一个整体与按钮栏叠放
        val textArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(preeditOverlay)
            addView(candidatesView)
        }

        // 候选区功能按钮栏：与「编码区 + 候选词列表」整体叠放（FrameLayout 覆盖，
        // 高度为二者总和），无候选词时显示按钮栏，有候选词时隐藏（见 updateToolbarVisibility）
        candidateToolbar = CandidateToolbarView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            scaleFactor = scale
            onButtonClick = { keyCode -> handleSoftKeyPress(keyCode, 0) }
            applySkin(skin)
        }

        val candidatesStack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(textArea)
            addView(candidateToolbar)
        }
        candidatesArea.addView(candidatesStack)

        root.addView(candidatesArea)

        // 键盘容器（底部）：承载可切换的键盘视图
        keyboardContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(keyboardContainer)

        // 按上次保存的布局创建键盘（installKeyboard 内部按 currentDisplayMode 适配悬浮）
        currentKeyboardType = loadKeyboardType()
        installKeyboard(currentKeyboardType)

        // 悬浮形态：内容包裹进悬浮面板容器（拖拽/位置持久化/停靠按钮，委托控制器）
        return displayModeCtrl.wrapContent(root, mode, skin)
    }

    // ===== 显示形态（停靠 / 悬浮，委托 DisplayModeController）=====

    /** 悬浮形态的窗口 insets：委托 [DisplayModeController.computeInsets]。 */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        displayModeCtrl.computeInsets(outInsets)
    }

    /**
     * 始终禁用全屏提取模式（extract mode）：
     * 横屏下系统默认会用全屏输入框替代应用画面，这是游戏内打字体验差的主因；
     * 悬浮形态必须禁用，停靠形态禁用后横屏也能看到原应用界面（体验修复）。
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 系统配置变化（含深浅色切换）：通知皮肤管理器，
     * darkMode=both 的皮肤据此重建快照并经 [skinChangeListener] 换肤。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SkinManager.onSystemDarkModeChanged(this)
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
     * 键盘视图创建与九宫格复合布局的组装细节委托 [KeyboardLayoutManager]；
     * 悬浮形态下传入 floating 标志与统一缩放因子。
     */
    private fun installKeyboard(type: KeyboardType) {
        val container = keyboardContainer ?: return
        // 容器即将被清空重建，先关闭技能面板（释放 WebView，叠层/提升两种挂载均适用）
        skillPanels.close()
        val floating = displayModeCtrl.currentMode == DisplayMode.FLOATING
        val scale = if (floating) DisplayModeManager.FLOATING_SCALE else 1f
        val installed = keyboardLayoutManager.install(container, type, floating, scale)
        keyboardView = installed.keyboardView
        pinyinSideBar = installed.pinyinSideBar
        currentKeyboardType = type
        // 新建的视图按当前编辑器同步换行键文案（搜索 / 发送 / 换行…）
        syncEnterKeyLabel()
    }

    /**
     * 同步换行键键面文案：按当前编辑器的 imeOptions/inputType 解析
     * （多行或无动作显示「换行」，声明了动作则显示「搜索」「发送」等），
     * 与 [InputLogicController] 中回车键的实际落地语义同源（[EnterKeyBehavior]）。
     */
    private fun syncEnterKeyLabel() {
        keyboardView?.enterKeyLabel = EnterKeyBehavior.labelOf(currentInputEditorInfo)
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
        scheduleEngineSync()
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
     * 调度一次引擎状态同步（latest-wins）：取消进行中的旧任务，等引擎就绪后
     * 按「执行时」的当前键盘类型执行 [applyEngineForKeyboard]。
     * 所有入口（键盘切换 / 获焦 / 部署完成 / 形态与主题切换 / 方案一致性守护）
     * 共用本方法，串行化消除并发同步交错导致的迟到写入（如快速
     * 九宫格→全键盘切换时，滞后的九宫格同步把引擎切回 t9）。
     *
     * @param timeoutMs 等待引擎就绪的超时；超时放弃本次，由部署完成消息触发的重同步兑底
     * @param beforeSync 引擎就绪后、同步前的前置操作（如 onStartInputView 清编码）
     */
    private fun scheduleEngineSync(
        timeoutMs: Long = KEY_ENGINE_READY_TIMEOUT_MS,
        beforeSync: (suspend () -> Unit)? = null
    ) {
        engineSyncJob?.cancel()
        engineSyncJob = serviceScope.launch {
            try {
                if (!awaitEngineReady(timeoutMs)) {
                    Log.w(TAG, "引擎状态同步放弃：Rime引擎未就绪（可能正在重新部署，待部署完成消息重同步）")
                    return@launch
                }
                beforeSync?.invoke()
                applyEngineForKeyboard(currentKeyboardType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "引擎状态同步异常: ${e.message}")
                // 同步失败（如重部署窗口期 rime.api 抛异常）时清除中→英标志，
                // 避免残留的 pendingEnglishMode 吞掉后续一次中英切换
                pendingEnglishMode = false
            }
        }
    }

    /**
     * 根据当前键盘类型同步 Rime 方案与状态（「布局 ↔ 方案」映射元数据见 [KeyboardType]）：
     * - 九宫格：切到专用 T9 方案（[KeyboardType.forcedSchemaId]）并保证中文模式
     *   （多击字母才能匹配拼音候选）
     * - 全键盘：对齐到用户持久化的全键盘方案偏好（[SchemaPreference]，
     *   替代早期易失的 schemeBeforeT9 内存记忆，进程重建后选择不丢）
     * 所有 selectSchema 均检查返回值，失败时记日志不静默吞掉；最后把状态同步到 UI。
     */
    private suspend fun applyEngineForKeyboard(type: KeyboardType) {
        when (type) {
            KeyboardType.NINE_GRID -> {
                val forced = requireNotNull(type.forcedSchemaId)
                if (rime.api.getCurrentSchema() != forced) {
                    if (!rime.api.selectSchema(forced)) {
                        Log.e(TAG, "切换九宫格专用方案失败: $forced（方案可能未编译/部署异常，可尝试设置页重新部署）")
                    }
                }
                if (rime.api.getOption("ascii_mode")) {
                    rime.api.setOption("ascii_mode", false)
                }
            }
            KeyboardType.QWERTY -> {
                // 对齐到用户的全键盘方案偏好（覆盖 t9 残留与外部意外切换）；
                // 偏好方案切换失败（如方案已移除）时回退默认方案兼底
                val preferred = SchemaPreference.getQwertySchema(this)
                if (rime.api.getCurrentSchema() != preferred) {
                    if (!rime.api.selectSchema(preferred)) {
                        Log.e(TAG, "恢复全键盘方案失败: $preferred，回退默认方案 ${SchemaPreference.DEFAULT_SCHEMA_ID}")
                        if (preferred != SchemaPreference.DEFAULT_SCHEMA_ID &&
                            !rime.api.selectSchema(SchemaPreference.DEFAULT_SCHEMA_ID)
                        ) {
                            Log.e(TAG, "回退默认方案也失败: ${SchemaPreference.DEFAULT_SCHEMA_ID}")
                        }
                    }
                }
                // 九宫格“中→英”触发：强制英文模式，避免与 handleSoftKeyPress 竞态
                if (pendingEnglishMode) {
                    rime.api.setOption("ascii_mode", true)
                    pendingEnglishMode = false
                }
            }
            KeyboardType.SYMBOL -> {
                // 符号键盘为临时面板：清除活跃编码避免残留 preedit，
                // 方案与 ascii_mode 保持不变，「返回」后无感恢复原键盘状态
                rime.api.clearComposition()
                keyRecordStack.clear()
            }
            KeyboardType.NUMBER -> {
                // 数字键盘与符号键盘同模式：临时面板，清编码不动方案与 ascii_mode
                rime.api.clearComposition()
                keyRecordStack.clear()
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
            updateToolbarVisibility(context)
            // 编码区同源同步（仅候选栏悬浮层，键盘视图不绘制编码）：九宫格按候选
            // 读音+实际击键还原预览；全键盘回退到 Rime 原始 preedit
            val preview = buildPinyinPreview(context)
            preeditOverlay?.setText(preview ?: context?.composition?.preedit)
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
        // 符号/数字键盘是临时面板，不持久化；重建输入视图时回到进入前的布局
        if (type == KeyboardType.SYMBOL || type == KeyboardType.NUMBER) return
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

        // 重新解析显示形态（横屏自动悬浮 / 设置页开关变更），不一致则重建输入视图
        displayModeCtrl.refreshIfChanged()?.let { resolved ->
            setInputView(buildInputView(resolved))
        }

        // 编辑器切换时实时重判图片能力（contentMimeTypes 动态检测 + 白名单兜底），
        // 刷新涂鸦面板「发送/保存」按钮（同应用内切换输入框时面板可能仍打开）
        doodlePanels.refreshImageSupport()

        // 换行键文案随编辑器动作变化（如微信搜索框显示「搜索」），与实际落地语义一致
        syncEnterKeyLabel()

        // 兜底同步当前剪贴板（服务重启期间漏听的复制在此补收；去重逻辑保证幂等零 IO）
        captureClipboardToHistory()

        // 词库下载后引擎可能正在重新部署，scheduleEngineSync 内部先等待就绪再同步，
        // 否则 rime.api 抛异常导致 t9 方案/ascii_mode 永不恢复，九宫格按键失效；
        // 同步前先清除之前的编码，再按当前键盘对齐方案与状态（九宫格会切到 T9 方案）
        scheduleEngineSync(ENGINE_READY_TIMEOUT_MS) {
            rime.api.clearComposition()
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

        // 强制关闭技能面板（销毁 WebView，避免后台常驻内存/定时器）与 AI/涂鸦/粘贴板/工具面板
        skillPanels.close()
        aiPanels.close()
        doodlePanels.close()
        clipboardPanels.close()
        toolPanels.close()

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

    /**
     * 系统内存吃紧回调：主动释放面板资源，降低低内存设备上 IME 进程被
     * LMK 优先猎杀的风险（技能面板 WebView 是 librime 常驻之上最大的内存增量，
     * 40~80MB，技能插件可行性方案 §9 承诺的主动回收落点）。
     * RUNNING_LOW 及更严重级别时关闭全部面板（各 close 均幂等，
     * 与 onFinishInputView 同一清理路径）。
     *
     * RUNNING_* 系列常量在 API 34+ 已废弃（不再下发），但 minSdk 24 区间的
     * 低版本设备仍会收到；API 34+ 上本判断自然退化为仅响应 UI_HIDDEN 及以上
     * 级别（数值更大），作为 onFinishInputView 之外的强制清理层。
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "onTrimMemory(level=$level)：关闭全部面板释放内存")
            skillPanels.close()
            aiPanels.close()
            doodlePanels.close()
            clipboardPanels.close()
            toolPanels.close()
            // 同步归还 native 堆持留的空闲页（部署残留，真机实测 20~27MB）
            if (RimeNative.isLoaded) RimeNative.trimNativeHeap()
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
                        // 视图不再预翻转，统一在此按引擎结果回写
                        keyboardView?.isChineseMode = currentAscii // 反转
                        Log.d(TAG, "切换中英文: ascii_mode=${!currentAscii}")
                    } catch (e: Exception) {
                        Log.e(TAG, "切换中英文异常: ${e.message}", e)
                    }
                }
            }

            // 悬浮/停靠形态切换（游戏悬浮键盘）：重建输入视图，引擎状态不受影响
            KeyCode.KEYCODE_TOGGLE_FLOATING -> {
                displayModeCtrl.toggle()
            }

            // 技能面板开关：覆盖/移除键盘区域上的技能面板（与其他面板互斥）
            KeyCode.KEYCODE_SKILL_PANEL -> {
                aiPanels.close()
                doodlePanels.close()
                clipboardPanels.close()
                toolPanels.close()
                keyboardPickers.close()
                skillPanels.toggle()
            }
            
            // AI 问答面板开关：编码区上方展示输入框/答案区（与其他面板互斥）
            KeyCode.KEYCODE_AI_ASSISTANT -> {
                skillPanels.close()
                doodlePanels.close()
                clipboardPanels.close()
                toolPanels.close()
                keyboardPickers.close()
                aiPanels.toggle()
            }
            
            // 涂鸦画板开关：收起键盘展示画布（与其他面板互斥）；
            // 编辑器不收图片时面板按钮转为「保存」（存相册兑底），
            // 仅当发送/保存两条出路都不可用（Android 10 以下且不收图）时拦截不开面板
            KeyCode.KEYCODE_DOODLE_PANEL -> {
                if (!doodlePanels.isOpen && !inputLogic.acceptsImageContent() &&
                    !GalleryImageSaver.isSupported) {
                    Toast.makeText(this, "当前输入框不支持发送图片", Toast.LENGTH_SHORT).show()
                } else {
                    skillPanels.close()
                    aiPanels.close()
                    clipboardPanels.close()
                    toolPanels.close()
                    keyboardPickers.close()
                    doodlePanels.toggle()
                }
            }
            
            // 粘贴板历史面板开关：收起键盘展示历史列表（与其他面板互斥）；
            // 点击条目经 commitDirectToEditor 直达宿主输入框，不接管 commitTarget
            KeyCode.KEYCODE_CLIPBOARD_PANEL -> {
                skillPanels.close()
                aiPanels.close()
                doodlePanels.close()
                toolPanels.close()
                keyboardPickers.close()
                clipboardPanels.toggle()
            }

            // 工具面板开关（候选区按钮栏 Logo 键）：收起键盘网格展示全部工具项
            //（与其他面板互斥）；选中工具后先关面板再回到本方法统一路由
            KeyCode.KEYCODE_TOOL_PANEL -> {
                skillPanels.close()
                aiPanels.close()
                doodlePanels.close()
                clipboardPanels.close()
                keyboardPickers.close()
                toolPanels.toggle()
            }

            // 键盘选择面板开关（功能栏「键盘切换」按钮）：收起键盘列表展示
            // 可选主键盘布局（与其他面板互斥）；选中后先关面板再走
            // switchKeyboard 统一切换路径，不触碰编辑器文本与光标
            KeyCode.KEYCODE_KEYBOARD_PICKER -> {
                skillPanels.close()
                aiPanels.close()
                doodlePanels.close()
                clipboardPanels.close()
                toolPanels.close()
                keyboardPickers.toggle()
            }

            // 收起键盘（候选区按钮栏）
            KeyCode.KEYCODE_HIDE_KEYBOARD -> {
                requestHideSelf(0)
            }

            // 打开设置页（工具面板「设置」项，原功能栏固定设置按钮已替换为 Logo）
            KeyCode.KEYCODE_OPEN_SETTINGS -> {
                openSettings()
            }

            // 循环切换皮肤（候选区按钮栏）：在已解锁皮肤间依次切换
            KeyCode.KEYCODE_SWITCH_THEME -> {
                cycleSkin()
            }

            // 循环切换全键盘输入方案（候选区按钮栏）：仅允许自选方案的布局生效
            KeyCode.KEYCODE_SWITCH_SCHEMA -> {
                cycleSchema()
            }

            // 符号键盘开关：记录进入前布局，再次触发（面板内「返回」键）时恢复
            KeyCode.KEYCODE_SYMBOL -> {
                if (currentKeyboardType == KeyboardType.SYMBOL) {
                    val restore = keyboardBeforeSymbol ?: KeyboardType.QWERTY
                    keyboardBeforeSymbol = null
                    switchKeyboard(restore)
                } else {
                    keyboardBeforeSymbol = currentKeyboardType
                    switchKeyboard(KeyboardType.SYMBOL)
                }
            }

            // 数字键盘开关：与符号键盘同模式，记录进入前布局，「返回」时恢复。
            // 九宫格底栏的「中数转换」键（KEYCODE_SWITCH_NUMBER_MODE）走同一路径，
            // 即切到数字键盘布局，而非切换 ascii_mode
            KeyCode.KEYCODE_NUMBER_KEYBOARD, KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> {
                if (currentKeyboardType == KeyboardType.NUMBER) {
                    val restore = keyboardBeforeNumber ?: KeyboardType.QWERTY
                    keyboardBeforeNumber = null
                    switchKeyboard(restore)
                } else {
                    keyboardBeforeNumber = currentKeyboardType
                    switchKeyboard(KeyboardType.NUMBER)
                }
            }

            // 普通按键：发送给Rime引擎
            else -> {
                // 九宫格模式下的智能退格
                if (keyCode == KeyCode.XK_BackSpace && currentKeyboardType == KeyboardType.NINE_GRID && !keyRecordStack.isEmpty()) {
                    val restoreCommand = keyRecordStack.popAndRestore()
                    if (restoreCommand != null) {
                        // replaceKey（底层 set_input）会清空引擎内全部已确认段：
                        // 先同步解除栈内确认标记，保持栈与引擎一致（无确认段时为空操作）
                        keyRecordStack.unconfirmAll()
                        // 撤销拼音选择：将已锁定拼音替换回原 T9 键
                        inputLogic.restorePinyin(restoreCommand)
                        return  // 不发送普通 BackSpace
                    }
                    // restoreCommand 为 null 表示弹出的是普通 T9Key/Apostrophe；
                    // 若仍存在已确认段，禁发普通 BackSpace（删空未确认键时引擎会
                    // Reopen 已确认段，把已确认汉字打回数字候选态），
                    // 改走 End+KP_Left+Delete 的无 Reopen 安全删除序列
                    if (keyRecordStack.hasConfirmed()) {
                        inputLogic.deleteUnconfirmedBackward()
                        return
                    }
                    // 无确认段：继续执行正常退格
                }
                // 九宫格模式下追踪 T9 按键
                if (currentKeyboardType == KeyboardType.NINE_GRID) {
                    when {
                        keyCode in '2'.code..'9'.code -> keyRecordStack.pushT9Key(keyCode.toChar())
                        keyCode == '\''.code -> {
                            // 分词键：无编码时为空操作（Rime 不消费会降级直接上屏撇号，
                            // 在此拦截避免把 ' 字符提交到编辑器）
                            if (keyRecordStack.isEmpty()) return
                            keyRecordStack.pushApostrophe()
                        }
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
     * 存在引擎已确认段时改走「退格重打」路径（replaceKey 会清空确认段，逐键重打可保留）。
     */
    private fun handlePinyinSelect(pinyin: String) {
        val beforeRaw = keyRecordStack.unconfirmedRawChars()
        val command = keyRecordStack.pushPinyinSelectAction(pinyin) ?: return
        if (keyRecordStack.hasConfirmed()) {
            inputLogic.retypeUnconfirmed(beforeRaw.length, keyRecordStack.unconfirmedRawChars())
        } else {
            inputLogic.selectPinyin(command)
        }
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
     * AI 面板「发图/存图」统一入口：点击时按当前编辑器图片能力实时路由——
     * 可收图走 commitContent 直发（[sendAnswerAsImage]），否则保存到相册
     * （[saveAnswerAsImage]）。气泡按钮标签为创建时快照，滞后也不会误发。
     */
    private fun submitAnswerImage(content: CharSequence) {
        if (inputLogic.acceptsImageContent()) {
            sendAnswerAsImage(content)
        } else {
            saveAnswerAsImage(content)
        }
    }

    /**
     * 将 AI 答案渲染为主题卡片图并经 commitContent 发送到当前输入框（AI 面板「发图」入口）。
     * 渲染/PNG 压缩在后台线程执行，提交与 Toast 反馈回主线程；面板打开期间
     * commitTarget 被占用，故经 [InputLogicController.commitImageToEditor] 绕过面板路由直达宿主编辑器。
     */
    private fun sendAnswerAsImage(content: CharSequence) {
        if (!inputLogic.acceptsImageContent()) {
            Toast.makeText(this, "当前输入框不支持发送图片", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        val skin = SkinManager.getCurrentSkin(this)
        serviceScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    TextImageRenderer.renderToPng(applicationContext, content, skin)
                }
                val uri = FileProvider.getUriForFile(
                    this@ZiYouInputMethodService, "$packageName.imecontent", file)
                val ok = inputLogic.commitImageToEditor(uri, "image/png", "AI 答案图片")
                if (!ok) {
                    Toast.makeText(this@ZiYouInputMethodService,
                        "发送图片失败或当前输入框不支持", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 答案转图片失败: ${e.message}", e)
                Toast.makeText(this@ZiYouInputMethodService, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将 AI 答案渲染为主题卡片图并保存到系统相册（AI 面板「存图」路径，
     * 编辑器不收图片时的兜底出口）。渲染/PNG 压缩在后台线程执行，相册写入在
     * IO 线程，Toast 反馈回主线程；Android 10 以下 MediaStore 免权限写入不可用，直接提示。
     */
    private fun saveAnswerAsImage(content: CharSequence) {
        if (!GalleryImageSaver.isSupported) {
            Toast.makeText(this, "保存到相册需要 Android 10 及以上系统", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        val skin = SkinManager.getCurrentSkin(this)
        serviceScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    TextImageRenderer.renderToPng(applicationContext, content, skin)
                }
                val ok = withContext(Dispatchers.IO) {
                    GalleryImageSaver.savePng(applicationContext, file.readBytes(), "ziyou_ai")
                }
                Toast.makeText(this@ZiYouInputMethodService,
                    if (ok) "已保存到相册" else "保存到相册失败", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "AI 答案存图失败: ${e.message}", e)
                Toast.makeText(this@ZiYouInputMethodService, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将涂鸦快照导出为 PNG 并经 commitContent 发送到当前输入框（涂鸦面板「发送」入口）。
     * 合成/PNG 压缩在后台线程执行，提交与 Toast 反馈回主线程；遵循面板期间直达宿主
     * 编辑器的路由纪律，经 [InputLogicController.commitImageToEditor] 提交；
     * 快照所有权在本方法，导出完成后 recycle；发送成功后自动关闭面板。
     */
    private fun sendDoodleAsImage(snapshot: Bitmap) {
        if (!inputLogic.acceptsImageContent()) {
            // 按钮态滞后兜底：点击瞬间编辑器已不收图则转存相册
            saveDoodleAsImage(snapshot)
            return
        }
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        serviceScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    try {
                        DoodleImageExporter.exportToPng(applicationContext, snapshot)
                    } finally {
                        snapshot.recycle()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    this@ZiYouInputMethodService, "$packageName.imecontent", file)
                val ok = inputLogic.commitImageToEditor(uri, "image/png", "涂鸦图片")
                if (ok) {
                    doodlePanels.close()
                } else {
                    Toast.makeText(this@ZiYouInputMethodService,
                        "发送图片失败或当前输入框不支持", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "涂鸦转图片失败: ${e.message}", e)
                Toast.makeText(this@ZiYouInputMethodService, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将涂鸦快照导出为 PNG 并保存到系统相册（涂鸦面板「保存」入口，
     * 编辑器不收图片时的兜底出口）。合成/PNG 压缩在后台线程执行，相册写入在
     * IO 线程，Toast 反馈回主线程；快照所有权在本方法，导出完成后 recycle；
     * 保存成功后自动关闭面板（与发送路径同一交互节奏）。
     */
    private fun saveDoodleAsImage(snapshot: Bitmap) {
        if (!GalleryImageSaver.isSupported) {
            snapshot.recycle()
            Toast.makeText(this, "保存到相册需要 Android 10 及以上系统", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        serviceScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    try {
                        DoodleImageExporter.exportToPng(applicationContext, snapshot)
                    } finally {
                        snapshot.recycle()
                    }
                }
                val ok = withContext(Dispatchers.IO) {
                    GalleryImageSaver.savePng(applicationContext, file.readBytes(), "ziyou_doodle")
                }
                if (ok) {
                    Toast.makeText(this@ZiYouInputMethodService, "已保存到相册", Toast.LENGTH_SHORT).show()
                    doodlePanels.close()
                } else {
                    Toast.makeText(this@ZiYouInputMethodService, "保存到相册失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "涂鸦存图失败: ${e.message}", e)
                Toast.makeText(this@ZiYouInputMethodService, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        // 无候选词且无活跃编码时显示候选区功能按钮栏
        updateToolbarVisibility(context)
        // 编码区同源同步（仅候选栏悬浮层，键盘视图不绘制编码）：九宫格按候选
        // 读音+实际击键还原预览，确保编码区与候选区拼音一致；全键盘回退到 Rime 原始 preedit
        val preview = buildPinyinPreview(context)
        preeditOverlay?.setText(preview ?: context?.composition?.preedit)
        // 左侧拼音侧栏：有候选拼音则展示拼音，否则展示自定义符号
        pinyinSideBar?.setPinyinCandidates(pinyinHints)
    }

    /**
     * 同步候选区功能按钮栏显隐（在主线程调用）：
     * 无候选词且无活跃编码时显示按钮栏，否则隐藏让位给编码区与候选词列表。
     * 按钮栏高度等于二者总高叠放，切换仅改 visibility，无高度跳动。
     */
    private fun updateToolbarVisibility(context: ContextProto?) {
        val idle = context?.menu?.candidates.isNullOrEmpty() &&
            context?.input.isNullOrEmpty()
        candidateToolbar?.visibility = if (idle) View.VISIBLE else View.GONE
    }

    /**
     * 打开设置页（候选区按钮栏入口）。
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * 在已解锁皮肤间循环切换（候选区按钮栏入口）。
     * setSkin 成功后皮肤快照在 IO 线程重建，就绪后经 [skinChangeListener]
     * 重建输入视图套用新皮肤并重同步引擎状态；引擎编码/方案不受影响。
     */
    private fun cycleSkin() {
        val unlocked = SkinManager.getUnlockedSkinIds(this)
        if (unlocked.size < 2) {
            Toast.makeText(this, "暂无其他已解锁皮肤", Toast.LENGTH_SHORT).show()
            return
        }
        val current = SkinManager.getCurrentSkinId(this)
        val next = unlocked[(unlocked.indexOf(current) + 1) % unlocked.size]
        SkinManager.setSkin(this, next)
    }

    /**
     * 皮肤快照就绪/变更回调（主线程）：
     * - STYLE_ONLY（仅颜色/字号/圆角等样式变化，背景图/字体/布局尺寸不变）：
     *   对现有视图原地 applySkin，跳过全量重建，保留输入状态；
     *   悬浮形态下悬浮容器链派用皮肤，仍走全量重建（保守策略）。
     * - FULL：重建输入视图套用新皮肤（与形态切换同源路径），并重同步引擎状态。
     * 输入视图尚未创建时跳过（onCreateInputView 自会取最新快照）。
     */
    private val skinChangeListener = object : SkinManager.SkinChangeListener {
        override fun onSkinChanged(skin: SkinTheme) =
            onSkinChanged(skin, SkinManager.SkinChangeKind.FULL)

        override fun onSkinChanged(skin: SkinTheme, kind: SkinManager.SkinChangeKind) {
            if (contentLayout == null) return
            if (kind == SkinManager.SkinChangeKind.STYLE_ONLY &&
                displayModeCtrl.currentMode == DisplayMode.DOCKED
            ) {
                applySkinToViews(skin)
            } else {
                keyboardView?.resetInputState()
                setInputView(buildInputView(displayModeCtrl.currentMode))
                scheduleEngineSync()
            }
        }
    }

    /** STYLE_ONLY 增量路径：对全部皮肤消费视图原地套用新皮肤（不重建视图树）。 */
    private fun applySkinToViews(skin: SkinTheme) {
        keyboardView?.applySkin(skin)
        pinyinSideBar?.applySkin(skin)
        candidatesView?.applySkin(skin)
        candidateToolbar?.applySkin(skin)
        preeditOverlay?.applySkin(skin)
    }

    /**
     * 在可选方案间循环切换全键盘输入方案（候选区按钮栏「方」键入口）。
     * 仅允许自选方案的布局（[KeyboardType.allowsSchemaChoice]）生效；
     * 布局专用方案（如 t9）不入循环候选。切换成功后写入 [SchemaPreference]
     * 偏好并重同步引擎状态，与设置页选择同一持久化链路。
     */
    private fun cycleSchema() {
        if (!currentKeyboardType.allowsSchemaChoice) {
            Toast.makeText(this, "当前键盘使用专用方案，请切换到全键盘后选择", Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            try {
                if (!awaitEngineReady(KEY_ENGINE_READY_TIMEOUT_MS)) {
                    Toast.makeText(this@ZiYouInputMethodService,
                        "引擎正在部署，请稍后再试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // 布局专用方案（如 t9）是实现细节，不作为用户选项
                val schemas = rime.api.getSchemaList()
                    .filter { it.schemaId !in KeyboardType.FORCED_SCHEMA_IDS }
                if (schemas.size < 2) {
                    Toast.makeText(this@ZiYouInputMethodService,
                        "暂无其他可选方案", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val current = rime.api.getCurrentSchema()
                val next = schemas[(schemas.indexOfFirst { it.schemaId == current } + 1) % schemas.size]
                if (!rime.api.selectSchema(next.schemaId)) {
                    Log.e(TAG, "循环切换方案失败: ${next.schemaId}")
                    Toast.makeText(this@ZiYouInputMethodService,
                        "切换方案失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                SchemaPreference.setQwertySchema(this@ZiYouInputMethodService, next.schemaId)
                Toast.makeText(this@ZiYouInputMethodService,
                    "已切换到: ${next.name}", Toast.LENGTH_SHORT).show()
                // 重同步引擎状态到 UI（新方案与偏好已一致，仅刷新中英态/候选区）
                scheduleEngineSync()
            } catch (e: Exception) {
                Log.e(TAG, "循环切换方案异常: ${e.message}", e)
            }
        }
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
        return PinyinHintProvider.buildHints(context, keyRecordStack.confirmedRawLength())
    }

    /**
     * 生成顶部编码区的"当前拼音"单串预览（委托 [PinyinHintProvider]，
     * 以高亮候选读音为消歧依据、以实际击键数为长度约束；
     * 分段确认后已确认前缀以汉字展示，未确认部分按状态机确认偏移切分）。
     * 非九宫格返回 null，由候选视图沿用 Rime 原始 preedit。
     */
    private fun buildPinyinPreview(context: ContextProto?): String? {
        if (currentKeyboardType != KeyboardType.NINE_GRID) return null
        return PinyinHintProvider.buildPreview(context, keyRecordStack.confirmedRawLength())
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
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "同步 ascii_mode 状态异常: ${e.message}")
                        }
                    }
                }
            }
            is RimeMessage.SchemaMessage -> {
                Log.d(TAG, "Rime方案切换: ${message.schemaId}")
                // 一致性守护：当前布局要求专用方案（如九宫格绑 t9）而引擎被外部
                // 切走（设置页/物理键盘热键）时立即重同步，不等下次获焦；
                // 自身 selectSchema 触发的通知方案已一致，不会循环
                val forced = currentKeyboardType.forcedSchemaId
                if (forced != null && message.schemaId != forced) {
                    Log.w(TAG, "方案与键盘布局不一致（收到 ${message.schemaId}，需要 $forced），触发重同步")
                    scheduleEngineSync()
                }
            }
            is RimeMessage.DeployMessage -> {
                Log.d(TAG, "Rime部署状态: ${message.status}")
                // 部署结束（成功/失败）后归还编译期临时分配持留的 native 空闲页
                //（非热路径，mallopt 线程安全）
                if (message.status != "start" && RimeNative.isLoaded) {
                    RimeNative.trimNativeHeap()
                }
                // 词库下载/启用后 RimeSession.redeploy 会整体重建引擎，方案与选项全部复位。
                // 待引擎就绪后重新同步当前键盘的方案与中英文状态（latest-wins 统一调度），
                // 否则九宫格停留在默认方案上，中/数切换等按键表现为“失效”。
                scheduleEngineSync(ENGINE_READY_TIMEOUT_MS)
            }
            is RimeMessage.UnknownMessage -> {
                Log.d(TAG, "Rime未知消息: type=${message.type}, value=${message.value}")
            }
        }
    }

    // ===== 清理 =====

    override fun onDestroy() {
        Log.i(TAG, "InputMethodService onDestroy")
        // 注销皮肤变更监听（与 onCreate 注册对称）
        SkinManager.removeListener(skinChangeListener)
        // 注销剪贴板监听（与 onCreate 注册对称）
        try {
            getSystemService(ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.w(TAG, "注销剪贴板监听失败: ${e.message}")
        }
        // 释放技能面板（销毁 WebView）与 AI 面板（取消进行中请求）
        skillPanels.close()
        aiPanels.close()
        // 服务销毁前落盘剩余的上屏计分
        LevelStats.flush()
        // 取消所有协程
        serviceScope.cancel()
        // 释放视图引用
        keyboardView = null
        keyboardContainer = null
        contentLayout = null
        candidatesContainer = null
        candidatesView = null
        candidateToolbar = null
        preeditOverlay = null
        pinyinSideBar = null
        displayModeCtrl.release()
        super.onDestroy()
    }
}
