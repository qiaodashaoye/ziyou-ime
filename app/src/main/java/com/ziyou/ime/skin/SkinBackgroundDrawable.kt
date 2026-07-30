package com.ziyou.ime.skin

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.ziyou.ime.core.skin.SkinBackgroundScaleMode

/**
 * 皮肤键盘背景 Drawable：按 [SkinBackgroundScaleMode] 绘制背景图 + 压暗遮罩。
 *
 * BitmapDrawable 原生不支持 centerCrop / tile 的组合语义，这里用 Matrix / BitmapShader
 * 在 draw 期按 bounds 精确适配（bounds 变化仅重算矩阵，无位图重解码）。
 * 位图由 [SkinAssetCache] 持有并复用，本类不负责回收。
 */
class SkinBackgroundDrawable(
    private val bitmap: Bitmap,
    private val scaleMode: SkinBackgroundScaleMode,
    dimAmount: Float
) : Drawable() {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply {
        color = ((dimAmount.coerceIn(0f, 1f) * 255).toInt() shl 24)
        style = Paint.Style.FILL
    }
    private val matrix = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        when (scaleMode) {
            SkinBackgroundScaleMode.CENTER_CROP -> {
                // 取覆盖满 bounds 的最大缩放，居中裁掉溢出部分
                val scale = maxOf(
                    bounds.width().toFloat() / bitmap.width,
                    bounds.height().toFloat() / bitmap.height
                )
                val dx = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
                val dy = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
                bitmapPaint.shader = BitmapShader(
                    bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                ).apply { setLocalMatrix(matrix) }
            }
            SkinBackgroundScaleMode.FIT_XY -> {
                matrix.setScale(
                    bounds.width().toFloat() / bitmap.width,
                    bounds.height().toFloat() / bitmap.height
                )
                matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                bitmapPaint.shader = BitmapShader(
                    bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                ).apply { setLocalMatrix(matrix) }
            }
            SkinBackgroundScaleMode.TILE -> {
                matrix.setTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                bitmapPaint.shader = BitmapShader(
                    bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
                ).apply { setLocalMatrix(matrix) }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, bitmapPaint)
        if (dimPaint.color != 0) {
            canvas.drawRect(b, dimPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
