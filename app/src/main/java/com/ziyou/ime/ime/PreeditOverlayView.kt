package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.ziyou.ime.config.KeyboardTheme

/**
 * 编码区视图
 *
 * 独立显示 Rime 编码区（preedit）文本，位于候选词列表上方，通过垂直 LinearLayout 堆叠。
 * 职责单一：仅负责编码区文本的渲染与主题适配。
 *
 * 空值规范：传入 null 或空字符串时设为 GONE（不占位），有效文本时设为 VISIBLE。
 */
class PreeditOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 编码区字体大小（sp） */
        private const val TEXT_SIZE_SP = 12f
        /** 水平内边距（dp） */
        private const val PADDING_H_DP = 12
        /** 默认视图高度（dp），与候选词区域保持一致 */
        private const val VIEW_HEIGHT_DP = 40
        /** 分隔线宽度（dp） */
        private const val DIVIDER_WIDTH_DP = 1f
    }

    /** 当前显示的编码文本 */
    private var displayText: String? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(TEXT_SIZE_SP)
        color = Color.parseColor("#666666")
        typeface = Typeface.DEFAULT
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        strokeWidth = dp2px(DIVIDER_WIDTH_DP)
    }

    /**
     * 设置编码区显示文本。
     *
     * 空值规范：
     * - null 或空字符串 → GONE（不占位）
     * - 有效文本 → VISIBLE 并触发重绘
     */
    fun setText(text: String?) {
        val normalized = text?.takeIf { it.isNotEmpty() }
        if (displayText == normalized) return
        displayText = normalized
        visibility = if (normalized != null) VISIBLE else GONE
        requestLayout()
        invalidate()
    }

    /**
     * 应用主题，与候选词视图和键盘视图保持视觉一致（由 Service 层调用）
     */
    fun applyTheme(theme: KeyboardTheme) {
        textPaint.color = theme.preeditTextColor
        bgPaint.color = theme.candidateBackground
        dividerPaint.color = theme.borderColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val text = displayText ?: return

        // 绘制背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 绘制编码文本（垂直居中）
        val baseline = height / 2f + textPaint.textSize / 3f
        canvas.drawText(text, dp2px(PADDING_H_DP.toFloat()), baseline, textPaint)

        // 绘制底部分隔线
        canvas.drawLine(
            0f, height.toFloat(),
            width.toFloat(), height.toFloat(),
            dividerPaint
        )
    }

    // ===== 单位转换工具 =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
