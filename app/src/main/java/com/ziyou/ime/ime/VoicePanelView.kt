package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ziyou.ime.skin.SkinTheme
import com.ziyou.ime.voice.VoiceModelManager.VoiceCommitMode

/**
 * 语音输入面板：内部结构为「标题栏 + 预览区 + 操作区」。
 *
 * 挂载在输入视图内容根容器顶部并立即收起键盘/候选区（见 [VoicePanelCoordinator]），
 * 与粘贴板/涂鸦面板同一高度守恒策略。面板无文字输入需求，**不接管 commitTarget**，
 * 识别文本经宿主走 commitDirectToEditor 直达输入框，对 Rime 与输入链路零侵入。
 *
 * 视图保持哑元：全部状态由协调器经 [showState] / [updatePreview] / [setMode] 驱动。
 * 配色全部映射自当前 [SkinTheme]，与 [ClipboardPanelView] 同一视觉语言；
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class VoicePanelView(
    context: Context,
    private val theme: SkinTheme,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责停止会话并移除视图） */
        fun onRequestClose()

        /** 请求开始一轮语音会话（「开始说」/「继续说」） */
        fun onStartSession()

        /** 请求手动停止识别（「停止」） */
        fun onStopSession()

        /** 缓冲模式下请求发送已确认文本 */
        fun onSendBuffered()

        /** 切换上屏策略（自动上屏 ↔ 缓冲发送） */
        fun onToggleMode()

        /** 跳转设置页：授予录音权限 */
        fun onRequestPermission()

        /** 跳转设置页：下载/管理语音模型 */
        fun onOpenModelSettings()

        /** 按键震动反馈 */
        fun performHaptic()
    }

    /** 面板状态（协调器驱动）。 */
    sealed class State {
        /** 模型加载中 */
        data object LoadingModel : State()

        /** 录音权限未授予 */
        data object NoPermission : State()

        /** 语音模型未下载 */
        data object NoModel : State()

        /** 就绪（未开始或一轮结束，可「开始说/继续说」） */
        data object Idle : State()

        /** 正在聆听识别 */
        data object Listening : State()

        /** 会话异常 */
        data class Error(val message: String) : State()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 状态提示行（标题栏下方小字） */
    private val statusView: TextView

    /** 预览文本（已确认 + partial 拼接，由协调器经 buffer.preview 提供） */
    private val previewView: TextView

    /** 预览滚动容器（文本增长后自动滚到底部） */
    private val previewScroll: ScrollView

    /** 模式切换按钮（文案随 [VoiceCommitMode] 变化） */
    private val modeButton: TextView

    /** 主操作按钮（开始说 / 停止 / 继续说，随状态切换） */
    private val primaryButton: TextView

    /** 发送按钮（缓冲模式可见） */
    private val sendButton: TextView

    /** 辅助操作按钮（去授权 / 下载模型，随状态显隐） */
    private val auxButton: TextView

    private var currentState: State = State.Idle

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏：标题 + 模式切换 + 关闭 ✕ ──
        val titleView = TextView(context).apply {
            text = "语音输入"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER_VERTICAL
        }
        modeButton = createToolButton("逐句上屏") { host.onToggleMode() }
        val closeButton = createToolButton("✕") { host.onRequestClose() }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
            addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // 弹性占位：标题靠左，操作钮靠右
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(modeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
                marginStart = dp(6f)
            })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 状态提示行 ──
        statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(theme.keyTextColor)
            alpha = 0.6f
            setPadding(dp(14f), 0, dp(14f), dp(4f))
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 预览区：ScrollView 接管剩余空间，文本贴底展示 ──
        previewView = TextView(context).apply {
            textSize = 15f
            setTextColor(theme.keyTextColor)
            setLineSpacing(dp(3f).toFloat(), 1f)
            background = roundedBg(theme.keyBackground, 10f, theme.borderColor)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        previewScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setPadding(dp(10f), 0, dp(10f), dp(6f))
            addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(previewScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 操作区：辅助 + 主操作 + 发送 ──
        auxButton = TextView(context).apply {
            textSize = 13f
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            setPadding(dp(14f), 0, dp(14f), 0)
            background = roundedBg(theme.keyBackground, 10f, theme.candidateHighlightColor)
            visibility = GONE
        }
        primaryButton = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            background = roundedBg(theme.keyPressedBackground, 10f, theme.borderColor)
        }
        sendButton = TextView(context).apply {
            text = "发送"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            background = roundedBg(theme.keyBackground, 10f, theme.candidateHighlightColor)
            visibility = GONE
            setOnClickListener {
                host.performHaptic()
                host.onSendBuffered()
            }
        }
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
            addView(auxButton, LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(8f) })
            addView(primaryButton, LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(8f) })
            addView(sendButton, LayoutParams(0, dp(44f), 1f))
        }
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ===== 协调器驱动入口（均主线程） =====

    /** 切换面板状态，刷新提示行与按钮组。 */
    fun showState(state: State) {
        currentState = state
        when (state) {
            State.LoadingModel -> {
                statusView.text = "正在加载语音模型…"
                setButtons(primary = null, aux = null, sendVisible = false)
            }

            State.NoPermission -> {
                statusView.text = "语音输入需要录音权限（仅本地识别，不上传）"
                setButtons(primary = null, aux = "去授权" to { host.onRequestPermission() }, sendVisible = false)
            }

            State.NoModel -> {
                statusView.text = "尚未下载语音模型（离线识别，约 160MB）"
                setButtons(primary = null, aux = "下载模型" to { host.onOpenModelSettings() }, sendVisible = false)
            }

            State.Idle -> {
                statusView.text = if (previewView.text.isEmpty()) "点击「开始说」，边说边出字"
                    else "本轮已结束，可继续说或关闭"
                val hasText = previewView.text.isNotEmpty()
                setButtons(
                    primary = (if (hasText) "继续说" else "开始说") to { host.onStartSession() },
                    aux = null,
                    sendVisible = hasText && currentMode == VoiceCommitMode.BUFFER_SEND
                )
            }

            State.Listening -> {
                statusView.text = "正在聆听… 停顿后自动断句"
                setButtons(primary = "停止" to { host.onStopSession() }, aux = null, sendVisible = false)
            }

            is State.Error -> {
                statusView.text = "识别异常：${state.message}"
                setButtons(primary = "重试" to { host.onStartSession() }, aux = null, sendVisible = false)
            }
        }
    }

    /** 刷新预览文本并滚动到底部（文本由协调器经 VoiceUtteranceBuffer.preview 提供）。 */
    fun updatePreview(text: String) {
        previewView.text = text
        previewScroll.post { previewScroll.fullScroll(FOCUS_DOWN) }
        // 空闲态下文本出现/清空会影响主按钮文案与发送钮可见性
        if (currentState is State.Idle) showState(State.Idle)
    }

    private var currentMode: VoiceCommitMode = VoiceCommitMode.AUTO_COMMIT

    /** 设置上屏策略（刷新模式按钮文案；空闲态同步发送钮可见性）。 */
    fun setMode(mode: VoiceCommitMode) {
        currentMode = mode
        modeButton.text = if (mode == VoiceCommitMode.AUTO_COMMIT) "逐句上屏" else "攒句发送"
        if (currentState is State.Idle) showState(State.Idle)
    }

    // ===== 内部构件 =====

    /** 统一刷新按钮组：primary/aux 传 null 表示隐藏（aux 为 文案×点击 对）。 */
    private fun setButtons(
        primary: Pair<String, () -> Unit>?,
        aux: Pair<String, () -> Unit>?,
        sendVisible: Boolean,
    ) {
        if (primary != null) {
            primaryButton.visibility = VISIBLE
            primaryButton.text = primary.first
            primaryButton.setOnClickListener {
                host.performHaptic()
                primary.second()
            }
        } else {
            primaryButton.visibility = GONE
            primaryButton.setOnClickListener(null)
        }
        if (aux != null) {
            auxButton.visibility = VISIBLE
            auxButton.text = aux.first
            auxButton.setOnClickListener {
                host.performHaptic()
                aux.second()
            }
        } else {
            auxButton.visibility = GONE
            auxButton.setOnClickListener(null)
        }
        sendButton.visibility = if (sendVisible) VISIBLE else GONE
    }

    /** 工具按钮（模式切换/关闭共用样式） */
    private fun createToolButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            setOnClickListener {
                host.performHaptic()
                onClick()
            }
        }
    }

    /** 圆角背景（可选描边），与键盘主题色一致。 */
    private fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1f), it) }
        }
    }
}
