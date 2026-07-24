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
 * 显示规范：始终可见并绘制背景（即使无编码文本），与下方候选词区共享同一背景、
 * 不绘制分割线，使二者在视觉上连续为一个整体。
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
        /** 默认视图高度（dp），紧凑显示以减少垂直空间占用 */
        private const val VIEW_HEIGHT_DP = 24
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

    /**
     * 设置编码区显示文本。
     *
     * 显示规范：视图始终可见（保持背景与候选词区连续），
     * 传入 null 或空字符串时清空文本仅绘制背景，有效文本时绘制文本。
     */
    fun setText(text: String?) {
        val normalized = text?.takeIf { it.isNotEmpty() }
        if (displayText == normalized) return
        displayText = normalized
        invalidate()
    }

    /**
     * 应用主题，与候选词视图和键盘视图保持视觉一致（由 Service 层调用）
     */
    fun applyTheme(theme: KeyboardTheme) {
        textPaint.color = theme.preeditTextColor
        bgPaint.color = theme.candidateBackground
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        // 始终绘制背景（即使无编码文本），与下方候选词区共享同一背景，视觉连续为整体
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 无编码文本时仅保留背景，不绘制文本、不绘制分割线
        val text = displayText ?: return

        // 绘制编码文本（垂直居中）
        val baseline = height / 2f + textPaint.textSize / 3f
        canvas.drawText(text, dp2px(PADDING_H_DP.toFloat()), baseline, textPaint)
    }

    // ===== 单位转换工具 =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
