package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ziyou.ime.core.toolbar.ToolbarConfigLogic
import com.ziyou.ime.skin.SkinTheme

/**
 * 工具面板：候选区按钮栏 Logo 键打开，网格展示全部可用工具项。
 *
 * 内部结构为「标题栏 + 工具网格」。挂载在输入视图内容根容器顶部并立即
 * 收起键盘/候选区（见 [ToolPanelCoordinator]），与粘贴板/涂鸦面板同一
 * 高度守恒策略。面板无文字输入需求，不接管输入路由——点击工具项经宿主
 * 回调功能码，由 Service 的 handleSoftKeyPress 统一路由（与功能栏按钮同源）。
 *
 * 标题栏：标题 + 设置 + 编辑 + 关闭 ✕（设置入口自工具网格移入标题栏，
 *  居「编辑」按钮左侧）；网格区：每行 [GRID_COLUMNS] 个工具格
 * （圆角图标位 + 名称），ScrollView 纵向滚动兜底小屏/横屏空间不足。
 * 图标位绘矢量图标（[ToolbarIconDrawer]，与功能栏 [CandidateToolbarView]
 * 同一目录与视觉规格，图标色跟随皮肤 toolbarTextColor）。
 *
 * 编辑模式（键盘内自定义功能栏，「编辑」键进入 /「完成」键退出）：
 * 网格切换为双分区——「已显示」区按功能栏视觉顺序（左→右）排列已启用
 * 按钮，格角标 ⊖ 点击移除、格底 ←/→ 微调顺序；「更多工具」区列出未启用
 * 按钮，角标 ⊕ 点击追加到功能栏最左侧。每次操作即改即存（经
 * [Host.onToolbarIdsChanged] 落盘），功能栏视图的配置监听实时刷新，
 * 无「保存」步骤。id 列表变换复用 :core-logic 的 [ToolbarConfigLogic]
 * 纯函数（add/remove/move），删空由纯函数拒绝（功能栏永不为空）。
 *
 * 无障碍：每个工具格为原生 View，contentDescription 取工具名称
 * （编辑态为「添加/移除/左移/右移 xx」动作描述），TalkBack 焦点导航
 * 与点击动作开箱即用。
 *
 * 配色全部映射自当前 [SkinTheme]，与 [ClipboardPanelView] 同一视觉语言；
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class ToolPanelView(
    context: Context,
    private val theme: SkinTheme,
    private val tools: List<ToolPanelCatalog.Tool>,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图） */
        fun onRequestClose()

        /** 点击工具项：回抛 [KeyCode] 功能码（宿主先关面板再统一路由） */
        fun onToolSelected(keyCode: Int)

        /** 按键震动反馈 */
        fun performHaptic()

        /** 编辑模式：当前启用按钮的有序 id（宿主读配置仓库并经目录清洗） */
        fun enabledToolbarIds(): List<String>

        /** 编辑模式：配置变更即改即存（宿主落盘，功能栏经配置监听实时刷新） */
        fun onToolbarIdsChanged(ids: List<String>)
    }

    companion object {
        /** 网格列数（4 列在最窄停靠宽度下图标位与名称仍有充足留白） */
        private const val GRID_COLUMNS = 4

        /** 图标边长占图标位短边的比例（52dp 图标位内绘 26dp 图标，
         *  小尺寸下笔画留白充足，与功能栏胶囊内图标视觉密度一致） */
        private const val ICON_SIZE_RATIO = 0.5f

        /** 编辑态角标圆半径（dp，绘于图标位右上角） */
        private const val BADGE_RADIUS_DP = 8f
    }

    /** 编辑态格角标：⊕ 添加到功能栏 / ⊖ 从功能栏移除 */
    private enum class Badge { ADD, REMOVE }

    /** 矢量图标绘制器（路径构造期缓存，与功能栏同一套图标目录） */
    private val iconDrawer = ToolbarIconDrawer()

    /** 是否处于编辑模式（功能栏按钮的增删与排序） */
    private var editing = false

    /** 编辑模式下启用按钮的有序 id（进入编辑态时从宿主读取快照，即改即存保持同步） */
    private var enabledIds: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private val titleView: TextView
    private val settingsButton: TextView
    private val editButton: TextView
    private val gridLayout: LinearLayout

    /** 角标圆底画笔（主题候选高亮色，与按下态强调色同源） */
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.candidateHighlightColor
    }

    /** 角标符号画笔（+/− 线条，取面板底色形成反白） */
    private val badgeSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = theme.keyboardBackground
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true
        badgeSymbolPaint.strokeWidth = dp(1.6f).toFloat()

        // ── 标题栏：标题 + 设置 + 编辑 + 关闭 ✕ ──
        titleView = TextView(context).apply {
            text = "全部工具"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER_VERTICAL
        }
        settingsButton = TextView(context).apply {
            text = "设置"
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            contentDescription = "打开设置"
            setOnClickListener {
                host.performHaptic()
                // 与网格工具项同路由：宿主先关面板再经 handleSoftKeyPress 打开设置页
                host.onToolSelected(KeyCode.KEYCODE_OPEN_SETTINGS)
            }
        }
        editButton = TextView(context).apply {
            text = "编辑"
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            contentDescription = "编辑功能栏"
            setOnClickListener {
                host.performHaptic()
                setEditing(!editing)
            }
        }
        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            contentDescription = "关闭工具面板"
            setOnClickListener {
                host.performHaptic()
                host.onRequestClose()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
            addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // 弹性占位：标题靠左，设置/编辑/关闭钮靠右
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(settingsButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
                rightMargin = dp(8f)
            })
            addView(editButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
                rightMargin = dp(8f)
            })
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 网格区：按行等分排列，ScrollView 接管剩余空间 ──
        gridLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(gridLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        rebuildContent()
    }

    // ===== 编辑模式状态 =====

    /** 浏览态 ⇄ 编辑态切换：进入时读取配置快照，标题/按钮文案随态更新。 */
    private fun setEditing(active: Boolean) {
        editing = active
        if (active) enabledIds = host.enabledToolbarIds()
        titleView.text = if (active) "编辑功能栏" else "全部工具"
        editButton.text = if (active) "完成" else "编辑"
        editButton.contentDescription = if (active) "完成编辑" else "编辑功能栏"
        rebuildContent()
    }

    /** 应用新配置：内容无变化时短路；变化则即改即存并重建编辑网格。 */
    private fun updateEnabledIds(newIds: List<String>) {
        if (newIds == enabledIds) return
        enabledIds = newIds
        host.onToolbarIdsChanged(newIds)
        rebuildContent()
    }

    /**
     * 「已显示」区按功能栏视觉顺序（左→右）展示：功能栏从右往左排列
     * （配置 index 0 贴最右），故展示列表 = 配置列表反转；排序操作在
     * 展示域执行（← 即视觉左移）后反转存回，用户心智与所见一致。
     */
    private fun moveDisplayed(displayIndex: Int, offset: Int) {
        val display = enabledIds.reversed()
        val moved = ToolbarConfigLogic.move(display, displayIndex, offset)
        if (moved == display) return
        host.performHaptic()
        updateEnabledIds(moved.reversed())
    }

    /** 移除按钮：纯函数拒绝删空（原样返回）时提示，与设置页约束一致。 */
    private fun removeItem(id: String) {
        val removed = ToolbarConfigLogic.remove(enabledIds, id)
        if (removed == enabledIds) {
            Toast.makeText(context, "至少保留一个功能按钮", Toast.LENGTH_SHORT).show()
            return
        }
        host.performHaptic()
        updateEnabledIds(removed)
    }

    /** 添加按钮：追加到配置末尾（功能栏视觉最左侧，靠近 Logo）。 */
    private fun addItem(id: String) {
        host.performHaptic()
        updateEnabledIds(ToolbarConfigLogic.add(enabledIds, id))
    }

    // ===== 网格构建 =====

    /** 按当前态重建网格内容：浏览态全量工具；编辑态「已显示 + 更多工具」双分区。 */
    private fun rebuildContent() {
        gridLayout.removeAllViews()
        if (!editing) {
            addGridRows(tools.map { tool ->
                createToolCell(tool, badge = null, description = tool.name) {
                    host.onToolSelected(tool.keyCode)
                }
            })
            return
        }
        // 可自定义工具索引（目录项 toolbarId 回指功能栏 id）
        val byId = tools.filter { it.toolbarId != null }.associateBy { it.toolbarId!! }
        // 已显示区：按功能栏视觉顺序（左→右）= 配置列表反转
        val displayIds = enabledIds.reversed()
        gridLayout.addView(sectionLabel("已显示（按功能栏左→右顺序，点 ⊖ 移除）"))
        addGridRows(displayIds.mapIndexedNotNull { index, id ->
            val tool = byId[id] ?: return@mapIndexedNotNull null
            createToolCell(
                tool, badge = Badge.REMOVE,
                description = "从功能栏移除${tool.name}",
                displayIndex = index
            ) { removeItem(id) }
        })
        // 更多工具区：未启用的可自定义项，点 ⊕ 添加
        val moreTools = tools.filter { it.toolbarId != null && it.toolbarId !in enabledIds }
        if (moreTools.isNotEmpty()) {
            gridLayout.addView(sectionLabel("更多工具（点 ⊕ 添加到功能栏）"))
            addGridRows(moreTools.map { tool ->
                createToolCell(
                    tool, badge = Badge.ADD,
                    description = "添加${tool.name}到功能栏"
                ) { addItem(tool.toolbarId!!) }
            })
        }
    }

    /** 将工具格按每行 [GRID_COLUMNS] 个排入网格，末行补空占位保持单元宽一致。 */
    private fun addGridRows(cells: List<View>) {
        for (rowCells in cells.chunked(GRID_COLUMNS)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (cell in rowCells) {
                row.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(GRID_COLUMNS - rowCells.size) {
                row.addView(View(context), LayoutParams(0, 0, 1f))
            }
            gridLayout.addView(row, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        }
    }

    /** 编辑态分区标题（小号弱化文本）。 */
    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(theme.keyTextColor)
        alpha = 0.6f
        setPadding(dp(4f), dp(8f), dp(4f), 0)
    }

    /**
     * 单个工具格：圆角方形图标位（矢量图标，皮肤 toolbarTextColor 染色）
     * + 下方名称，整格可点。编辑态附加：图标位右上角 ⊕/⊖ 角标（[badge]）；
     * 「已显示」区格底 ←/→ 排序箭头（[displayIndex] >= 0 时）。
     */
    private fun createToolCell(
        tool: ToolPanelCatalog.Tool,
        badge: Badge?,
        description: String,
        displayIndex: Int = -1,
        onClick: () -> Unit
    ): View {
        val iconView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (width <= 0 || height <= 0) return
                iconDrawer.draw(
                    canvas,
                    tool.icon,
                    width / 2f,
                    height / 2f,
                    minOf(width, height) * ICON_SIZE_RATIO,
                    theme.toolbarTextColor
                )
                if (badge != null) {
                    // 右上角角标：圆底 + 反白 +/− 符号
                    val r = dp(BADGE_RADIUS_DP).toFloat()
                    val cx = width - r
                    val cy = r
                    canvas.drawCircle(cx, cy, r, badgeBgPaint)
                    val arm = r * 0.5f
                    canvas.drawLine(cx - arm, cy, cx + arm, cy, badgeSymbolPaint)
                    if (badge == Badge.ADD) {
                        canvas.drawLine(cx, cy - arm, cx, cy + arm, badgeSymbolPaint)
                    }
                }
            }
        }.apply {
            background = roundedBg(theme.keyBackground, 14f, theme.borderColor)
            // 图标位不单独朗读，无障碍描述统一挂在整格
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val nameView = TextView(context).apply {
            text = tool.name
            textSize = 11f
            setTextColor(theme.keyTextColor)
            alpha = 0.75f
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            addView(iconView, LayoutParams(dp(52f), dp(52f)))
            addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5f)
            })
            contentDescription = description
            setOnClickListener {
                if (badge == null) host.performHaptic()
                onClick()
            }
        }
        if (displayIndex >= 0) {
            // 排序箭头行：视觉左移/右移（映射到配置列表由 moveDisplayed 反转处理）
            val arrows = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            arrows.addView(arrowView("←", "左移${tool.name}") { moveDisplayed(displayIndex, -1) })
            arrows.addView(arrowView("→", "右移${tool.name}") { moveDisplayed(displayIndex, 1) })
            cell.addView(arrows, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            })
        }
        return cell
    }

    /** 排序箭头（编辑态「已显示」区格底，主题强调色提示可点）。 */
    private fun arrowView(label: String, desc: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(theme.candidateHighlightColor)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            contentDescription = desc
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
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
