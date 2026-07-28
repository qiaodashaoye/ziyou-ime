package com.ziyou.ime.ime

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * 图片保存到系统相册的统一出口（Pictures/字由输入法/）。
 *
 * 基于 API 29+ 的 MediaStore RELATIVE_PATH + IS_PENDING 机制，免存储权限；
 * Android 10 以下需要 WRITE_EXTERNAL_STORAGE 运行时权限，而输入法窗口无法
 * 发起权限请求，故由 [isSupported] 直接挡掉。涂鸦/AI 面板的「保存」路径
 * 与技能 image.saveToGallery（SkillRuntime）共用本实现。
 *
 * 含磁盘 IO，须在后台线程调用。
 */
object GalleryImageSaver {

    /** 相册子目录名（Pictures 下） */
    private const val ALBUM_DIR = "字由输入法"

    /** 是否支持免权限保存到相册（API 29+ MediaStore RELATIVE_PATH） */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 将 PNG 字节写入系统相册。
     *
     * @param namePrefix 文件名前缀（自动追加时间戳与 .png 后缀）
     * @return 是否保存成功（系统版本不支持 / 写入失败返回 false）
     */
    fun savePng(context: Context, bytes: ByteArray, namePrefix: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "${namePrefix}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use { it.write(bytes); true } ?: false
            if (written) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                // 输出流打开失败：删除残留的 pending 记录
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } catch (e: Exception) {
            // 写入半途失败：删除残留的 pending 记录
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
