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
 * 键盘选择面板：功能栏「键盘切换」按钮打开，列表展示全部可选主键盘布局。
 *
 * 候选目录来自 [KeyboardType.PICKER_TYPES]（声明了 pickerLabel 的可持久化
 * 主键盘：九宫格拼音 / 全键盘拼音；符号/数字为临时面板不入选单，
 * 手写等新布局实现后声明 pickerLabel 即自动进入本面板）。
 *
 * 挂载在输入视图内容根容器顶部并立即收起键盘/候选区（见
 * [KeyboardPickerCoordinator]），与工具/粘贴板面板同一高度守恒策略。
 * 无文字输入需求，不接管输入路由——点击条目经宿主回调目标布局，
 * 由 Service 先关面板再走 switchKeyboard 统一切换路径（与键盘内
 * 切换键同源，方案/中英模式由 applyEngineForKeyboard 自动对齐）。
 *
 * 当前布局条目以主题强调色高亮并带「✓ 当前」标记；点击当前项仅关闭
 * 面板（switchKeyboard 幂等短路，零开销）。
 *
 * 无障碍：每行原生 View，contentDescription 为「切换到 xx」/「当前键盘 xx」。
 * 配色全部映射自 [SkinTheme]，与 [ToolPanelView] 同一视觉语言；
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class KeyboardPickerPanelView(
    context: Context,
    private val theme: SkinTheme,
    current: KeyboardType,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图） */
        fun onRequestClose()

        /** 选中键盘布局：宿主先关面板再走 switchKeyboard 统一切换 */
        fun onKeyboardSelected(type: KeyboardType)

        /** 按键震动反馈 */
        fun performHaptic()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏：标题 + 关闭 ✕（与工具面板同款） ──
        val titleView = TextView(context).apply {
            text = "选择键盘"
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
            background = roundedBg(theme.keyBackground, 8f)
            contentDescription = "关闭键盘选择面板"
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

        // ── 布局列表：全部可选主键盘，当前项高亮 ──
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        }
        for (type in KeyboardType.PICKER_TYPES) {
            list.addView(createRow(type, type == current), LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** 单行布局条目：名称靠左 + 当前标记靠右，整行圆角可点，当前项强调色高亮。 */
    private fun createRow(type: KeyboardType, selected: Boolean): View {
        val label = requireNotNull(type.pickerLabel)
        val nameView = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(if (selected) theme.candidateHighlightColor else theme.keyTextColor)
            if (selected) typeface = Typeface.DEFAULT_BOLD
        }
        val markView = TextView(context).apply {
            text = if (selected) "✓ 当前" else ""
            textSize = 12f
            setTextColor(theme.candidateHighlightColor)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
            background = roundedBg(theme.keyBackground, 12f)
            addView(nameView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(markView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            contentDescription = if (selected) "当前键盘 $label" else "切换到 $label"
            setOnClickListener {
                host.performHaptic()
                host.onKeyboardSelected(type)
            }
        }
    }

    /** 圆角背景（描边取皮肤边框色），与键盘主题色一致。 */
    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1f), theme.borderColor)
        }
    }
}
