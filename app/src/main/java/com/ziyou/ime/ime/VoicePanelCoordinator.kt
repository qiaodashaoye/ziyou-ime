package com.ziyou.ime.ime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.ziyou.ime.core.voice.VoiceSessionEvent
import com.ziyou.ime.core.voice.VoiceSessionStateMachine
import com.ziyou.ime.core.voice.VoiceUtteranceBuffer
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.voice.SpeechRecognizerEngine
import com.ziyou.ime.voice.VoiceModelManager
import com.ziyou.ime.voice.VoiceModelManager.VoiceCommitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音输入面板协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离「语音面板生命周期 + 会话编排 + 键盘收放」职责，
 * 与 [ClipboardPanelCoordinator] / [AiPanelCoordinator] 遵循同一拆分纪律。
 *
 * 与粘贴板面板同款布局策略：打开即收起键盘/候选区，面板接管二者实测高度之和
 * （高度守恒），且**不接管 commitTarget 输入路由**——识别文本经
 * [Host.commitVoiceTextToEditor] 走 commitDirectToEditor 直达宿主输入框，
 * 对 Rime 引擎与输入热路径零侵入（识别/采集线程与 RimeDispatcher 零交集）。
 *
 * 上屏策略（[VoiceCommitMode]，设置页与面板内均可切换）：
 * - AUTO_COMMIT（默认）：每句端点确认即上屏；
 * - BUFFER_SEND：面板内缓冲，用户点「发送」一次上屏。
 *
 * 与技能面板同一形态约束：悬浮键盘模式下不开放，Toast 提示。
 *
 * 所有方法均须在主线程调用；引擎回调来自解码线程，统一经面板 post 切回主线程。
 */
