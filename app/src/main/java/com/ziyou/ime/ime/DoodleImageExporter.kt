package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File

/**
 * 涂鸦 → PNG 导出器（画布键盘「发送」用）。
 *
 * 将 [DoodleCanvasView.snapshot] 的透明底笔迹层合成为不透明白底图片
 * （透明底会被部分接收应用渲染成黑色，与 [TextImageRenderer] 同一经验），
 * 宽度超限时等比降采样，输出写入 cache 下 [ImageCommitBridge.CACHE_DIR_NAME]
 * 目录（FileProvider 已暴露，可直接经 commitContent 提交）。
 *
 * 纯 CPU 绘制 + PNG 压缩，须在后台线程调用；传入的快照由调用方负责 recycle。
 */
object DoodleImageExporter {

    /** 导出图片最大宽度（px），高分屏画布降采样避免产出超大图 */
    private const val MAX_EXPORT_WIDTH = 1080

    /**
     * 合成并导出 PNG 文件。
     *
     * @param snapshot 画布笔迹层快照（透明底，仅笔画）
     * @return 生成的 PNG 文件（位于 FileProvider 已暴露的缓存子目录）
     */
    fun exportToPng(context: Context, snapshot: Bitmap): File {
        // 等比降采样（仅超限时）
        val scale = if (snapshot.width > MAX_EXPORT_WIDTH) {
            MAX_EXPORT_WIDTH.toFloat() / snapshot.width
        } else 1f
        val outWidth = (snapshot.width * scale).toInt().coerceAtLeast(1)
        val outHeight = (snapshot.height * scale).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            // 不透明白底（与画布纸面一致）
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                snapshot,
                Rect(0, 0, snapshot.width, snapshot.height),
                Rect(0, 0, outWidth, outHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )

            // 写入 FileProvider 已暴露的缓存子目录（先清理历史文件，避免缓存累积）
            val dir = File(context.cacheDir, ImageCommitBridge.CACHE_DIR_NAME).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "doodle_${System.currentTimeMillis()}.png")
            file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file
        } finally {
            output.recycle()
        }
    }
}
