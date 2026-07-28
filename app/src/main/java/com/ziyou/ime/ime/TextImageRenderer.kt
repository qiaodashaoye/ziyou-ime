package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import com.ziyou.ime.config.KeyboardTheme
import java.io.File

/**
 * 文本 → 图片卡片渲染器（AI 答案「发图」用）。
 *
 * 将富文本内容（支持 Spanned，如 [com.ziyou.ime.ai.MarkdownRenderer] 的解析结果，
 * 粗体/列表/代码块样式随 Span 一并绘制）渲染为固定宽度的 PNG 卡片：
 * 顶部主题强调色条 + 正文 + 分隔线 + 品牌页脚，配色全部取自当前 [KeyboardTheme]，
 * 与键盘视觉一致。输出写入 cache 下 [ImeImageCache.CACHE_DIR_NAME] 目录
 * （FileProvider 已暴露该目录，可直接经 commitContent 提交）。
 *
 * 按固定像素宽度渲染（不乘设备密度），保证不同设备产出一致的分享图。
 * 纯 CPU 绘制 + PNG 压缩，须在后台线程调用。
 */
object TextImageRenderer {

    /** 输出图片宽度（px） */
    private const val IMAGE_WIDTH = 840
    /** 四周留白（px） */
    private const val PADDING = 48
    /** 正文字号（px） */
    private const val TEXT_SIZE = 34f
    /** 页脚字号（px） */
    private const val FOOTER_TEXT_SIZE = 24f
    /** 顶部强调色条高度（px） */
    private const val ACCENT_BAR_HEIGHT = 10
    /** 正文最大高度（px），超长答案截断加省略号，防止超大 Bitmap 撑爆内存 */
    private const val MAX_CONTENT_HEIGHT = 6000
    /** 页脚品牌文案 */
    private const val FOOTER_TEXT = "字由输入法 · AI 生成"

    /**
     * 渲染内容为 PNG 卡片文件。
     *
     * @param content 答案富文本（Spanned 样式一并绘制）
     * @param theme 当前键盘主题（背景/文字/强调色取色来源）
     * @return 生成的 PNG 文件（位于 FileProvider 已暴露的缓存子目录）
     */
    fun renderToPng(context: Context, content: CharSequence, theme: KeyboardTheme): File {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.keyTextColor
            textSize = TEXT_SIZE
        }
        val contentWidth = IMAGE_WIDTH - PADDING * 2

        // 正文排版；超高则按可容纳行数截断并追加省略号后重排
        var layout = buildLayout(content, textPaint, contentWidth)
        if (layout.height > MAX_CONTENT_HEIGHT) {
            val lastLine = (layout.getLineForVertical(MAX_CONTENT_HEIGHT) - 1).coerceAtLeast(0)
            val truncated = SpannableStringBuilder(
                content.subSequence(0, layout.getLineEnd(lastLine))).append("…")
            layout = buildLayout(truncated, textPaint, contentWidth)
        }

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.preeditTextColor
            textSize = FOOTER_TEXT_SIZE
        }
        val footerGap = PADDING / 2
        val footerHeight = (footerPaint.descent() - footerPaint.ascent()).toInt()
        val height = ACCENT_BAR_HEIGHT + PADDING + layout.height +
            footerGap + 1 + footerGap + footerHeight + PADDING

        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // 背景整幅填充（不透明，避免部分应用把透明区渲染为黑色）
            canvas.drawColor(theme.keyboardBackground)
            // 顶部强调色条
            val accentPaint = Paint().apply { color = theme.candidateHighlightColor }
            canvas.drawRect(0f, 0f, IMAGE_WIDTH.toFloat(), ACCENT_BAR_HEIGHT.toFloat(), accentPaint)
            // 正文
            canvas.save()
            canvas.translate(PADDING.toFloat(), (ACCENT_BAR_HEIGHT + PADDING).toFloat())
            layout.draw(canvas)
            canvas.restore()
            // 分隔线 + 页脚
            val dividerY = (ACCENT_BAR_HEIGHT + PADDING + layout.height + footerGap).toFloat()
            val dividerPaint = Paint().apply { color = theme.borderColor }
            canvas.drawRect(PADDING.toFloat(), dividerY, (IMAGE_WIDTH - PADDING).toFloat(),
                dividerY + 1, dividerPaint)
            canvas.drawText(FOOTER_TEXT, PADDING.toFloat(),
                dividerY + footerGap - footerPaint.ascent(), footerPaint)

            // 写入 FileProvider 已暴露的缓存子目录（先清理历史文件，避免缓存累积）
            val dir = File(context.cacheDir, ImeImageCache.CACHE_DIR_NAME).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "ai_answer_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(true)
            .build()
}
