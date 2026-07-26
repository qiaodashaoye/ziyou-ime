package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.ziyou.ime.ai.AiChatClient
import com.ziyou.ime.ai.AiConfig
import com.ziyou.ime.ai.MarkdownRenderer
import com.ziyou.ime.config.KeyboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AI 问答面板：内部结构为「宿主标题栏 + 对话气泡区（可滚动）+ 输入行」。
 *
 * 挂载在输入视图内容根容器顶部（编码区上方，见 [AiPanelCoordinator]），
 * 复用技能面板的输入路由纪律：面板打开期间 [aiCommitTarget] 接管键盘上屏，
 * 用户经正常拼音键盘打字，文本注入面板输入框而非宿主编辑器。
 *
 * 两种布局形态（由宿主经 [Host.onRequestKeyboardCollapsed] 编排）：
 * - 提问态：输入行 + 键盘可见，对话区收起为小窗预览（有历史时）；
 * - 答案态（点击搜索后）：键盘/候选区收回，对话区接管其空间独立滚动。
 *
 * AI 请求经 [AiChatClient] 在 IO 线程异步执行，面板持有独立协程作用域，
 * [release] 时取消，网络失败以错误气泡呈现。
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class AiPanelView(
    context: Context,
    private val theme: KeyboardTheme,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图并调用 [release]） */
        fun onRequestClose()

        /** 键盘收放：true=收回键盘让位给答案区，false=恢复键盘继续输入 */
        fun onRequestKeyboardCollapsed(collapsed: Boolean)

        /** 打开设置页（未配置 AI 服务时的引导入口） */
        fun onRequestOpenSettings()

        /** 将 AI 答案上屏到当前输入框（绕过面板输入路由，直达宿主编辑器） */
        fun onCommitAnswer(text: String)

        /** 将 AI 答案渲染为图片并发送到当前输入框（commitContent 富媒体提交） */
        fun onSendAnswerAsImage(content: CharSequence)

        /** 按键震动反馈 */
        fun performHaptic()
    }

    companion object {
        private const val TAG = "AiPanelView"
        /** 提问态下对话区的预览高度（dp），无历史消息时整体隐藏 */
        private const val CHAT_PREVIEW_HEIGHT_DP = 120f
        /** 气泡占面板宽度的最大比例 */
        private const val BUBBLE_MAX_WIDTH_RATIO = 0.78f
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 对话滚动区（答案态接管键盘空间，提问态小窗预览/隐藏） */
    private val chatScroll: ScrollView

    /** 对话气泡列表容器 */
    private val chatList: LinearLayout

    /** 输入内容展示（键盘上屏文本经输入路由注入，非 EditText） */
    private val inputDisplay: TextView

    /** 搜索（发送）按钮 */
    private val sendButton: TextView

    /** 输入行容器（输入框 + 搜索按钮）：提问后整行隐藏，面板重开才恢复 */
    private val inputRow: LinearLayout

    /** 加载指示行（请求进行中显示，结束后移除） */
    private var loadingRow: View? = null

    /** 输入缓冲：键盘经 CommitTarget 注入的文本 */
    private val inputBuffer = StringBuilder()

    /** 面板协程作用域（主线程），面板释放时整体取消 */
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前进行中的 AI 请求（同一时刻仅允许一个） */
    private var requestJob: Job? = null

    /** Markdown 渲染取色：代码底色/强调色/次要色均映射自当前键盘主题 */
    private val markdownPalette = MarkdownRenderer.Palette(
        codeBackground = theme.keyPressedBackground,
        accentColor = theme.candidateHighlightColor,
        secondaryColor = theme.preeditTextColor
    )

    /**
     * 输入锁定标志：提问一次后置为 true，隐藏输入行并拦截输入路由残留文本，
     * 防止同一会话连续多次提问；仅面板关闭重开（重建本视图）时自然复位。
     */
    private var inputLocked = false

    /**
     * 上屏目标：面板打开期间键盘文本经此注入输入框；
     * 无编码时的回车键路由为发送（与搜索按钮等价）。
     */
    val aiCommitTarget = object : InputLogicController.CommitTarget {
        override fun commit(text: CharSequence) = appendInput(text)

        override fun deleteBackward() = deleteInputBackward()

        override fun onEnter() = sendQuestion()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏 ──
        val titleView = TextView(context).apply {
            text = "AI 问答"
            textSize = 15f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            setOnClickListener {
                host.performHaptic()
                host.onRequestClose()
            }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.candidateBackground)
            // 左侧占位与右侧关闭钮等宽，保证标题视觉居中
            addView(View(context), LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
            addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(closeButton, LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))
        addView(View(context).apply { setBackgroundColor(theme.borderColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))

        // ── 对话气泡区（初始隐藏，首次提问后出现）──
        chatList = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        chatScroll = ScrollView(context).apply {
            isFillViewport = true
            visibility = GONE
            addView(chatList, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(chatScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0))

        // ── 输入行：输入框展示 + 搜索按钮 ──
        inputDisplay = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            maxLines = 1
            isHorizontalScrollBarEnabled = false
            movementMethod = ScrollingMovementMethod.getInstance()
            background = roundedBg(theme.keyBackground, 18f, theme.borderColor)
            // 点击输入框：恢复键盘继续编辑（答案态收回键盘后再次提问的入口）
            setOnClickListener { host.onRequestKeyboardCollapsed(false) }
        }
        sendButton = TextView(context).apply {
            text = "搜索"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.candidateBackground)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            background = roundedBg(theme.candidateHighlightColor, 18f, null)
            setOnClickListener {
                host.performHaptic()
                sendQuestion()
            }
        }
        inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            addView(inputDisplay, LayoutParams(0, dp(36f), 1f))
            addView(sendButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f)).apply {
                marginStart = dp(8f)
            })
        }
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refreshInputDisplay()
    }

    // ===== 输入路由 =====

    private fun appendInput(text: CharSequence) {
        if (inputLocked) return
        inputBuffer.append(text)
        refreshInputDisplay()
    }

    private fun deleteInputBackward() {
        if (inputLocked || inputBuffer.isEmpty()) return
        // 按码点回删，避免拆散表情等代理对字符
        val cutIndex = inputBuffer.offsetByCodePoints(inputBuffer.length, -1)
        inputBuffer.delete(cutIndex, inputBuffer.length)
        refreshInputDisplay()
    }

    /** 刷新输入框展示：空时显示提示文字，非空显示已输入内容。 */
    private fun refreshInputDisplay() {
        if (inputLocked) return
        if (inputBuffer.isEmpty()) {
            inputDisplay.text = "询问AI..."
            inputDisplay.setTextColor(theme.preeditTextColor and 0x00FFFFFF or 0x80000000.toInt())
        } else {
            inputDisplay.text = inputBuffer
            inputDisplay.setTextColor(theme.keyTextColor)
        }
    }

    // ===== 提问 / 应答 =====

    /** 发送当前输入的问题（搜索按钮 / 键盘回车共同入口）。 */
    private fun sendQuestion() {
        if (inputLocked) return
        val question = inputBuffer.toString().trim()
        if (question.isEmpty()) return
        if (requestJob?.isActive == true) return  // 上一次请求未结束，忽略

        inputBuffer.clear()
        refreshInputDisplay()
        addQuestionBubble(question)
        // 锁定输入：同一会话仅允许一次提问，整行隐藏输入框与搜索按钮
        lockInput()

        // 键盘自动收回，答案区接管键盘空间
        host.onRequestKeyboardCollapsed(true)

        // 未配置 AI 服务：以卡片引导去设置页，不发起网络请求
        if (!AiConfig.isConfigured(context)) {
            addAnswerBubble("尚未配置 AI 服务。请在「设置 → AI 问答」中填写 API Key 后使用。",
                isError = true, withSettingsEntry = true)
            return
        }

        showLoading()
        requestJob = panelScope.launch {
            val result = AiChatClient.ask(context.applicationContext, question)
            hideLoading()
            result.fold(
                onSuccess = { answer -> addAnswerBubble(answer, isError = false) },
                onFailure = { e ->
                    Log.w(TAG, "AI 请求失败: ${e.message}")
                    addAnswerBubble(e.message ?: "请求失败，请稍后重试", isError = true)
                }
            )
        }
    }

    /**
     * 锁定输入行：提问后整行隐藏输入框与搜索按钮（GONE，腾出的空间由
     * 答案区接管），防止同一会话连续多次提问；[inputLocked] 同时拦截输入路由
     * 残留的键盘文本与回车发送。复位仅发生在面板关闭重开（重建本视图）时。
     */
    private fun lockInput() {
        inputLocked = true
        inputRow.visibility = GONE
    }

    /** 加载指示行：旋转进度条 + 「正在思考…」。 */
    private fun showLoading() {
        if (loadingRow != null) return
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            addView(ProgressBar(context).apply {
                isIndeterminate = true
            }, LayoutParams(dp(18f), dp(18f)))
            addView(TextView(context).apply {
                text = "正在思考…"
                textSize = 13f
                setTextColor(theme.preeditTextColor)
                setPadding(dp(8f), 0, 0, 0)
            })
        }
        loadingRow = row
        chatList.addView(row)
        scrollToBottom()
    }

    private fun hideLoading() {
        loadingRow?.let { chatList.removeView(it) }
        loadingRow = null
    }

    /** 问题气泡：右对齐，强调色底。 */
    private fun addQuestionBubble(text: String) {
        chatList.addView(createBubble(text, alignEnd = true,
            bgColor = theme.candidateHighlightColor, textColor = theme.candidateBackground))
        chatScroll.visibility = VISIBLE
        scrollToBottom()
    }

    /**
     * 答案卡片：左对齐；[isError] 时以错误样式呈现（纯文本，不附操作按钮），
     * 成功答案经 [MarkdownRenderer] 解析为富文本展示（粗体/列表/代码块等），
     * 右侧附「发送」（上屏解析后纯文本）与「发图」（富文本渲染为图片后
     * commitContent 发送）两个按钮纵向堆叠；[withSettingsEntry] 时点击卡片跳转设置页。
     */
    private fun addAnswerBubble(text: String, isError: Boolean, withSettingsEntry: Boolean = false) {
        // 成功答案按 Markdown 渲染；错误提示为本地文案，保持纯文本
        val content: CharSequence = if (isError) text else MarkdownRenderer.render(text, markdownPalette)
        val bubble = createBubble(content, alignEnd = false,
            bgColor = theme.keyBackground,
            textColor = if (isError) 0xFFD32F2F.toInt() else theme.keyTextColor)
        if (withSettingsEntry) {
            bubble.getChildAt(0).setOnClickListener { host.onRequestOpenSettings() }
        }
        // 成功答案右侧附操作列：「发送」上屏解析后纯文本（Markdown 标记已剥离，
        // 列表已转 •）；「发图」把富文本渲染为主题卡片图发送，两者共用同一样式
        if (!isError) {
            val actions = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(createAnswerActionButton("发送") { host.onCommitAnswer(content.toString()) },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(createAnswerActionButton("发图") { host.onSendAnswerAsImage(content) },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6f)
                    })
            }
            bubble.addView(actions, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f)
            })
        }
        chatList.addView(bubble)
        chatScroll.visibility = VISIBLE
        scrollToBottom()
    }

    /** AI 答案气泡右侧的操作按钮（发送/发图，样式与输入行搜索按钮一致）。 */
    private fun createAnswerActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.candidateBackground)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = roundedBg(theme.candidateHighlightColor, 14f, null)
            setOnClickListener {
                host.performHaptic()
                onClick()
            }
        }
    }

    /** 构建单条气泡行：外层控制左右对齐，内层圆角 TextView 承载内容
     *  （[content] 支持 Spanned 富文本，Markdown 答案经解析后传入）。 */
    private fun createBubble(content: CharSequence, alignEnd: Boolean, bgColor: Int, textColor: Int): LinearLayout {
        val bubbleText = TextView(context).apply {
            text = content
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(false)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = roundedBg(bgColor, 12f, if (alignEnd) null else theme.borderColor)
            // 气泡宽度上限：面板实际宽度比例（未布局时回退屏幕宽度），适配不同屏幕尺寸
            val base = if (width > 0) width else resources.displayMetrics.widthPixels
            maxWidth = (base * BUBBLE_MAX_WIDTH_RATIO).toInt()
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = if (alignEnd) Gravity.END else (Gravity.START or Gravity.CENTER_VERTICAL)
            setPadding(0, dp(3f), 0, dp(3f))
            addView(bubbleText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 圆角背景（可选描边），与键盘主题色一致。 */
    private fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1f), it) }
        }
    }

    // ===== 布局形态（由协调器在键盘收放后回调）=====

    /**
     * 同步内部对话区的布局形态：
     * - 答案态（键盘已收回）：对话区 weight=1 接管面板剩余空间；
     * - 提问态（键盘可见）：有历史消息时保留小窗预览，否则隐藏。
     */
    fun applyAnswerMode(answerMode: Boolean) {
        val params = chatScroll.layoutParams as LayoutParams
        if (answerMode) {
            chatScroll.visibility = VISIBLE
            params.height = 0
            params.weight = 1f
        } else {
            params.weight = 0f
            if (chatList.childCount > 0) {
                chatScroll.visibility = VISIBLE
                params.height = dp(CHAT_PREVIEW_HEIGHT_DP)
            } else {
                chatScroll.visibility = GONE
                params.height = 0
            }
        }
        chatScroll.layoutParams = params
        scrollToBottom()
    }

    /** 面板移除前必须调用：取消进行中的 AI 请求与协程作用域。 */
    fun release() {
        requestJob?.cancel()
        requestJob = null
        panelScope.cancel()
    }
}
