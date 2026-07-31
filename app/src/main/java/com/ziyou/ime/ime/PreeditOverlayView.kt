package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.ziyou.ime.core.skin.SkinColor
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinTheme

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
        /** 编码区字体默认大小（sp，皮肤可覆盖） */
        private const val TEXT_SIZE_SP = 12f
        /** 水平内边距（dp） */
        private const val PADDING_H_DP = 12
        /** 默认视图高度（dp）：仅保留 12sp 文本上下各约 2dp 的紧凑内边距，
         *  避免编码区自身留白过大、与下方候选词区产生视觉间隙；
         *  对模块内开放供 [CandidateToolbarView] 计算候选区总高 */
        internal const val VIEW_HEIGHT_DP = 18
    }

    /** 当前显示的编码文本 */
    private var displayText: String? = null

    /** 当前编码字号（sp，随皮肤更新） */
    private var textSizeSp = TEXT_SIZE_SP

    /**
     * 全局缩放因子（悬浮模式用）：同步缩小视图高度与编码字号，
     * 与键盘/候选视图的 scaleFactor 保持一致。默认 1.0，停靠模式零影响。
     */
    var scaleFactor: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                textPaint.textSize = sp2px(textSizeSp)
                requestLayout()
                invalidate()
            }
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(TEXT_SIZE_SP)
        color = Color.parseColor("#666666")
        typeface = Typeface.DEFAULT
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    init {
        // 构造期即接当前皮肤（快照命中 O(1)），避免首帧硬编码灰打底；
        // Service 层切皮肤时仍会再次下发 applySkin
        applySkin(SkinManager.getCurrentSkin(context))
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
     * 应用皮肤，与候选词视图和键盘视图保持视觉一致（由 Service 层调用）。
     * 背景色按皮肤整体透明度调制，与下方候选词区保持同一背景视觉连续。
     */
    fun applySkin(skin: SkinTheme) {
        textPaint.color = skin.preeditTextColor
        textPaint.typeface = skin.textTypeface
        textSizeSp = skin.preeditTextSizeSp
        textPaint.textSize = sp2px(textSizeSp)
        bgPaint.color = SkinColor.scaleAlpha(skin.candidateBackground, skin.backgroundAlpha)
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

    // ===== 单位转换工具（已叠加缩放因子，悬浮模式下尺寸统一缩放） =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density * scaleFactor

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity * scaleFactor
}
