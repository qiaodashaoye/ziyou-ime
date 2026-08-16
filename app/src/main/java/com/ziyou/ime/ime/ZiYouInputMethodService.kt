package com.ziyou.ime.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.os.Build
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
import com.ziyou.ime.ai.prediction.LlmPredictionConfig
import com.ziyou.ime.ai.prediction.LlmPredictionStats
import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.RimeMessage
import com.ziyou.ime.core.RimeNative
import com.ziyou.ime.core.prediction.AdoptionRecord
import com.ziyou.ime.core.prediction.AutoPunctPolicy
import com.ziyou.ime.core.prediction.CandidateFusion
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.data.ClipboardHistoryRepository
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
            engineSync.scheduleEngineSync()
        }
    }

    /** 面板公共宿主：统一提供 5 个共享方法实现，各面板 Host 委托到此避免重复。 */
    private val basePanelHost = object : BasePanelHost {
        override fun contentLayout() = this@ZiYouInputMethodService.contentLayout
        override fun keyboardContainer() = this@ZiYouInputMethodService.keyboardContainer
        override fun candidatesContainer() = this@ZiYouInputMethodService.candidatesContainer
        override fun keyboardView() = this@ZiYouInputMethodService.keyboardView
        override fun onPanelWillOpen() = clearCompositionForPanel()
    }

    /** 引擎状态同步控制器（布局切换 → 方案/模式同步 → UI 刷新，从 Service 拆分） */
    private val engineSync by lazy {
        EngineSyncController(engineSyncHost)
    }

    /** 提供给 [EngineSyncController] 的回调：引擎访问、视图刷新与键盘管理。 */
    private val engineSyncHost = object : EngineSyncController.Host {
        override val rime: RimeEngine get() = this@ZiYouInputMethodService.rime
        override val serviceScope get() = this@ZiYouInputMethodService.serviceScope
        override val currentKeyboardType get() = this@ZiYouInputMethodService.currentKeyboardType
        override val keyRecordStack get() = this@ZiYouInputMethodService.keyRecordStack
        override val serviceContext get() = this@ZiYouInputMethodService
        override fun installKeyboard(type: KeyboardType) =
            this@ZiYouInputMethodService.installKeyboard(type)
        override fun saveKeyboardType(type: KeyboardType) =
            this@ZiYouInputMethodService.saveKeyboardType(type)
        override fun clearPreeditPreview() {
            this@ZiYouInputMethodService.preeditOverlay?.setText(null)
        }
        override suspend fun renderFromEngine() {
            val ctx = this@ZiYouInputMethodService.rime.api.getContext()
            this@ZiYouInputMethodService.renderContext(ctx)
        }
        override fun setKeyboardChineseMode(isChinese: Boolean) {
            this@ZiYouInputMethodService.keyboardView?.isChineseMode = isChinese
        }
    }

    /** 技能面板协调器（面板生命周期与三态布局编排，从 Service 拆分） */
    private val skillPanels by lazy {
        SkillPanelCoordinator(this, skillPanelHost)
    }

    /** 提供给 [SkillPanelCoordinator] 的宿主能力：容器访问、上屏出口与输入路由切换。 */
    private val skillPanelHost = object : SkillPanelCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun isFloatingMode(): Boolean =
            displayModeCtrl.currentMode == DisplayMode.FLOATING

        override fun currentEditorInfo(): EditorInfo? = currentInputEditorInfo

        override fun commitText(text: String) = inputLogic.commitSideSymbol(text)

        override fun setCommitTarget(target: InputLogicController.CommitTarget?) {
            inputLogic.commitTarget = target
        }

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
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun setCommitTarget(target: InputLogicController.CommitTarget?) {
            inputLogic.commitTarget = target
        }

        override fun commitAnswerToEditor(text: String) = inputLogic.commitDirectToEditor(text)

        override fun commitAnswerImageToEditor(content: CharSequence) = submitAnswerImage(content)

        override fun editorAcceptsImage(): Boolean = inputLogic.acceptsImageContent()
    }

    /** 涂鸦画板面板协调器（面板生命周期与键盘收放编排，与 AI/技能面板同一拆分纪律） */
    private val doodlePanels by lazy {
        DoodlePanelCoordinator(this, doodlePanelHost)
    }

    /** 提供给 [DoodlePanelCoordinator] 的宿主能力：容器访问与图片发送出口。 */
    private val doodlePanelHost by lazy { object : DoodlePanelCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun sendDoodleImage(snapshot: Bitmap) =
            imageHelper.submitDoodle(snapshot)

        override fun saveDoodleImage(snapshot: Bitmap) =
            imageHelper.submitDoodle(snapshot)

        override fun imageSupportsSend(): Boolean = inputLogic.acceptsImageContent()
    } }

    /** 粘贴板历史面板协调器（面板生命周期与键盘收放编排，与 AI/技能/涂鸦面板同一拆分纪律） */
    private val clipboardPanels by lazy {
        ClipboardPanelCoordinator(this, clipboardPanelHost)
    }

    /** 提供给 [ClipboardPanelCoordinator] 的宿主能力：容器访问与粘贴出口。 */
    private val clipboardPanelHost = object : ClipboardPanelCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun isFloatingMode(): Boolean =
            displayModeCtrl.currentMode == DisplayMode.FLOATING

        override fun pasteToEditor(text: String) = inputLogic.commitDirectToEditor(text)
    }

    /** 工具面板协调器（Logo 键入口，面板生命周期与键盘收放编排，与其他面板同一拆分纪律） */
    private val toolPanels by lazy {
        ToolPanelCoordinator(this, toolPanelHost)
    }

    /** 提供给 [ToolPanelCoordinator] 的宿主能力：容器访问与功能码分发出口。 */
    private val toolPanelHost = object : ToolPanelCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun dispatchToolKey(keyCode: Int) = handleSoftKeyPress(keyCode, 0)
    }

    /** 键盘选择面板协调器（功能栏「键盘切换」入口，与其他面板同一拆分纪律） */
    private val keyboardPickers by lazy {
        KeyboardPickerCoordinator(this, keyboardPickerHost)
    }

    /** 提供给 [KeyboardPickerCoordinator] 的宿主能力：容器访问与布局切换出口。 */
    private val keyboardPickerHost = object : KeyboardPickerCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun currentKeyboardType(): KeyboardType =
            this@ZiYouInputMethodService.currentKeyboardType

        override fun switchKeyboard(type: KeyboardType) =
            this@ZiYouInputMethodService.switchKeyboard(type)
    }

    /** 语音输入面板协调器（会话编排与键盘收放，与其他面板同一拆分纪律） */
    private val voicePanels by lazy {
        VoicePanelCoordinator(this, voicePanelHost, serviceScope)
    }

    /** 提供给 [VoicePanelCoordinator] 的宿主能力：容器访问与语音文本直达上屏出口。 */
    private val voicePanelHost = object : VoicePanelCoordinator.Host {
        override fun contentLayout() = basePanelHost.contentLayout()
        override fun keyboardContainer() = basePanelHost.keyboardContainer()
        override fun candidatesContainer() = basePanelHost.candidatesContainer()
        override fun keyboardView() = basePanelHost.keyboardView()
        override fun onPanelWillOpen() = basePanelHost.onPanelWillOpen()

        override fun isFloatingMode(): Boolean =
            displayModeCtrl.currentMode == DisplayMode.FLOATING

        override fun commitVoiceTextToEditor(text: String) = inputLogic.commitDirectToEditor(text)

        override fun openVoiceSettings(requestPermission: Boolean) {
            val intent = Intent(this@ZiYouInputMethodService, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SettingsActivity.EXTRA_OPEN_VOICE, true)
                putExtra(SettingsActivity.EXTRA_VOICE_REQUEST_PERMISSION, requestPermission)
            }
            startActivity(intent)
        }
    }

    /** 剪贴板变更监听：复制即收录历史（持强引用，键盘可见期注册/注销，见 [startClipboardWatch]） */
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboardToHistory()
    }

    /** 剪贴板监听是否已注册（防 onStartInputView 重复触发时重复注册） */
    private var clipboardWatching = false

    /**
     * 开始监听剪贴板变更（键盘可见期，onStartInputView 调用）。
     *
     * 耗电审计 P0：监听收窄到键盘可见期——键盘隐藏时系统剪贴板变更不再
     * 唤醒本进程收录历史（重度复制场景下消除频繁的进程唤醒）；
     * 键盘隐藏期间的复制不会丢失，[onStartInputView] 的兜底同步会补收。
     * （Android 10+ 后台读剪贴板仅默认输入法豁免，非默认时读到 null 自然跳过）
     */
    private fun startClipboardWatch() {
        if (clipboardWatching) return
        try {
            getSystemService(ClipboardManager::class.java)
                ?.addPrimaryClipChangedListener(clipboardListener)
            clipboardWatching = true
        } catch (e: Exception) {
            Log.w(TAG, "注册剪贴板监听失败: ${e.message}")
        }
    }

    /** 停止监听剪贴板变更（onFinishInputView / onDestroy 兜底，幂等）。 */
    private fun stopClipboardWatch() {
        if (!clipboardWatching) return
        clipboardWatching = false
        try {
            getSystemService(ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.w(TAG, "注销剪贴板监听失败: ${e.message}")
        }
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

    /**
     * 关闭全部面板（技能 / AI / 涂鸦 / 粘贴板 / 工具 / 键盘选择 / 语音）。
     * 各 close 均幂等，已关闭的面板不受影响。供面板互斥切换、视图重建、
     * 内存回收与 Service 销毁等场景统一调用。
     */
    private fun closeAllPanels() {
        skillPanels.close()
        aiPanels.close()
        doodlePanels.close()
        clipboardPanels.close()
        toolPanels.close()
        keyboardPickers.close()
        voicePanels.close()
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

    /** 九宫格输入状态追踪栈（拼音消歧与智能回退） */
    private val keyRecordStack = KeyRecordStack()

    /**
     * 最近一次 renderContext 是否处于「无活跃编码」态（input 为空）。
     * LLM 续写的追加窗口以此为准而非「预测态」（menu 非空且 input 为空）：
     * 句末标点上屏时引擎会主动清预测、predict.db 未收录的词 menu 也为空，
     * 这两种 menu 空的时刻恰是 LLM 续写最有价值的场景，不得拒绝。
     * 结果回调时二次校验：仅在仍无活跃编码时追加渲染，否则丢弃。
     */
    private var llmAppendAllowed = false

    /**
     * 最近一次 renderContext 是否为引擎预测态（menu 非空且 input 为空）。
     * 候选点击时据此判定是否属于「采纳引擎预测词」，触发自动补标点
     *（AutoPunctPolicy）——仅预测态候选享有此行为，普通组句候选不插标点。
     */
    private var lastEnginePredictionMode = false

    /** 当前已追加展示的 LLM 续写词（与 [llmCandidatesStartIndex] 配套，点击分段路由用） */
    private var llmCandidates: List<String> = emptyList()

    /** 本次服务实例是否已发起过词窗口预热（S9：每服务实例至多一次，防输入框频繁切换重复预热） */
    private var llmPrewarmIssued = false

    /** 上次 renderContext 是否有活跃编码（S6 决策数据：编码→空闲过渡时结算联想在场情况） */
    private var wasComposingLastRender = false

    /** LLM 追加词在候选流中的起始 index（= 追加时刻的引擎候选总数） */
    private var llmCandidatesStartIndex = 0

    /** 输入逻辑控制器（与 Rime 交互、上屏、刷新 UI），经 DI 容器获取引擎与上屏监听。 */
    private val inputLogic by lazy {
        InputLogicController(
            AppContainer.rimeEngine, serviceScope, keyRecordStack,
            inputLogicCallbacks, AppContainer.commitListeners,
            AppContainer.commitTextObservers
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

    /** 图片发送/保存辅助类（AI 答案图 + 涂鸦快照，收敛 Service 内四个相似方法）。 */
    private val imageHelper: ImageCommitHelper by lazy {
        ImageCommitHelper(
            this, serviceScope, inputLogic,
            onDoodleSent = { doodlePanels.close() },
            onDoodleSaved = { doodlePanels.close() }
        )
    }

    // ===== 生命周期 =====

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InputMethodService onCreate")

        // 初始化等级计分（热路径仅做内存自增，此处仅注入 applicationContext）
        LevelStats.init(applicationContext)

        // LLM 智能续写结果回调（主线程）：仍处于「无活跃编码」态才追加渲染，
        // 否则丢弃——迟到的词不允许挂到新一轮编码的候选栏上（epoch 守卫的
        // 视图层第二道防线）。追加前经 CandidateFusion 双重去重：与引擎候选
        // 重复时保留引擎身份；与上下文词（最近上屏词，含刚采纳的续写词）
        // 重复时丢弃——防模型复读把刚上屏的词再次推回候选栏
        AppContainer.llmPredictionCoordinator.onResult = { _, candidates, contextWords ->
            val view = candidatesView
            if (llmAppendAllowed && view != null && candidates.isNotEmpty()) {
                val engineTexts = view.engineCandidateTexts()
                val fused = CandidateFusion.fuse(
                    engineTexts, candidates, engineTexts.size + 5, exclude = contextWords
                )
                val llmPart = fused.subList(engineTexts.size.coerceAtMost(fused.size), fused.size)
                if (llmPart.isNotEmpty()) {
                    llmCandidatesStartIndex = view.engineCandidateCount()
                    llmCandidates = llmPart
                    view.appendLlmCandidates(llmPart)
                    // 引擎 menu 为空时功能按钮栏可见（updateToolbarVisibility），
                    // 追加 LLM 候选后隐藏避免两栏重叠；menu 非空时已隐藏，幂等无害
                    candidateToolbar?.visibility = View.GONE
                }
            }
        }

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

        // 剪贴板监听已收窄到键盘可见期（onStartInputView 注册，耗电审计 P0），
        // 此处不再常驻注册；onDestroy 保留兜底注销

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
        // 重建前释放全部面板（旧容器即将废弃，WebView/进行中请求/录音会话必须显式释放）
        closeAllPanels()
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
            onCandidateClick = { index, candidate -> handleCandidateClick(index, candidate) }
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
     * 九宫格"中→英"专用切换：强制 ascii_mode=true 并切到 QWERTY。
     * 不走 handleSoftKeyPress 异步路径，避免与 applyEngineForKeyboard 竞态。
     * 同时记录进入前布局，供 QWERTY 上英→中时返回原布局（如九宫格）。
     */
    private fun switchToQwertyEnglish() {
        engineSync.switchToQwertyEnglish()
    }

    /**
     * 切换键盘布局，重建视图并同步方案 / 中英文模式 / 编码区。
     * 委托 [EngineSyncController.switchKeyboard] 实现。
     */
    private fun switchKeyboard(type: KeyboardType) {
        engineSync.switchKeyboard(type)
    }

    // [applyEngineForKeyboard] 已委托 [EngineSyncController]

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
     * 输入会话开始（含同一 App 内切换输入框）。
     *
     * LLM 智能续写：同 App 内切换输入框时键盘视图不收起，只回调本方法而
     * 不再回调 [onStartInputView]，必须在此清空词窗口/缓存，否则 A 输入框的
     * 上下文会泄漏进 B 的会话并随之发往第三方服务（隐私红线，设计文档 §4.6）。
     * 纯内存操作，零 IO；与 onStartInputView/onFinishInputView 的 reset 幂等重复无害。
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        AppContainer.llmPredictionCoordinator.reset()
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

        // 兜底同步当前剪贴板（服务重启/键盘隐藏期间漏听的复制在此补收；去重逻辑保证幂等零 IO）
        captureClipboardToHistory()

        // 键盘可见期才监听剪贴板变更（耗电审计 P0：消除键盘隐藏时的进程唤醒）
        startClipboardWatch()

        // LLM 智能续写：新输入会话开始，清空词窗口并作废 in-flight 请求
        //（防跨输入框上下文泄漏；纯内存操作，零 IO；LRU 缓存跨会话保留，
        // 重复语境的命中价值正在于此，内容仅候选词无泄漏面）
        AppContainer.llmPredictionCoordinator.reset()

        // 词窗口预热（联想优化方案 §4.4）：协调器内部等待缓存装载完成信号后按热度
        // 静默保温（S9 事件驱动），开关关闭时零成本返回；不阻塞输入视图启动；
        // 每服务实例至多一次（onStartInputView 随输入框切换频繁触发）
        if (!llmPrewarmIssued) {
            llmPrewarmIssued = true
            AppContainer.llmPredictionCoordinator.prewarm()
        }

        // 词库下载后引擎可能正在重新部署，scheduleEngineSync 内部先等待就绪再同步，
        // 否则 rime.api 抛异常导致 t9 方案/ascii_mode 永不恢复，九宫格按键失效；
        // 同步前先清除之前的编码，再按当前键盘对齐方案与状态（九宫格会切到 T9 方案）
        engineSync.scheduleEngineSync(EngineSyncController.ENGINE_READY_TIMEOUT_MS) {
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

        // 键盘隐藏：停止剪贴板监听，避免键盘不可见时系统复制事件唤醒本进程（耗电审计 P0）
        stopClipboardWatch()

        // 关闭全部面板（技能面板 WebView、AI 请求、涂鸦、粘贴板、工具、语音），避免后台常驻
        closeAllPanels()

        // 丢弃未提交的多击预览并清空编码区
        keyboardView?.resetInputState()
        preeditOverlay?.setText(null)

        // 输入视图隐藏，主动落盘累计的上屏计分，避免尾部数据丢失
        LevelStats.flush()

        // LLM 智能续写：同点位落盘候选缓存快照与采纳攒批（异步 IO，不阻塞）
        AppContainer.llmPredictionCoordinator.flush()

        // LLM 智能续写：输入视图收起即会话结束，清空上下文（与 onStartInputView 对称）
        AppContainer.llmPredictionCoordinator.reset()

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
            closeAllPanels()
            // 同步归还 native 堆持留的空闲页（部署残留，真机实测 20~27MB）
            if (RimeNative.isLoaded) RimeNative.trimNativeHeap()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // 高水位连语音识别模型（数百 MB native）一并释放；面板已在上方关闭，
            // 引擎 release 后经 AppContainer 懒获取可重建，不影响后续使用
            AppContainer.speechEngine.release()
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
                if (engineSync.pendingEnglishMode) {
                    engineSync.pendingEnglishMode = false
                    return
                }
                serviceScope.launch {
                    try {
                        if (!engineSync.awaitEngineReady(EngineSyncController.KEY_ENGINE_READY_TIMEOUT_MS)) {
                            Log.w(TAG, "切换中英文失败：Rime引擎未就绪（可能正在重新部署）")
                            return@launch
                        }
                        val currentAscii = rime.api.getOption("ascii_mode")
                        // 九宫格“中→英”返回：QWERTY 英文下按中英键时恢复进入前布局
                        // （applyEngineForKeyboard 保证切回 t9 方案并强制中文模式），
                        // 而非仅翻转 ascii_mode 停留在 QWERTY
                        val origin = engineSync.qwertyEnglishOrigin
                        if (currentAscii && origin != null && currentKeyboardType == KeyboardType.QWERTY) {
                            engineSync.qwertyEnglishOrigin = null
                            Log.d(TAG, "英→中返回进入前布局: $origin")
                            switchKeyboard(origin)
                            return@launch
                        }
                        // 其他翻转场景消费标记，避免后续切换被陈旧标记误恢复
                        engineSync.qwertyEnglishOrigin = null
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
                closeAllPanels()
                skillPanels.toggle()
            }
            
            // AI 问答面板开关：编码区上方展示输入框/答案区（与其他面板互斥）
            KeyCode.KEYCODE_AI_ASSISTANT -> {
                closeAllPanels()
                aiPanels.openAsk()
            }

            // 人设润色面板开关：草稿经人设改写为候选后选择性上屏（与其他面板互斥）
            KeyCode.KEYCODE_AI_POLISH -> {
                closeAllPanels()
                aiPanels.openPolish()
            }
            
            // 涂鸦画板开关：收起键盘展示画布（与其他面板互斥）；
            // 编辑器不收图片时面板按钮转为「保存」（存相册兑底），
            // 仅当发送/保存两条出路都不可用（Android 10 以下且不收图）时拦截不开面板
            KeyCode.KEYCODE_DOODLE_PANEL -> {
                if (!doodlePanels.isOpen && !inputLogic.acceptsImageContent() &&
                    !GalleryImageSaver.isSupported) {
                    Toast.makeText(this, "当前输入框不支持发送图片", Toast.LENGTH_SHORT).show()
                } else {
                    closeAllPanels()
                    doodlePanels.toggle()
                }
            }
            
            // 粘贴板历史面板开关：收起键盘展示历史列表（与其他面板互斥）；
            // 点击条目经 commitDirectToEditor 直达宿主输入框，不接管 commitTarget
            KeyCode.KEYCODE_CLIPBOARD_PANEL -> {
                closeAllPanels()
                clipboardPanels.toggle()
            }

            // 工具面板开关（候选区按钮栏 Logo 键）：收起键盘网格展示全部工具项
            //（与其他面板互斥）；选中工具后先关面板再回到本方法统一路由
            KeyCode.KEYCODE_TOOL_PANEL -> {
                closeAllPanels()
                toolPanels.toggle()
            }

            // 键盘选择面板开关（功能栏「键盘切换」按钮）：收起键盘列表展示
            // 可选主键盘布局（与其他面板互斥）；选中后先关面板再走
            // switchKeyboard 统一切换路径，不触碰编辑器文本与光标
            KeyCode.KEYCODE_KEYBOARD_PICKER -> {
                closeAllPanels()
                keyboardPickers.toggle()
            }

            // 语音输入面板开关：收起键盘展示语音面板（与其他面板互斥）；
            // 识别文本经 commitDirectToEditor 直达宿主输入框，不接管 commitTarget；
            // 权限/模型未就绪时面板内展示引导态，跳转设置页语音入口
            KeyCode.KEYCODE_VOICE_PANEL -> {
                closeAllPanels()
                voicePanels.toggle()
            }

            // 收起键盘（候选区按钮栏）
            KeyCode.KEYCODE_HIDE_KEYBOARD -> {
                requestHideSelf(0)
            }

            // 打开设置页（工具面板标题栏「设置」按钮，原功能栏固定设置按钮已替换为 Logo）
            KeyCode.KEYCODE_OPEN_SETTINGS -> {
                openSettings()
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
                    // 退格前的未确认原始编码（存在确认段时供「退格重打」计算删除量）
                    val rawBeforeRestore = keyRecordStack.unconfirmedRawChars()
                    val restoreCommand = keyRecordStack.popAndRestore()
                    if (restoreCommand != null) {
                        if (keyRecordStack.hasConfirmed()) {
                            // 存在已确认段：replaceKey（底层 set_input）会重建 composition，
                            // 清空引擎内全部已确认段——已确认汉字与已锁定拼音被全量
                            // 打回原始数字（如 你hao → 64426）。改走与 handlePinyinSelect
                            // 同源的「退格重打」：只解锁尾部已锁定拼音，确认前缀全程保留
                            inputLogic.retypeUnconfirmed(
                                rawBeforeRestore.length,
                                keyRecordStack.unconfirmedRawChars()
                            )
                        } else {
                            // 无确认段：撤销拼音选择，将已锁定拼音替换回原 T9 键
                            inputLogic.restorePinyin(restoreCommand)
                        }
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

    /** 处理候选词点击（含引擎预测词与 LLM 续写词，按 index 分段路由）；
     *  携带被点候选本体供分段确认同步（跨页时引擎当前页 menu 查不到其注音）。 */
    private fun handleCandidateClick(index: Int, candidate: CandidateProto) {
        val llmOffset = index - llmCandidatesStartIndex
        if (llmCandidates.isNotEmpty() && llmOffset in llmCandidates.indices) {
            // LLM 续写词（index ≥ 引擎候选总数）：不走 Rime 选词，直达宿主编辑器。
            // 绕过 commitTarget 是有意为之——预测态宿主必为真实编辑器；
            // commitDirectToEditor 已回调 commitTextObservers，被采纳的续写词进入
            // 下一轮上下文，续写链自然延续。
            val adopted = llmCandidates[llmOffset]
            val hadEnginePrediction = llmCandidatesStartIndex > 0
            // S8 决策数据：LLM 词采纳时引擎候选是否在场（位置策略 A/B 依据）
            LlmPredictionStats.onLlmAdoption(hadEnginePrediction)
            // 采纳词对攒批（联想优化方案 §4.6 形态 B）：主线程 O(1) 计数，
            // 构建期固化进自建 predict.db，运行时不参与排序
            AppContainer.llmPredictionCoordinator.recordAdoption(
                lastLearnableWord(AppContainer.llmPredictionCoordinator.contextWords()), adopted
            )
            // 自动补标点：前文与续写词之间无标点时前置逗号（单次 commit 同落，
            // 顺序天然保证）；候选自带前导标点/前文已带标点时策略返回空串
            val prefix = AutoPunctPolicy.decidePrefix(
                AppContainer.llmPredictionCoordinator.contextWords(), adopted
            )
            // 采纳后先清候选栏残留：被采纳的词不得继续挂在候选栏上（视图 LLM
            // 尾部与 Service 路由记录同步清零；新一轮请求的结果到达后会重建）
            llmCandidates = emptyList()
            candidatesView?.clearLlmCandidates()
            inputLogic.commitDirectToEditor(prefix + adopted)
            // 直达上屏绕过了 Rime：若候选栏里曾有引擎预测词，引擎内部残留的
            // prediction 占位段会令陈旧预测候选留在 menu（含刚被采纳的同形词）。
            // 发 Escape 让 predictor 清预测与占位段（predictor.cc ProcessKeyEvent）
            // 并重新渲染，终止陈旧预测态；仅在确有引擎预测候选时发，避免误触
            if (hadEnginePrediction) {
                inputLogic.clearStalePrediction()
            }
            return
        }
        // 引擎预测候选采纳：自动补标点。标点必须先于预测词落编辑器——
        // 主线程同步提交标点后 selectCandidate 协程才入队（Main FIFO 保序）；
        // 非预测态（普通组句候选）不插标点，策略内部另有防叠加规则
        if (lastEnginePredictionMode) {
            // 引擎预测词采纳同样计入攒批（预测采纳才记，普通组句候选不计）
            AppContainer.llmPredictionCoordinator.recordAdoption(
                lastLearnableWord(AppContainer.llmPredictionCoordinator.contextWords()), candidate.text
            )
            val prefix = AutoPunctPolicy.decidePrefix(
                AppContainer.llmPredictionCoordinator.contextWords(), candidate.text
            )
            if (prefix.isNotEmpty()) {
                inputLogic.commitAutoPunctuation(prefix)
            } else if (AutoPunctPolicy.isPoetryChain(
                    AppContainer.llmPredictionCoordinator.contextWords(), candidate.text
                )
            ) {
                // 诗句链路采纳埋点（一句诗联想整首诗，仅计数无词内容）
                LlmPredictionStats.onPoetryAdoption()
            }
        }
        inputLogic.selectCandidate(index, candidate)
    }

    /** 处理翻页。@param forward true=下一页, false=上一页 */
    private fun handlePageChange(forward: Boolean) = inputLogic.changePage(forward)

    /**
     * 取词窗口末位的可学习词（采纳词对的 prev）；窗口无 1~4 字纯汉字词时
     * 返回空串，[AdoptionRecord] 会静默忽略（不产出非法词对）。
     */
    private fun lastLearnableWord(words: List<String>): String =
        words.lastOrNull { AdoptionRecord.isLearnableWord(it) } ?: ""

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
     * AI 面板「发图/存图」统一入口（委托 [ImageCommitHelper]）。
     * 气泡按钮标签为创建时快照，滞后也不会误发。
     */
    private fun submitAnswerImage(content: CharSequence) = imageHelper.submitAnswer(content)

    /**
     * 根据最新 Rime 上下文刷新候选词、编码区与拼音侧栏（在主线程调用）。
     * 供 [InputLogicController] 通过回调驱动。
     */
    private fun renderContext(context: ContextProto?) {
        val pinyinHints = if (currentKeyboardType != KeyboardType.NINE_GRID) null
            else PinyinHintProvider.buildHints(context, keyRecordStack.confirmedRawLength())
        // 更新候选词视图；预测态（引擎在 commit 后产生 prediction 候选：菜单非空且
        // 编码串为空）复用联想强调色，与普通候选词区分；未启用 predict 模块时该分支休眠
        val predictionMode = context?.menu?.candidates?.isNotEmpty() == true && context.input.isEmpty()
        candidatesView?.updateCandidates(context, predictionMode)
        lastEnginePredictionMode = predictionMode
        // 预测式预取（联想优化方案 §4.5）：引擎预测渲染后预热首个预测词的
        // 下一轮 LLM 缓存，采纳后链式第二轮零等待；开关关闭/限流/有 in-flight
        // 请求时协调器内部静默返回，渲染热路径仅一次布尔判断成本
        if (predictionMode && LlmPredictionConfig.isEnabled(applicationContext)) {
            val texts = context.menu.candidates.take(3).map { it.text }.filter { it.isNotEmpty() }
            if (texts.isNotEmpty()) {
                AppContainer.llmPredictionCoordinator.prefetch(texts)
            }
        }
        // LLM 追加窗口以「无活跃编码」为准：句末标点上屏引擎清预测（menu 空）、
        // predict.db 未收录的上屏词 menu 也为空——若以预测态为门控，这两种 LLM
        // 续写最有价值的场景会被 invalidate/结果丢弃误杀（新编码开始才是真正作废时刻）
        val idle = context?.input.isNullOrEmpty() != false
        llmAppendAllowed = idle
        // S6 决策数据：编码→空闲过渡即一次「上屏后联想在场情况」采样——
        // 引擎预测在场计 shown，无任何候选计 gap（句末标点/db 未收录时刻）；
        // gap/(shown+gap) 达显著比例时才有句末兑底方案的立项依据（分析报告 S6）
        if (wasComposingLastRender && idle) {
            if (predictionMode) LlmPredictionStats.onEnginePredictionShown()
            else LlmPredictionStats.onAssociationGap()
        }
        wasComposingLastRender = !idle
        // 引擎新渲染已作废视图侧 LLM 追加尾部（updateCandidates 内清空），
        // Service 侧路由记录同步清零，防陈旧分段误路由
        llmCandidates = emptyList()
        if (!idle) {
            // 用户开始新编码：作废待发射/in-flight 的 LLM 请求。
            // renderContext 在主线程，invalidate 只做 epoch++ 与 cancel job，足够轻量
            AppContainer.llmPredictionCoordinator.invalidate()
        }
        // 无候选词且无活跃编码时显示候选区功能按钮栏
        updateToolbarVisibility(context)
        // 编码区同源同步（仅候选栏悬浮层，键盘视图不绘制编码）：九宫格按候选
        // 读音+实际击键还原预览，确保编码区与候选区拼音一致；全键盘回退到 Rime 原始 preedit
        val preview = if (currentKeyboardType != KeyboardType.NINE_GRID) null
            else PinyinHintProvider.buildPreview(context, keyRecordStack.confirmedRawLength())
        preeditOverlay?.setText(preview ?: context?.composition?.preedit)
        // 左侧拼音侧栏：有候选拼音则展示拼音，否则展示自定义符号
        // 仅在九宫格模式下更新拼音候选；数字键盘侧栏始终为符号模式
        if (currentKeyboardType == KeyboardType.NINE_GRID) {
            pinyinSideBar?.setPinyinCandidates(pinyinHints)
        }
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
                engineSync.scheduleEngineSync()
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
                    engineSync.scheduleEngineSync()
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
                engineSync.scheduleEngineSync(EngineSyncController.ENGINE_READY_TIMEOUT_MS)
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
        // 兜底注销剪贴板监听（正常路径已在 onFinishInputView 注销，此处幂等）
        stopClipboardWatch()
        // 释放全部面板（技能 WebView / AI 请求 / 涂鸦 / 粘贴板 / 工具 / 键盘选择 / 语音）
        closeAllPanels()
        // 解绑 LLM 续写结果回调（协调器为进程级单例，持回调会泄漏已销毁 Service）
        AppContainer.llmPredictionCoordinator.onResult = null
        // 服务销毁前落盘候选缓存快照与采纳攒批（内部异步 IO）
        AppContainer.llmPredictionCoordinator.flush()
        AppContainer.speechEngine.release()
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
