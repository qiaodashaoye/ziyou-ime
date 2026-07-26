package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ziyou.ime.config.KeyboardTheme

/**
 * 候选区功能按钮栏
 *
 * 与「编码区 [PreeditOverlayView] + 候选词列表 [SimpleCandidatesView]」整体叠放在
 * 同一区域（FrameLayout 覆盖），显隐互斥由 Service 层控制：
 * - 无候选词时显示本按钮栏（收起键盘 / 技能面板 / 悬浮切换 / 主题切换 / 设置）
 * - 有候选词时隐藏，让位给编码区与候选词列表
 *
 * 遵循本项目 Canvas 纯绘制的既有风格（见 [BaseKeyboardView] / [SimpleCandidatesView]），
 * 按钮点击通过 [onButtonClick] 回调携带 [KeyCode] 自定义功能码向上抛出，
 * 由 Service 的 handleSoftKeyPress 统一路由，View 层不持有 Service 引用。
 */
class CandidateToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 视图高度（dp）：编码区 + 候选词列表的总高，与二者垂直堆叠后的
         *  整体高度一致，显隐切换时无高度跳动（高度常量单一来源于各自视图） */
        private const val VIEW_HEIGHT_DP =
            PreeditOverlayView.VIEW_HEIGHT_DP + SimpleCandidatesView.VIEW_HEIGHT_DP
        /** 按钮文字大小（sp） */
        private const val BUTTON_TEXT_SIZE_SP = 15f
        /** 按下高亮的圆角半径（dp） */
        private const val HIGHLIGHT_RADIUS_DP = 6f
        /** 按下高亮的内缩边距（dp） */
        private const val HIGHLIGHT_INSET_DP = 3f
    }

    /** 单个功能按钮：显示文本 + KeyCode 自定义功能码 */
    private data class ToolbarButton(val label: String, val keyCode: Int)

    /** 按钮列表（自左向右均分排列），功能码经 [onButtonClick] 抛给 Service 路由；
     *  「技」「浮」已从主键盘布局移除，统一集中到本按钮栏 */
    private val buttons = listOf(
        ToolbarButton("设", KeyCode.KEYCODE_OPEN_SETTINGS),
        ToolbarButton("肤", KeyCode.KEYCODE_SWITCH_THEME),
        ToolbarButton("技", KeyCode.KEYCODE_SKILL_PANEL),
        ToolbarButton("浮", KeyCode.KEYCODE_TOGGLE_FLOATING),
        ToolbarButton("\u2304", KeyCode.KEYCODE_HIDE_KEYBOARD)
    )

    /** 按钮点击回调，参数为 [KeyCode] 自定义功能码（由 Service 统一处理） */
    var onButtonClick: ((keyCode: Int) -> Unit)? = null

    /**
     * 全局缩放因子（悬浮模式用）：同步缩小视图高度与按钮字号，
     * 与键盘/候选视图的 scaleFactor 保持一致。默认 1.0，停靠模式零影响。
     */
    var scaleFactor: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                textPaint.textSize = sp2px(BUTTON_TEXT_SIZE_SP)
                minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
                requestLayout()
                invalidate()
            }
        }

    /** 当前按下的按钮索引，-1 表示无按下 */
    private var pressedIndex = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(BUTTON_TEXT_SIZE_SP)
        color = Color.DKGRAY
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.FILL
    }

    init {
        minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
    }

    /**
     * 应用主题，与候选词视图和键盘视图保持视觉一致（由 Service 层调用）
     */
    fun applyTheme(theme: KeyboardTheme) {
        bgPaint.color = theme.candidateBackground
        textPaint.color = theme.candidateTextColor
        pressedPaint.color = theme.keyPressedBackground
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制背景（与候选词区共享同一背景色，视觉连续为整体）
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (buttons.isEmpty() || width == 0) return

        val cellWidth = width.toFloat() / buttons.size
        val textBaseline = height / 2f + textPaint.textSize / 3f
        val inset = dp2px(HIGHLIGHT_INSET_DP)
        val radius = dp2px(HIGHLIGHT_RADIUS_DP)

        for (i in buttons.indices) {
            val left = i * cellWidth
            // 按下高亮：内缩圆角矩形，颜色沿用按键按下色
            if (i == pressedIndex) {
                val rect = RectF(left + inset, inset, left + cellWidth - inset, height - inset)
                canvas.drawRoundRect(rect, radius, radius, pressedPaint)
            }
            canvas.drawText(buttons[i].label, left + cellWidth / 2f, textBaseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = buttonIndexAt(event.x, event.y)
                if (pressedIndex >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 手指移出按下的按钮范围即取消高亮（不触发点击）
                if (pressedIndex >= 0 && buttonIndexAt(event.x, event.y) != pressedIndex) {
                    pressedIndex = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                pressedIndex = -1
                invalidate()
                if (index >= 0 && buttonIndexAt(event.x, event.y) == index) {
                    onButtonClick?.invoke(buttons[index].keyCode)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 根据触摸坐标查找按钮索引，越界返回 -1 */
    private fun buttonIndexAt(x: Float, y: Float): Int {
        if (y < 0 || y > height || x < 0 || x >= width || buttons.isEmpty()) return -1
        val index = (x / (width.toFloat() / buttons.size)).toInt()
        return index.coerceIn(0, buttons.size - 1)
    }

    // ===== 单位转换工具（已叠加缩放因子，悬浮模式下尺寸统一缩放） =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density * scaleFactor

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity * scaleFactor
}
