package com.ziyou.ime.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ziyou.ime.core.skin.SkinKeyStyle

/**
 * 皮肤预览渲染器：给定 [SkinTheme] 离屏绘制一张迷你键盘示意图
 * （候选栏 + 三行 QWERTY 简化布局），不实例化真实键盘视图。
 *
 * 用途：皮肤管理页网格缩略图（包内 preview.png 缺失时现渲）、
 * 自定义编辑器实时预览（纯内存合成，不触碰真实键盘与磁盘）。
 * 纯 CPU 绘制，可在任意线程调用。
 */
object SkinPreviewRenderer {

    private val previewRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /**
     * 渲染皮肤预览图。
     * @param widthPx / heightPx 目标位图尺寸（像素）
     */
    fun render(context: Context, skin: SkinTheme, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        // 预览内的 dp 换算：以预览宽 = 360dp 的虚拟密度等比缩放，
        // 保证圆角/间距等 dp 参数在缩略图上比例正确
        val density = w / 360f
        val alpha = skin.backgroundAlpha

        // 背景：背景图（含遮罩）或键盘底色
        val bgDrawable = skin.createBackgroundDrawable()
        if (bgDrawable != null) {
            bgDrawable.setBounds(0, 0, bitmap.width, bitmap.height)
            bgDrawable.draw(canvas)
        }
        val boardPaint = fillPaint(applyAlpha(skin.keyboardBackground, alpha))
        if (bgDrawable == null) {
            canvas.drawRect(0f, 0f, w, h, boardPaint)
        }

        // 候选栏（顶部约 22% 高）
        val candidateHeight = h * 0.22f
        canvas.drawRect(0f, 0f, w, candidateHeight,
            fillPaint(applyAlpha(skin.candidateBackground, alpha)))
        val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = candidateHeight * 0.5f
            typeface = skin.textTypeface
        }
        var candidateX = 12f * density
        val candidateBaseline = candidateHeight / 2f + candidatePaint.textSize / 3f
        for ((index, word) in listOf("字由", "输入法", "皮肤", "预览").withIndex()) {
            candidatePaint.color =
                if (index == 0) skin.candidateHighlightColor else skin.candidateTextColor
            canvas.drawText(word, candidateX, candidateBaseline, candidatePaint)
            candidateX += candidatePaint.measureText(word) + 16f * density
        }

        // 键区（背景图之上仅当无背景图时补键盘底色）
        if (bgDrawable != null) {
            canvas.drawRect(0f, candidateHeight, w, h, boardPaint)
        }

        // 三行迷你按键
        val padding = skin.keyboardPaddingDp * density
        val gap = skin.keyGapDp * density
        val radius = skin.keyCornerRadiusDp * density
        val borderWidth = skin.keyBorderWidthDp * density
        val rowsTop = candidateHeight + padding
        val rowHeight = (h - rowsTop - padding - gap * (previewRows.size - 1)) / previewRows.size

        val keyPaint = fillPaint(applyAlpha(skin.keyBackground, alpha))
        val shadowPaint = fillPaint(skin.keyShadowColor).apply {
            // 与视图层同步：radiusDp > 0 时弥散投影（软件画布完整支持 maskFilter）
            skin.keyShadow?.let { s ->
                if (s.radiusDp > 0f) {
                    maskFilter = BlurMaskFilter(s.radiusDp * density, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = skin.borderColor
            strokeWidth = if (borderWidth > 0f) borderWidth else 1f * density
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.keyTextColor
            textAlign = Paint.Align.CENTER
            textSize = (skin.keyTextSizeSp / 18f) * rowHeight * 0.45f
            typeface = skin.keyTypeface
        }

        for ((rowIndex, letters) in previewRows.withIndex()) {
            val count = letters.length
            val unit = (w - padding * 2 - gap * (count - 1)) / count
            val top = rowsTop + rowIndex * (rowHeight + gap)
            for (col in letters.indices) {
                val left = padding + col * (unit + gap)
                val rect = RectF(left, top, left + unit, top + rowHeight)
                // 阴影（FILLED 风格才有）
                if (skin.keyStyle == SkinKeyStyle.FILLED) {
                    val s = skin.keyShadow
                    if (s != null) {
                        val shadowRect = RectF(rect).apply { offset(s.dxDp * density, s.dyDp * density) }
                        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
                    }
                }
                when (skin.keyStyle) {
                    SkinKeyStyle.FILLED -> {
                        canvas.drawRoundRect(rect, radius, radius, keyPaint)
                        if (borderWidth > 0f) canvas.drawRoundRect(rect, radius, radius, strokePaint)
                    }
                    SkinKeyStyle.OUTLINE -> canvas.drawRoundRect(rect, radius, radius, strokePaint)
                    SkinKeyStyle.FLAT -> Unit // 无键面
                }
                val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(letters[col].uppercase(), rect.centerX(), baseline, textPaint)
            }
        }
        return bitmap
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    /** 键面色按皮肤整体透明度调制（与视图层同一规则）。 */
    private fun applyAlpha(color: Int, factor: Float): Int =
        com.ziyou.ime.core.skin.SkinColor.scaleAlpha(color, factor)
}
