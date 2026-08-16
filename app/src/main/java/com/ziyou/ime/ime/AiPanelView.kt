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
import com.ziyou.ime.ai.AiChatOrchestrator
import com.ziyou.ime.ai.AiConfig
import com.ziyou.ime.ai.AiPanelMode
import com.ziyou.ime.ai.AiPersona
import com.ziyou.ime.ai.ChatMessage
import com.ziyou.ime.ai.MarkdownRenderer
import com.ziyou.ime.ai.PersonaRepository
import com.ziyou.ime.ai.knowledge.AiMemoryStore
import com.ziyou.ime.ai.knowledge.AiUsageStats
import com.ziyou.ime.core.ai.PolishVariant
import com.ziyou.ime.skin.SkinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AI 面板：按构造参数 [mode] 固定为问答或人设润色形态（工具栏两个独立
 * 按钮分别触发，见 [AiPanelCoordinator]），内部结构为「标题栏（人设 chip）
 * + 内容区（可滚动）+ 输入行」。
 *
 * 挂载在输入视图内容根容器顶部（编码区上方，见 [AiPanelCoordinator]），
 * 复用技能面板的输入路由纪律：面板打开期间 [aiCommitTarget] 接管键盘上屏，
 * 用户经正常拼音键盘打字，文本注入面板输入框而非宿主编辑器——润色模式
 * 正是借此实现「草稿不直接上屏」：草稿经 [AiChatOrchestrator.polish] 按人设
 * 改写为候选，用户点候选「上屏」才经宿主提交。
 *
 * 两种布局形态（由宿主经 [Host.onRequestKeyboardCollapsed] 编排）：
 * - 提问态：输入行 + 键盘可见，内容区收起为小窗预览（有历史时）；
 * - 答案态（发送后）：键盘/候选区收回，内容区接管其空间独立滚动。
 *
 * 业务编排在 [AiChatOrchestrator]（检索/prompt/请求），面板只留 UI；
 * AI 请求在 IO 线程异步执行，面板持有独立协程作用域，[release] 时取消，
 * 网络失败以错误气泡呈现。面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class AiPanelView(
    context: Context,
    private val theme: SkinTheme,
    private val host: Host,
    /** 工作模式：构造时固定，面板内不提供切换（双入口各自触发） */
    val mode: AiPanelMode
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图并调用 [release]） */
        fun onRequestClose()

        /** 键盘收放：true=收回键盘让位给答案区，false=恢复键盘继续输入 */
        fun onRequestKeyboardCollapsed(collapsed: Boolean)

        /** 打开设置页（未配置 AI 服务时的引导入口） */
        fun onRequestOpenSettings()

        /** 打开人设管理页（新建/编辑/删除人设及其知识库绑定） */
        fun onRequestOpenPersonaManager()

        /** 将 AI 答案上屏到当前输入框（绕过面板输入路由，直达宿主编辑器） */
        fun onCommitAnswer(text: String)

        /** 将 AI 答案渲染为图片后提交：宿主按最新图片能力路由到
         *  commitContent 直发输入框或保存到系统相册 */
        fun onSendAnswerAsImage(content: CharSequence)

        /** 当前编辑器是否可直接接收图片（答案操作按钮呈现「发图」或「存图」） */
        fun editorAcceptsImage(): Boolean

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
        /** 迭代润色历史上限（条数，FIFO；携带上轮候选助模型收敛去重） */
        private const val MAX_POLISH_HISTORY = 6
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

    /** 当前进行中的 AI 请求（同一时刻仅允许一个，问答/润色共用） */
    private var requestJob: Job? = null

    /** 当前人设（面板打开时从仓库读取，切换时立即更新，两面板共享） */
    private var currentPersona: AiPersona = PersonaRepository.getCurrentPersona(context)

    /** 问答模式多轮对话历史（FIFO，上限 [MAX_HISTORY_SIZE]） */
    private val chatHistory: MutableList<ChatMessage> = mutableListOf()

    /** 润色模式迭代历史（user=原文/调整要求，assistant=上轮候选）。
     *  隐私纪律：仅内存态，不落盘，[release] 即销毁 */
    private val polishHistory: MutableList<ChatMessage> = mutableListOf()

    /** 当前润色轮的草稿原文（重新润色时复用；上屏/新对话后复位） */
    private var lastPolishDraft: String? = null

    /** 当前是否存在润色候选结果（决定输入框双态：草稿 vs 调整要求） */
    private var hasPolishResult: Boolean = false

    /** 标题栏人设标签（点击弹出切换浮层；绑定时附 📚N 徽标） */
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
     * 无编码时的回车键按模式路由：问答=发送提问，润色=发起/重新润色。
     */
    val aiCommitTarget = object : InputLogicController.CommitTarget {
        override fun commit(text: CharSequence) = appendInput(text)

        override fun deleteBackward() = deleteInputBackward()

        override fun onEnter() = sendCurrent()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏 ──
        val titleView = TextView(context).apply {
            text = if (mode == AiPanelMode.ASK) "AI 问答" else "人设润色"
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
        // 人设标签（点击展开切换浮层；绑定知识库时附 📚N 徽标）
        personaLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(4f), 0)
            maxLines = 1
            isClickable = true
            setOnClickListener {
                host.performHaptic()
                togglePersonaOverlay()
            }
        }
        refreshPersonaLabel()
        // 新对话按钮（右侧，按当前面板模式清空对应会话）
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
                sendCurrent()
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
        refreshSendButton()
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

    /** 刷新输入框展示：空时按模式/状态显示提示文字，非空显示已输入内容。 */
    private fun refreshInputDisplay() {
        if (inputBuffer.isEmpty()) {
            inputDisplay.text = when {
                mode == AiPanelMode.ASK -> "询问AI..."
                hasPolishResult -> "输入调整要求（可选）..."
                else -> "输入要润色的文字..."
            }
            inputDisplay.setTextColor(theme.preeditTextColor and 0x00FFFFFF or 0x80000000.toInt())
        } else {
            inputDisplay.text = inputBuffer
            inputDisplay.setTextColor(theme.keyTextColor)
        }
    }

    // ===== 发送（按模式路由） =====

    /** 统一发送入口（搜索按钮 / 键盘回车）：问答=提问，润色=发起/重新润色。 */
    private fun sendCurrent() {
        when (mode) {
            AiPanelMode.ASK -> sendQuestion()
            AiPanelMode.POLISH -> sendPolish()
        }
    }

    /**
     * 问答模式：发送当前输入的问题。
     *
     * 支持多轮追问：上一次请求未完成时取消旧请求再发起新的；
     * 检索/prompt/请求编排在 [AiChatOrchestrator.ask]，面板只渲染结果。
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

        showLoading()
        val personaSnapshot = currentPersona
        requestJob = panelScope.launch {
            AiChatOrchestrator.ask(context, personaSnapshot, question, chatHistory)
                .fold(
                    onSuccess = { outcome ->
                        hideLoading()
                        chatHistory.add(ChatMessage("assistant", outcome.answer))
                        trimHistory()
                        addAnswerBubble(outcome.answer, isError = false,
                            sources = outcome.chunks.map { it.sourceName })
                    },
                    onFailure = { e ->
                        hideLoading()
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

    /**
     * 润色模式：草稿经人设润色产出候选（首轮），或携带调整要求重新润色。
     *
     * 输入框双态：无候选时内容为草稿；有候选时内容为调整要求（空则纯重生成）。
     * 草稿不直接上屏，候选「上屏」经用户显式点击才提交宿主编辑器。
     */
    private fun sendPolish() {
        val input = inputBuffer.toString().trim()
        // 双态取值：有候选时草稿复用上轮，输入视为调整要求
        val draft = if (hasPolishResult) lastPolishDraft else input
        val feedback = if (hasPolishResult) input.takeIf { it.isNotEmpty() } else null
        if (draft.isNullOrEmpty()) return

        if (requestJob?.isActive == true) {
            requestJob?.cancel()
        }
        inputBuffer.clear()
        refreshInputDisplay()

        // 首轮展示草稿原文气泡；重润时仅调整要求单独成泡（草稿不重复刷屏）
        if (!hasPolishResult) addQuestionBubble(draft)
        feedback?.let { addQuestionBubble("调整要求：$it") }

        host.onRequestKeyboardCollapsed(true)

        if (!AiConfig.isConfigured(context)) {
            addAnswerBubble("尚未配置 AI 服务。请在「设置 → AI 问答」中填写 API Key 后使用。",
                isError = true, withSettingsEntry = true)
            return
        }

        // 本轮 user 先入历史（orchestrator 透传给 AiChatClient 末尾追加，
        // 与问答路径同一约定：传入时 dropLast）
        val userContent = if (feedback.isNullOrBlank()) draft
        else "原文：$draft\n调整要求：$feedback"
        polishHistory.add(ChatMessage("user", userContent))
        trimPolishHistory()

        showLoading()
        val personaSnapshot = currentPersona
        requestJob = panelScope.launch {
            val outcome = AiChatOrchestrator.polish(
                context, personaSnapshot, draft, feedback, polishHistory.dropLast(1))
            hideLoading()
            if (outcome.error != null || outcome.variants.isEmpty()) {
                // 回滚本轮 user，避免重试时历史重复
                if (polishHistory.isNotEmpty() && polishHistory.last().role == "user"
                    && polishHistory.last().content == userContent) {
                    polishHistory.removeAt(polishHistory.lastIndex)
                }
                addAnswerBubble(outcome.error ?: "润色结果解析失败，请重试", isError = true)
                return@launch
            }
            lastPolishDraft = draft
            hasPolishResult = true
            // 上轮候选入历史：模型可感知已产出版本，重润时收敛去重
            polishHistory.add(ChatMessage("assistant",
                outcome.variants.joinToString("\n") { it.text }))
            trimPolishHistory()
            renderPolishResult(outcome.variants, outcome.sources)
            refreshInputDisplay()
            refreshSendButton()
        }
    }

    /** 润色候选渲染：每候选一张卡片（文本 + 风格说明）附「上屏」按钮。 */
    private fun renderPolishResult(variants: List<PolishVariant>, sources: List<String>) {
        for (variant in variants) {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = roundedBg(theme.keyBackground, 12f, theme.borderColor)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                addView(TextView(context).apply {
                    text = variant.text
                    textSize = 14f
                    setTextColor(theme.keyTextColor)
                    setLineSpacing(0f, 1.15f)
                })
                if (variant.note.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = variant.note
                        textSize = 11f
                        setTextColor(theme.preeditTextColor)
                        setPadding(0, dp(2f), 0, 0)
                    })
                }
            }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3f), 0, dp(3f))
                addView(card, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(createAnswerActionButton("上屏") { commitPolishVariant(variant.text) },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(6f)
                    })
            }
            chatList.addView(row)
        }
        // 风格参照来源行（与问答引用行同样式；编号与 prompt 内一致）
        if (sources.isNotEmpty()) {
            val label = sources.mapIndexed { i, name -> "[${i + 1}]$name" }
                .distinct()
                .joinToString(" ")
            chatList.addView(TextView(context).apply {
                this.text = "风格参照: $label"
                textSize = 11f
                setTextColor(theme.preeditTextColor)
                setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                maxLines = 2
            })
        }
        chatScroll.visibility = VISIBLE
        scrollToBottom()
    }

    /**
     * 润色候选上屏：直达宿主编辑器，随后复位本轮候选与输入框并恢复键盘，
     * 便于连续润色下一句（polishHistory 保留一轮供上下文衔接）。
     */
    private fun commitPolishVariant(text: String) {
        host.performHaptic()
        host.onCommitAnswer(text)
        hasPolishResult = false
        lastPolishDraft = null
        chatList.removeAllViews()
        chatScroll.visibility = GONE
        inputBuffer.clear()
        refreshInputDisplay()
        refreshSendButton()
        host.onRequestKeyboardCollapsed(false)
    }

    /** 润色历史 FIFO 淘汰（保持偶数条：user/assistant 成对）。 */
    private fun trimPolishHistory() {
        while (polishHistory.size > MAX_POLISH_HISTORY) {
            polishHistory.removeAt(0)
        }
    }

    /** FIFO 淘汰：超过 [MAX_HISTORY_SIZE] 时移除最早的消息（保持偶数条：user/assistant 成对）。 */
    private fun trimHistory() {
        while (chatHistory.size > MAX_HISTORY_SIZE) {
            chatHistory.removeAt(0)
        }
    }

    /**
     * 开启新对话：仅清空当前模式的会话。
     * - 问答模式：持久化当前会话记忆后清空气泡与历史；
     * - 润色模式：仅清候选与内存历史（润色内容不落盘，隐私纪律）。
     */
    private fun startNewConversation() {
        requestJob?.cancel()
        requestJob = null
        if (mode == AiPanelMode.ASK) {
            persistConversationMemory()
            chatHistory.clear()
        } else {
            polishHistory.clear()
            lastPolishDraft = null
            hasPolishResult = false
        }
        resetConversationUi()
    }

    /** 会话清空后的公共视图复位：移除气泡、恢复键盘与输入行。 */
    private fun resetConversationUi() {
        chatList.removeAllViews()
        chatScroll.visibility = GONE
        host.onRequestKeyboardCollapsed(false)
        inputRow.visibility = VISIBLE
        inputBuffer.clear()
        refreshInputDisplay()
        refreshSendButton()
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
     * commitContent 发送）两个按钮纵向堆叠；[withSettingsEntry] 时点击卡片跳转设置页；
     * [sources] 非空时在卡片下方附知识库引用来源小字行（与 prompt 内 [n] 编号对应）。
     */
    private fun addAnswerBubble(
        text: String,
        isError: Boolean,
        withSettingsEntry: Boolean = false,
        sources: List<String> = emptyList()
    ) {
        // 成功答案按 Markdown 渲染；错误提示为本地文案，保持纯文本
        val content: CharSequence = if (isError) text else MarkdownRenderer.render(text, markdownPalette)
        val bubble = createBubble(content, alignEnd = false,
            bgColor = theme.keyBackground,
            textColor = if (isError) 0xFFD32F2F.toInt() else theme.keyTextColor)
        if (withSettingsEntry) {
            bubble.getChildAt(0).setOnClickListener { host.onRequestOpenSettings() }
        }
        // 成功答案右侧附操作列：「发送」上屏解析后纯文本（Markdown 标记已剥离，
        // 列表已转 •）；图片钮按编辑器图片能力呈现「发图」（commitContent 直发）
        // 或「存图」（保存到相册），标签在气泡创建时快照，点击时宿主按最新检测结果路由
        if (!isError) {
            val actions = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(createAnswerActionButton("发送") { host.onCommitAnswer(content.toString()) },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                val imageLabel = if (host.editorAcceptsImage()) "发图" else "存图"
                addView(createAnswerActionButton(imageLabel) { host.onSendAnswerAsImage(content) },
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
        // 知识库引用来源行（仅本次检索命中时展示；不做点击跳转，IME 窗口限制）
        if (!isError && sources.isNotEmpty()) {
            val label = sources.mapIndexed { i, name -> "[${i + 1}]$name" }
                .distinct()
                .joinToString(" ")
            chatList.addView(TextView(context).apply {
                // 外层函数参数 text 遮蔽 TextView.text，需显式 this
                this.text = "参考: $label"
                textSize = 11f
                setTextColor(theme.preeditTextColor)
                setPadding(dp(4f), 0, dp(4f), dp(2f))
                maxLines = 2
            })
        }
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
                val kbBadge = if (persona.knowledgeItemIds.isNotEmpty())
                    " \uD83D\uDCDA${persona.knowledgeItemIds.size}" else ""
                val desc = if (persona.description.isNotBlank()) "  ${persona.description}" else ""
                text = "$check${persona.name} $badge$kbBadge$desc"
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
        // 新建角色入口（跳人设管理页：名称/简介/提示词/绑定知识库）
        container.addView(TextView(context).apply {
            text = "＋ 新建角色（在人设管理页配置与绑定知识库）"
            textSize = 13f
            setTextColor(theme.candidateHighlightColor)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener {
                host.performHaptic()
                personaOverlay.visibility = GONE
                host.onRequestOpenPersonaManager()
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
     * 切换人设：更新当前人设、刷新标签，并清空两种模式的会话
     * （风格/知识域切换，上下文不应混用），然后收起浮层。
     */
    private fun switchToPersona(persona: AiPersona) {
        if (persona.id == currentPersona.id) {
            personaOverlay.visibility = GONE
            return
        }
        requestJob?.cancel()
        requestJob = null
        // 切换前持久化当前问答会话记忆（摘要按旧人设分槽）
        persistConversationMemory()
        currentPersona = persona
        PersonaRepository.setCurrentPersona(context, persona.id)
        refreshPersonaLabel()
        // 两模式会话一并清空
        chatHistory.clear()
        polishHistory.clear()
        lastPolishDraft = null
        hasPolishResult = false
        resetConversationUi()
        personaOverlay.visibility = GONE
    }

    // ===== 按钮文案 =====

    /** 发送按钮文案：问答「搜索」/ 润色「润色」（有候选后「重新润色」）。 */
    private fun refreshSendButton() {
        sendButton.text = when {
            mode == AiPanelMode.ASK -> "搜索"
            hasPolishResult -> "重新润色"
            else -> "润色"
        }
    }

    /** 人设标签文案：人设名 + 绑定知识库时的 📚N 徽标。 */
    private fun refreshPersonaLabel() {
        val bound = currentPersona.knowledgeItemIds.size
        personaLabel.text = if (bound > 0) "\uD83C\uDFAD${currentPersona.name}\uD83D\uDCDA$bound"
        else "\uD83C\uDFAD${currentPersona.name}"
    }

    // ===== 对话记忆 =====

    /** 持久化当前会话历史并异步更新当前人设名下的跨会话摘要
     *  （新对话 / 切人设 / release 时调用；仅问答历史，润色不落盘）。 */
    private fun persistConversationMemory() {
        if (chatHistory.isEmpty()) return
        val appContext = context.applicationContext
        val historySnapshot = chatHistory.toList()
        val personaId = currentPersona.id
        AiMemoryStore.saveSession(appContext, historySnapshot)
        // 摘要生成走 AiMemoryStore 内部的独立 IO 作用域，不依赖面板 panelScope
        AiMemoryStore.updateSummaryAsync(appContext, personaId, historySnapshot)
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

    /** 面板移除前必须调用：持久化会话记忆与统计，取消进行中的 AI 请求与协程作用域。 */
    fun release() {
        persistConversationMemory()
        AiUsageStats.flush(context.applicationContext)
        requestJob?.cancel()
        requestJob = null
        panelScope.cancel()
    }
}