@MainThread
class VoicePanelCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host,
    private val scope: CoroutineScope,
) {

    companion object {
        /** 键盘/候选区尚未完成测量时的面板兜底高度（dp） */
        private const val FALLBACK_PANEL_HEIGHT_DP = 300f

        /** 聆听态静默超时：始终没说话，自动结束（ms） */
        private const val LISTENING_TIMEOUT_MS = 5_000L

        /** 说话中/断句后静默超时：判定说完了，自动结束（ms） */
        private const val COOLDOWN_TIMEOUT_MS = 4_000L

        /** 会话绝对时长上限：噪声环境下 partial 可能持续重 arm 静默定时器使会话
         *  无限延长（麦克风+解码持续烧 CPU），到期后下一次定时器触发时强制收尾（ms） */
        private const val MAX_SESSION_MS = 60_000L
    }

    /** 协调器需要 Service 提供的能力：视图容器访问与上屏出口。 */
    interface Host : BasePanelHost {
        /** 当前是否悬浮键盘模式（悬浮下不开放语音面板，与技能面板同一约束） */
        fun isFloatingMode(): Boolean

        /** 语音文本上屏到宿主输入框（commitDirectToEditor，绕过面板路由） */
        fun commitVoiceTextToEditor(text: String)

        /** 打开设置页语音入口（授权引导时 requestPermission=true 直达系统权限弹窗） */
        fun openVoiceSettings(requestPermission: Boolean)
    }

    /** 语音面板（仅打开时非空）。@Volatile：解码线程的回调会先读它判空再 post，
     *  主线程写入需对其可见（最坏情形仅是多投一个被二次判空拦下的 no-op）。 */
    @Volatile
    private var panel: VoicePanelView? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 语音识别引擎（经 DI 容器，测试可注入 fake） */
    private val engine: SpeechRecognizerEngine get() = AppContainer.speechEngine

    private val fsm = VoiceSessionStateMachine()
    private val buffer = VoiceUtteranceBuffer()
    private var commitMode = VoiceCommitMode.AUTO_COMMIT

    /** 静默超时任务（每次识别产出到达时重置） */
    private var silenceTimer: Runnable? = null

    /** 本轮会话绝对时长截止点（[startListening] 时设置，硬超时兜底） */
    private var sessionDeadlineMs = 0L

    /** 模型异步加载任务（面板关闭时取消） */
    private var modelLoadJob: Job? = null

    /** 面板开关切换（候选区按钮栏「声」键 / 工具面板入口）。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 打开语音面板：挂载到内容根容器顶部并立即收起键盘/候选区，
     * 面板高度 = 二者实测高度之和（高度守恒，IME 窗口总高不变）。
     * 随后按「权限 → 模型 → 加载 → 聆听」顺序进入初始状态。
     */
    fun open() {
        if (panel != null) return
        if (host.isFloatingMode()) {
            Toast.makeText(service, "悬浮键盘暂不支持语音输入", Toast.LENGTH_SHORT).show()
            return
        }
        val content = host.contentLayout() ?: return
        val keyboard = host.keyboardContainer() ?: return
        val candidates = host.candidatesContainer() ?: return
        host.onPanelWillOpen()

        // 先取实测高度再隐藏；未测量（极端时序）时回退固定高度
        val measured = keyboard.height + candidates.height
        val panelHeight = if (measured > 0) measured
            else (FALLBACK_PANEL_HEIGHT_DP * service.resources.displayMetrics.density).toInt()
        keyboard.visibility = View.GONE
        candidates.visibility = View.GONE

        val newPanel = VoicePanelView(service, SkinManager.getCurrentSkin(service), panelHost)
        content.addView(newPanel, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        panel = newPanel

        // 恢复上次的上屏策略偏好
        commitMode = VoiceModelManager.getCommitMode(service)
        newPanel.setMode(commitMode)

        when {
            !hasAudioPermission() -> newPanel.showState(VoicePanelView.State.NoPermission)
            else -> prepareAndListen(newPanel)
        }
    }

    /**
     * 关闭语音面板（幂等）。尾段文本交付策略：
     * 停止会话时引擎的尾段冲刷 onFinal 在主线程同步落入 buffer（见 [onMainThread]
     * 的同线程直执行分支），随后把全部已确认增量上屏——无论 AUTO/BUFFER 模式：
     * 面板被关（含 onFinishInputView/onTrimMemory 强制关闭）意味着会话终结，
     * 已确认文本宁可上屏也不静默丢弃。
     */
    fun close() {
        val current = panel ?: return
        // 无条件停止会话（幂等）：解码异常后 fsm 可能已回 IDLE 但采集线程仍在运行，
        // 不得依赖 fsm.isActive 守卫，否则面板关闭后麦克风仍被占用。
        // 先于 panel 置空执行，让尾段冲刷 onFinal 能同步落入 buffer 交付
        engine.stopSession()
        // 注销静默定时器：尾段 onFinal 可能刚重 arm，须在 stopSession 之后再清
        // （removeCallbacks 需要 view 引用）
        current.removeCallbacks(silenceTimeoutAction)
        silenceTimer = null
        // 尾段与历史确认段统一交付（AUTO 模式通常已在逐句 drain，此处只兜底残余）
        buffer.drainConfirmed().takeIf { it.isNotEmpty() }
            ?.let { host.commitVoiceTextToEditor(it) }
        buffer.reset()
        fsm.onEvent(VoiceSessionEvent.Reset)
        panel = null
        modelLoadJob?.cancel()
        modelLoadJob = null
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 会话编排 =====

    /** 模型就绪检查 + 异步加载 + 开启聆听。 */
    private fun prepareAndListen(view: VoicePanelView) {
        val spec = VoiceModelManager.getActiveSpec(service)
        if (!VoiceModelManager.isInstalled(service, spec)) {
            view.showState(VoicePanelView.State.NoModel)
            return
        }
        val modelDir = VoiceModelManager.modelDir(service, spec)
        // 必须比对「已加载目录」而非仅看 isModelLoaded：用户在设置页切换激活模型后，
        // 目标目录与已加载不一致则重新加载，否则引擎会继续用旧模型识别
        if (engine.isModelLoadedFor(modelDir)) {
            startListening(view)
            return
        }
        view.showState(VoicePanelView.State.LoadingModel)
        modelLoadJob = scope.launch {
            val error = withContext(Dispatchers.IO) { engine.loadModel(modelDir) }
            val current = panel ?: return@launch // 加载期间面板被关：放弃
            if (error != null) {
                current.showState(VoicePanelView.State.Error(error))
            } else {
                startListening(current)
            }
        }
    }

    /** 开启一轮识别会话（重置缓冲与状态机，注册引擎监听，挂静默超时）。 */
    private fun startListening(view: VoicePanelView) {
        buffer.reset()
        view.updatePreview("")
        fsm.onEvent(VoiceSessionEvent.Start)
        val error = engine.startSession(engineListener)
        if (error != null) {
            fsm.onEvent(VoiceSessionEvent.UserStop)
            view.showState(VoicePanelView.State.Error(error))
            return
        }
        view.showState(VoicePanelView.State.Listening)
        sessionDeadlineMs = SystemClock.uptimeMillis() + MAX_SESSION_MS
        armSilenceTimer(LISTENING_TIMEOUT_MS)
    }

    /** 引擎回调：来自解码线程（尾段来自 stopSession 调用线程），统一 post 回主线程。 */
    private val engineListener = object : SpeechRecognizerEngine.Listener {
        override fun onPartial(text: String) {
            onMainThread {
                val view = panel ?: return@onMainThread
                if (buffer.updatePartial(text)) {
                    view.updatePreview(buffer.preview())
                }
                fsm.onEvent(VoiceSessionEvent.SpeechDetected)
                armSilenceTimer(COOLDOWN_TIMEOUT_MS)
            }
        }

        override fun onFinal(text: String) {
            onMainThread {
                val view = panel ?: return@onMainThread
                if (!buffer.commitSegment(text)) return@onMainThread
                fsm.onEvent(VoiceSessionEvent.UtteranceEnd)
                if (commitMode == VoiceCommitMode.AUTO_COMMIT) {
                    buffer.drainConfirmed().takeIf { it.isNotEmpty() }
                        ?.let { host.commitVoiceTextToEditor(it) }
                }
                view.updatePreview(buffer.preview())
                armSilenceTimer(COOLDOWN_TIMEOUT_MS)
            }
        }

        override fun onError(message: String) {
            // 引擎异常时会话已终止，但识别流等会话资源须到 stopSession 才释放；
            // 引擎内部已在异常处停掉采集，此处补齐会话侧释放（幂等）
            engine.stopSession()
            onMainThread {
                val view = panel ?: return@onMainThread
                fsm.onEvent(VoiceSessionEvent.UserStop)
                cancelSilenceTimer()
                view.showState(VoicePanelView.State.Error(message))
            }
        }
    }

    /** 静默超时：投递给会话状态机，自动收尾则结束本轮；到达会话绝对时长
     *  上限时无条件强制收尾（防噪声驱动的无限会话）。 */
    private val silenceTimeoutAction = Runnable {
        val view = panel ?: return@Runnable
        fsm.onEvent(VoiceSessionEvent.SilenceTimeout)
        val hardTimeout = SystemClock.uptimeMillis() >= sessionDeadlineMs
        if (fsm.autoStopped || hardTimeout) {
            engine.stopSession() // 尾段冲刷 → onFinal → 按策略交付
            view.showState(VoicePanelView.State.Idle)
        }
    }

    private fun armSilenceTimer(timeoutMs: Long) {
        val view = panel ?: return
        cancelSilenceTimer()
        silenceTimer = silenceTimeoutAction
        view.postDelayed(silenceTimeoutAction, timeoutMs)
    }

    private fun cancelSilenceTimer() {
        silenceTimer?.let { panel?.removeCallbacks(it) }
        silenceTimer = null
    }

    /**
     * 引擎回调切回主线程（面板已关闭则丢弃）。
     *
     * 已在主线程时直接同步执行：stopSession 的尾段 onFinal 在调用线程投递，
     * close()/手动停止都发生在主线程，若也走 post 会在 panel 置空后才执行而被丢弃，
     * 导致尾段文本无声丢失；解码线程的回调仍按 post 切回。
     */
    private fun onMainThread(block: () -> Unit) {
        val view = panel ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            view.post(block)
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ===== 面板宿主能力（VoicePanelView.Host 实现）=====

    private val panelHost = object : VoicePanelView.Host {
        override fun onRequestClose() = close()

        override fun onStartSession() {
            val view = panel ?: return
            host.onPanelWillOpen()
            prepareAndListen(view)
        }

        override fun onStopSession() {
            val view = panel ?: return
            cancelSilenceTimer()
            if (fsm.isActive) {
                engine.stopSession() // 尾段冲刷 onFinal 在主线程同步交付
                fsm.onEvent(VoiceSessionEvent.UserStop)
            }
            view.showState(VoicePanelView.State.Idle)
        }

        override fun onSendBuffered() {
            val text = buffer.drainConfirmed()
            if (text.isEmpty()) {
                Toast.makeText(service, "暂无已确认的语音文本", Toast.LENGTH_SHORT).show()
                return
            }
            host.commitVoiceTextToEditor(text)
            panel?.let { view ->
                // 已发送的段保留在预览里仅作历史展示无意义，清空进入下一轮
                buffer.reset()
                view.updatePreview("")
                view.showState(VoicePanelView.State.Idle)
            }
        }

        override fun onToggleMode() {
            commitMode = if (commitMode == VoiceCommitMode.AUTO_COMMIT) {
                VoiceCommitMode.BUFFER_SEND
            } else {
                VoiceCommitMode.AUTO_COMMIT
            }
            VoiceModelManager.setCommitMode(service, commitMode)
            panel?.setMode(commitMode)
            // 切到逐句上屏时，把已缓冲未发送的段立刻上屏，避免文本悬置
            if (commitMode == VoiceCommitMode.AUTO_COMMIT) {
                buffer.drainConfirmed().takeIf { it.isNotEmpty() }
                    ?.let { host.commitVoiceTextToEditor(it) }
            }
        }

        override fun onRequestPermission() = host.openVoiceSettings(requestPermission = true)

        override fun onOpenModelSettings() = host.openVoiceSettings(requestPermission = false)

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
