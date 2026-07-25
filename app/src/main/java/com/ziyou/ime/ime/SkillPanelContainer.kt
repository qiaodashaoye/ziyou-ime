package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ziyou.ime.config.KeyboardTheme
import com.ziyou.ime.skill.SkillBridge
import com.ziyou.ime.skill.SkillInfo
import com.ziyou.ime.skill.SkillManager
import com.ziyou.ime.skill.SkillRuntime
import com.ziyou.ime.skill.SkillWebViewFactory

/**
 * 技能面板容器：内部结构为「宿主标题栏（脚本不可遮盖，反钓鱼锚点）+
 * 内容区（技能列表 / 技能 WebView）」。
 *
 * 挂载位置由宿主按阶段切换（经 [Host.onRequestElevatedLayout]）：
 * - 技能列表 / 普通技能：覆盖键盘区域（候选/编码区保持在上方）；
 * - needs_input 技能：提升至编码区上方（紧凑高度，下方键盘可用供路由打字）。
 *
 * WebView 生命周期完全收敛在本容器：点开技能才懒创建，返回列表 / 关闭面板 /
 * 渲染进程崩溃即销毁（单实例、用完即毁，控制 IME 进程内存增量）。
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
class SkillPanelContainer(
    context: Context,
    private val theme: KeyboardTheme,
    private val host: Host
) : LinearLayout(context), SkillRuntime.Host {

    /** Service 需提供的宿主能力。 */
    interface Host {
        /** 文本上屏（统一走 InputLogicController 出口） */
        fun commitText(text: String)

        /** 用户/脚本请求关闭面板（Service 负责移除视图并调用 [release]） */
        fun onRequestClose()

        /** 当前编辑器包名 */
        fun editorPackageName(): String?

        /** 当前输入框类型 */
        fun editorInputType(): String

        /** 按键震动反馈 */
        fun performHaptic()

        /** 面板提升开关：needs_input 技能打开时提升至编码区上方（键盘露出可用），
         *  退出时恢复键盘叠层 */
        fun onRequestElevatedLayout(active: Boolean)

        /** 输入路由开关：true 时键盘上屏文本改道注入面板（commitTarget 切换） */
        fun onInputRoutingChanged(active: Boolean)

        /** 输入法界面展开开关：false 时键盘/编码区/候选区整体缩回、面板接管其空间（窗口总高不变） */
        fun onRequestImeExpanded(expanded: Boolean)
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private val titleView: TextView
    private val backButton: TextView
    private val contentFrame: FrameLayout
    private val listScroll: ScrollView

    private var webView: WebView? = null
    private var bridge: SkillBridge? = null
    private var runtime: SkillRuntime? = null

    /** 输入路由是否激活（input.requestFocus 后为 true） */
    private var inputRoutingActive = false

    /**
     * 上屏目标：输入路由激活时键盘文本经此注入面板输入框
     * （垫片 window.__imeskillInput 分发到 requestFocus 登记的元素）。
     */
    val skillCommitTarget = object : InputLogicController.CommitTarget {
        override fun commit(text: CharSequence) {
            evalInputJs("commit", org.json.JSONObject.quote(text.toString()))
        }

        override fun deleteBackward() {
            evalInputJs("backspace", null)
        }
    }

    private fun evalInputJs(fn: String, arg: String?) {
        webView?.evaluateJavascript(
            "window.__imeskillInput&&window.__imeskillInput.$fn(${arg ?: ""})", null)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层键盘
        isClickable = true

        // ── 宿主标题栏（原生绘制，脚本不可遮盖）──
        backButton = TextView(context).apply {
            text = "‹ 返回"
            textSize = 14f
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            visibility = GONE
            setOnClickListener { showSkillList() }
        }
        titleView = TextView(context).apply {
            text = TITLE_DEFAULT
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
            setOnClickListener { host.onRequestClose() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.candidateBackground)
            addView(backButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // 标题栏下分隔线
        addView(View(context).apply { setBackgroundColor(theme.borderColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))

        // ── 内容区：技能列表（默认）/ 技能 WebView ──
        val skillListView = SkillListView(context, theme, SkillManager.listSkills(context)) { skill ->
            openSkill(skill)
        }
        listScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(skillListView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        contentFrame = FrameLayout(context)
        contentFrame.addView(listScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ===== 技能装载 =====

    /** 点开技能：懒创建 WebView（安全配置统一走工厂）并加载入口页。
     *  needs_input 技能提升至编码区上方（键盘露出供路由打字），
     *  普通技能保持键盘叠层（覆盖键盘区域）。 */
    private fun openSkill(skill: SkillInfo) {
        destroyWebView()
        val skillRuntime = SkillRuntime(context, skill, this)
        val skillBridge = SkillBridge(skillRuntime) { webView }
        val view = SkillWebViewFactory.create(context, skill, skillBridge) {
            // 渲染进程崩溃：销毁 WebView 回到技能列表，IME 与面板存活
            showSkillList()
        }
        runtime = skillRuntime
        bridge = skillBridge
        webView = view
        listScroll.visibility = GONE
        contentFrame.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        titleView.text = skill.manifest.name
        backButton.visibility = VISIBLE
        // 挂载位置按技能类型切换（幂等）：needs_input 提升，否则确保回到键盘叠层
        host.onRequestElevatedLayout(skill.manifest.needsInput)
        view.loadUrl(SkillWebViewFactory.entryUrl(skill))
    }

    /** 返回技能列表（销毁当前技能 WebView，复位路由并恢复键盘叠层挂载）。 */
    private fun showSkillList() {
        destroyWebView()
        listScroll.visibility = VISIBLE
        titleView.text = TITLE_DEFAULT
        backButton.visibility = GONE
        host.onRequestElevatedLayout(false)
    }

    /** 面板移除前必须调用：释放 Bridge 与 WebView（用完即毁）。 */
    fun release() {
        destroyWebView()
    }

    private fun destroyWebView() {
        // 先恢复输入法界面（退出收缩态）与输入路由（commitTarget 切回宿主编辑器）
        host.onRequestImeExpanded(true)
        if (inputRoutingActive) {
            inputRoutingActive = false
            host.onInputRoutingChanged(false)
        }
        bridge?.release()
        bridge = null
        runtime?.release()
        runtime = null
        webView?.let { view ->
            contentFrame.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    // ===== SkillRuntime.Host（脚本能力落地）=====

    override fun commitText(text: String) = host.commitText(text)

    override fun closePanel() = host.onRequestClose()

    override fun setPanelTitle(title: String) {
        if (title.isNotBlank()) titleView.text = title
    }

    override fun editorPackageName(): String? = host.editorPackageName()

    override fun editorInputType(): String = host.editorInputType()

    override fun performHaptic() = host.performHaptic()

    override fun requestInputRouting(active: Boolean) {
        // 打字需要完整输入法界面：申请输入焦点时自动恢复键盘/编码区/候选区
        if (active) host.onRequestImeExpanded(true)
        if (active == inputRoutingActive) return
        inputRoutingActive = active
        host.onInputRoutingChanged(active)
    }

    override fun setImeExpanded(expanded: Boolean) {
        host.onRequestImeExpanded(expanded)
    }

    companion object {
        private const val TITLE_DEFAULT = "技能"
    }
}

/**
 * 技能列表网格（纯 Canvas 绘制，与键盘视觉风格一致）。
 *
 * 每格：图标字符（emoji，manifest.icon_text）+ 技能名；按下高亮，抬手回调。
 * 空列表时居中提示。
 */
@SuppressLint("ViewConstructor")
private class SkillListView(
    context: Context,
    private val theme: KeyboardTheme,
    private val skills: List<SkillInfo>,
    private val onSkillClick: (SkillInfo) -> Unit
) : View(context) {

    companion object {
        private const val COLUMNS = 4
        private const val CELL_HEIGHT_DP = 76f
        private const val CELL_GAP_DP = 8f
        private const val CORNER_DP = 10f
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val cellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyBackground }
    private val cellPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyPressedBackground }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(26f)
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
        color = theme.keyTextColor
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        color = theme.keyTextColor and 0x00FFFFFF or 0x99000000.toInt()
    }

    private var pressedIndex = -1
    private val cellRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = if (skills.isEmpty()) 1 else (skills.size + COLUMNS - 1) / COLUMNS
        val height = (rows * dp(CELL_HEIGHT_DP) + (rows + 1) * dp(CELL_GAP_DP)).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (skills.isEmpty()) {
            canvas.drawText("暂无可用技能", width / 2f, height / 2f, emptyPaint)
            return
        }
        skills.forEachIndexed { index, skill ->
            computeCellRect(index, cellRect)
            val bg = if (index == pressedIndex) cellPressedPaint else cellBgPaint
            canvas.drawRoundRect(cellRect, dp(CORNER_DP), dp(CORNER_DP), bg)
            val cx = cellRect.centerX()
            canvas.drawText(skill.manifest.iconText ?: "⚙", cx, cellRect.top + dp(36f), iconPaint)
            canvas.drawText(skill.manifest.name, cx, cellRect.bottom - dp(12f), namePaint)
        }
    }

    private fun computeCellRect(index: Int, out: RectF) {
        val gap = dp(CELL_GAP_DP)
        val cellWidth = (width - gap * (COLUMNS + 1)) / COLUMNS
        val col = index % COLUMNS
        val row = index / COLUMNS
        val left = gap + col * (cellWidth + gap)
        val top = gap + row * (dp(CELL_HEIGHT_DP) + gap)
        out.set(left, top, left + cellWidth, top + dp(CELL_HEIGHT_DP))
    }

    private fun indexAt(x: Float, y: Float): Int {
        val rect = RectF()
        skills.indices.forEach { index ->
            computeCellRect(index, rect)
            if (rect.contains(x, y)) return index
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = indexAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedIndex >= 0 && indexAt(event.x, event.y) != pressedIndex) {
                    pressedIndex = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x, event.y)
                if (index >= 0 && index == pressedIndex) {
                    onSkillClick(skills[index])
                }
                pressedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }
}
