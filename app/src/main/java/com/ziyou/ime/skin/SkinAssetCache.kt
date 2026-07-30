package com.ziyou.ime.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import com.ziyou.ime.core.skin.ResolvedSkin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 皮肤资源缓存：背景图（LruCache + 解码期降采样）、字体（进程级复用）、预览图。
 *
 * 内存预算：任意时刻常驻 ≈ 当前皮肤 1 张背景图（解码时按屏宽 inSampleSize
 * 预降采样）+ 1 个 Typeface；预览缓存仅皮肤管理页在前台时增长。
 * 解码失败一律返回 null（调用方回退纯色 / 系统字体），不抛出。
 */
object SkinAssetCache {
    private const val TAG = "SkinAssetCache"

    /**
     * 背景图缓存：当前皮肤 + 编辑器预览合成各 1 张降采样背景图。
     * 8MB 预算足够容纳 1-2 张按屏宽降采样的 ARGB_8888 全屏图。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * 预览图缓存（管理页网格用，约 8 张半屏宽缩略图的预算，
     * 高分辨率屏由 LRU 自行淘汰最旧条目）。
     */
    private val previewCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Typeface 进程级缓存（Typeface 可安全长持有；皮肤字体数量有限，无需 LRU）。
     * IO 协程与主线程（编辑器预览）并发读写，须用并发容器。
     */
    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    /**
     * 加载皮肤背景图（已按屏宽降采样）。
     * @param skinDir 皮肤安装目录（内置皮肤为 null → 无背景图）
     */
    fun loadBackground(context: Context, skinDir: File?, resolved: ResolvedSkin): Bitmap? {
        val relative = resolved.backgroundImage ?: return null
        val file = safeResolve(skinDir, relative) ?: return null
        val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val key = "${resolved.id}|${resolved.isDark}|$relative|$targetWidth"
        bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, targetWidth)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                bitmapCache.put(key, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "背景图解码失败: $file, ${e.message}")
            null
        }
    }

    /** 加载皮肤自定义字体（无声明 / 加载失败返回 null = 系统默认字体）。 */
    fun loadTypeface(skinDir: File?, resolved: ResolvedSkin): Typeface? {
        val relative = resolved.fontFamily ?: return null
        val file = safeResolve(skinDir, relative) ?: return null
        val key = file.absolutePath
        typefaceCache[key]?.let { return it }
        return try {
            Typeface.createFromFile(file)?.also { typefaceCache[key] = it }
        } catch (e: Exception) {
            Log.w(TAG, "字体加载失败: $file, ${e.message}")
            null
        }
    }

    /** 加载皮肤包内预览图（无包内预览返回 null，由调用方现渲）。 */
    fun loadPreview(info: SkinInfo): Bitmap? {
        val file = info.previewFile ?: return null
        previewCache.get(info.id)?.takeIf { !it.isRecycled }?.let { return it }
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { previewCache.put(info.id, it) }
        } catch (e: Exception) {
            Log.w(TAG, "预览图解码失败: $file, ${e.message}")
            null
        }
    }

    /** 皮肤卸载 / 重装时定向失效其全部缓存条目。 */
    fun evict(skinId: String) {
        for (key in bitmapCache.snapshot().keys) {
            if (key.startsWith("$skinId|")) bitmapCache.remove(key)
        }
        previewCache.remove(skinId)
    }

    /** 皮肤管理页退出时收缩预览缓存。 */
    fun trimPreviews() {
        previewCache.evictAll()
    }

    // ===== 内部 =====

    /** 相对路径落盘解析 + 纵深防御（规范化路径必须仍在皮肤目录内）。 */
    private fun safeResolve(skinDir: File?, relative: String): File? {
        if (skinDir == null) return null
        val file = File(skinDir, relative)
        val canonical = try {
            file.canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (!canonical.path.startsWith(skinDir.canonicalFile.path + File.separator)) {
            Log.w(TAG, "皮肤资源路径逃逸已拦截: $relative")
            return null
        }
        return canonical.takeIf { it.isFile }
    }

    /** 2 的幂降采样：解码宽度不超过目标宽度的 2 倍。 */
    private fun calculateInSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        var sampleSize = 1
        while (sourceWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
