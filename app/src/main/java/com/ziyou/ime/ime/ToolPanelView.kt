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

/**
 * 工具面板：候选区按钮栏 Logo 键打开，网格展示全部可用工具项。
 *
 * 内部结构为「标题栏 + 工具网格」。挂载在输入视图内容根容器顶部并立即
 * 收起键盘/候选区（见 [ToolPanelCoordinator]），与粘贴板/涂鸦面板同一
 * 高度守恒策略。面板无文字输入需求，不接管输入路由——点击工具项经宿主
 * 回调功能码，由 Service 的 handleSoftKeyPress 统一路由（与功能栏按钮同源）。
 *
 * 标题栏：标题 + 关闭 ✕；网格区：每行 [GRID_COLUMNS] 个工具格
 * （圆角图标位 + 名称），ScrollView 纵向滚动兜底小屏/横屏空间不足。
 *
 * 无障碍：每个工具格为原生 View，contentDescription 取工具名称，
 * TalkBack 焦点导航与点击动作开箱即用。
 *
 * 配色全部映射自当前 [SkinTheme]，与 [ClipboardPanelView] 同一视觉语言；
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class ToolPanelView(
    context: Context,
    private val theme: SkinTheme,
    tools: List<ToolPanelCatalog.Tool>,
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
    }

    companion object {
        /** 网格列数（4 列在最窄停靠宽度下图标位与名称仍有充足留白） */
        private const val GRID_COLUMNS = 4
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏：标题 + 关闭 ✕ ──
        val titleView = TextView(context).apply {
            text = "全部工具"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER_VERTICAL
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
            // 弹性占位：标题靠左，关闭钮靠右
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 网格区：按行等分排列，ScrollView 接管剩余空间 ──
        val gridLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        }
        for (rowTools in tools.chunked(GRID_COLUMNS)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (tool in rowTools) {
                row.addView(createToolCell(tool), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            // 末行不足整行时补空占位，保持单元宽度一致
            repeat(GRID_COLUMNS - rowTools.size) {
                row.addView(View(context), LayoutParams(0, 0, 1f))
            }
            gridLayout.addView(row, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(gridLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** 单个工具格：圆角方形图标位（label 字符）+ 下方名称，整格可点。 */
    private fun createToolCell(tool: ToolPanelCatalog.Tool): View {
        val iconView = TextView(context).apply {
            text = tool.label
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
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
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            addView(iconView, LayoutParams(dp(52f), dp(52f)))
            addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5f)
            })
            contentDescription = tool.name
            setOnClickListener {
                host.performHaptic()
                host.onToolSelected(tool.keyCode)
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
