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
import com.ziyou.ime.ai.AiPersona
import com.ziyou.ime.ai.ChatMessage
import com.ziyou.ime.ai.MarkdownRenderer
import com.ziyou.ime.ai.PersonaRepository
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
        /** 多轮对话历史上限（条数，包含 user + assistant，FIFO 淘汰） */
        private const val MAX_HISTORY_SIZE = 10
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

    /** 当前人设（面板打开时从仓库读取，切换时立即更新） */
    private var currentPersona: AiPersona = PersonaRepository.getCurrentPersona(context)

    /** 多轮对话历史（FIFO，上限 [MAX_HISTORY_SIZE]） */
    private val chatHistory: MutableList<ChatMessage> = mutableListOf()

    /** 标题栏人设标签（点击弹出切换浮层） */
    private lateinit var personaLabel: TextView

    /** 人设选择浮层（初始 GONE，点击 personaLabel 展开） */
    private lateinit var personaOverlay: LinearLayout

    /** Markdown 渲染取色：代码底色/强调色/次要色均映射自当前键盘主题 */
    private val markdownPalette = MarkdownRenderer.Palette(
        codeBackground = theme.keyPressedBackground,
        accentColor = theme.candidateHighlightColor,
        secondaryColor = theme.preeditTextColor
    )

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
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener {
                host.performHaptic()
                host.onRequestClose()
            }
        }
        // 人设标签（左侧，点击展开切换浮层）
        personaLabel = TextView(context).apply {
            text = "· ${currentPersona.name}"
            textSize = 12f
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(4f), 0)
            maxLines = 1
            isClickable = true
            setOnClickListener {
                host.performHaptic()
                togglePersonaOverlay()
            }
        }
        // 新对话按钮（右侧，清空历史重新开始）
        val newChatButton = TextView(context).apply {
            text = "新对话"
            textSize = 12f
            setTextColor(theme.preeditTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
            setOnClickListener {
                host.performHaptic()
                startNewConversation()
            }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.candidateBackground)
            addView(personaLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(newChatButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))
        addView(View(context).apply { setBackgroundColor(theme.borderColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))
        // 人设选择浮层（初始 GONE，点击 personaLabel 展开）
        personaOverlay = buildPersonaOverlay()
        addView(personaOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        inputBuffer.append(text)
        refreshInputDisplay()
    }

    private fun deleteInputBackward() {
        if (inputBuffer.isEmpty()) return
        // 按码点回删，避免拆散表情等代理对字符
        val cutIndex = inputBuffer.offsetByCodePoints(inputBuffer.length, -1)
        inputBuffer.delete(cutIndex, inputBuffer.length)
        refreshInputDisplay()
    }

    /** 刷新输入框展示：空时显示提示文字，非空显示已输入内容。 */
    private fun refreshInputDisplay() {
        if (inputBuffer.isEmpty()) {
            inputDisplay.text = "询问AI..."
            inputDisplay.setTextColor(theme.preeditTextColor and 0x00FFFFFF or 0x80000000.toInt())
        } else {
            inputDisplay.text = inputBuffer
            inputDisplay.setTextColor(theme.keyTextColor)
        }
    }

    // ===== 提问 / 应答 =====

    /**
     * 发送当前输入的问题（搜索按钮 / 键盘回车共同入口）。
     *
     * 支持多轮追问：上一次请求未完成时取消旧请求再发起新的；
     * 对话历史经 [chatHistory] 维护，上限 [MAX_HISTORY_SIZE]。
     */
    private fun sendQuestion() {
        val question = inputBuffer.toString().trim()
        if (question.isEmpty()) return

        // 取消上一次未完成请求（而非忽略新请求），支持连续追问
        if (requestJob?.isActive == true) {
            requestJob?.cancel()
        }

        inputBuffer.clear()
        refreshInputDisplay()
        addQuestionBubble(question)

        // 追加用户问题到历史
        chatHistory.add(ChatMessage("user", question))
        trimHistory()

        // 键盘自动收回，答案区接管键盘空间
        host.onRequestKeyboardCollapsed(true)

        // 未配置 AI 服务：以卡片引导去设置页，不发起网络请求
        if (!AiConfig.isConfigured(context)) {
            addAnswerBubble("尚未配置 AI 服务。请在「设置 → AI 问答」中填写 API Key 后使用。",
                isError = true, withSettingsEntry = true)
            return
        }

        // 拼接基础格式约束 + 当前人设提示词
        val fullSystemPrompt = AiChatClient.BASE_SYSTEM_PROMPT + "\n\n" + currentPersona.systemPrompt
        showLoading()
        requestJob = panelScope.launch {
            val result = AiChatClient.ask(
                context.applicationContext,
                question,
                fullSystemPrompt,
                chatHistory.dropLast(1)  // 刚追加的 user 由 buildRequestBody 末尾加入，历史仅传前 N-1 条
            )
            hideLoading()
            result.fold(
                onSuccess = { answer ->
                    chatHistory.add(ChatMessage("assistant", answer))
                    trimHistory()
                    addAnswerBubble(answer, isError = false)
                },
                onFailure = { e ->
                    Log.w(TAG, "AI 请求失败: ${e.message}")
                    // 失败时回滚刚追加的 user 消息，避免下次重试时重复
                    if (chatHistory.isNotEmpty() && chatHistory.last().role == "user"
                        && chatHistory.last().content == question) {
                        chatHistory.removeAt(chatHistory.lastIndex)
                    }
                    addAnswerBubble(e.message ?: "请求失败，请稍后重试", isError = true)
                }
            )
        }
    }

    /** FIFO 淘汰：超过 [MAX_HISTORY_SIZE] 时移除最早的消息（保持偶数条：user/assistant 成对）。 */
    private fun trimHistory() {
        while (chatHistory.size > MAX_HISTORY_SIZE) {
            chatHistory.removeAt(0)
        }
    }

    /** 开启新对话：清空气泡与历史，恢复输入行与键盘。 */
    private fun startNewConversation() {
        requestJob?.cancel()
        requestJob = null
        chatHistory.clear()
        chatList.removeAllViews()
        chatScroll.visibility = GONE
        // 恢复键盘与输入行
        host.onRequestKeyboardCollapsed(false)
        inputRow.visibility = VISIBLE
        inputBuffer.clear()
        refreshInputDisplay()
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

    // ===== 人设切换浮层 =====

    /**
     * 构建人设选择浮层：纵向列表展示全部人设（内置 + 自定义），
     * 当前选中项带勾选标记，点击切换人设后自动收起浮层。
     */
    private fun buildPersonaOverlay(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(theme.candidateBackground)
            visibility = GONE
            // 阻断触摸穿透
            isClickable = true
        }
        refreshPersonaOverlay(container)
        return container
    }

    /**
     * 刷新浮层内容（人设列表可能因设置页增删而变化）。
     *
     * 每个条目单击切换人设，长按展开/收起详情预览（系统提示词摘要），
     * 避免在 IME 上下文中弹出 AlertDialog 的窗口权限问题。
     */
    private fun refreshPersonaOverlay(container: LinearLayout = personaOverlay) {
        container.removeAllViews()
        val personas = PersonaRepository.getAllPersonas(context)
        val currentId = currentPersona.id
        for (persona in personas) {
            val isCurrent = persona.id == currentId

            // 详情区（初始 GONE，长按展开）
            val detailView = TextView(context).apply {
                val promptPreview = if (persona.systemPrompt.length > 80)
                    persona.systemPrompt.take(80) + "…"
                else persona.systemPrompt
                text = "提示词：$promptPreview"
                textSize = 11f
                setTextColor(theme.preeditTextColor)
                setPadding(dp(24f), dp(2f), dp(16f), dp(8f))
                maxLines = 3
                visibility = GONE
            }

            // 主条目行
            val item = TextView(context).apply {
                val check = if (isCurrent) "✓ " else "    "
                val badge = if (persona.isBuiltin) "[内置] " else ""
                val desc = if (persona.description.isNotBlank()) "  ${persona.description}" else ""
                text = "$check${persona.name} $badge$desc"
                textSize = 14f
                setTextColor(if (isCurrent) theme.candidateHighlightColor else theme.keyTextColor)
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                setBackgroundResource(android.R.drawable.list_selector_background)
                // 单击：切换人设
                setOnClickListener {
                    host.performHaptic()
                    switchToPersona(persona)
                }
                // 长按：展开/收起详情预览
                setOnLongClickListener {
                    host.performHaptic()
                    detailView.visibility = if (detailView.visibility == VISIBLE) GONE else VISIBLE
                    true
                }
            }

            val itemContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(item, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(detailView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            container.addView(itemContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            // 分割线
            container.addView(View(context).apply {
                setBackgroundColor(theme.borderColor)
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(0.5f)))
        }
    }

    /** 展开 / 收起人设选择浮层。 */
    private fun togglePersonaOverlay() {
        if (personaOverlay.visibility == VISIBLE) {
            personaOverlay.visibility = GONE
        } else {
            // 每次展开时刷新列表（设置页可能已修改自定义人设）
            refreshPersonaOverlay()
            personaOverlay.visibility = VISIBLE
        }
    }

    /**
     * 切换人设：更新当前人设、刷新标签、清空气泡与历史（避免风格冲突），
     * 然后收起浮层。
     */
    private fun switchToPersona(persona: AiPersona) {
        if (persona.id == currentPersona.id) {
            personaOverlay.visibility = GONE
            return
        }
        currentPersona = persona
        PersonaRepository.setCurrentPersona(context, persona.id)
        personaLabel.text = "· ${persona.name}"
        // 切换人设后清空当前会话（风格冲突，历史上下文不应混用）
        startNewConversation()
        personaOverlay.visibility = GONE
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
