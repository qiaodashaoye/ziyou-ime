package com.ziyou.ime.ime

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 待提交图片跳板。
 *
 * [ImagePickerActivity] 选好图并复制到本地后写入 [pending]；输入法在重新获得输入焦点
 * （`onStartInputView`）时取出并经 `commitContent` 提交给编辑器，随后清空。
 *
 * 采用“复制到本地 + 桥接待提交”而非在 Activity 内直接回传 InputConnection：
 * 选择器打开期间输入法窗口失焦、`InputConnection` 失效，返回原编辑器后才有可用连接。
 */
object ImageCommitBridge {

    /** 待发送图片共享缓存子目录（cacheDir 下，FileProvider 已暴露，选图/AI 答案转图共用） */
    const val CACHE_DIR_NAME = "ime_images"

    /** 待提交图片：本地文件绝对路径 + MIME 类型。 */
    data class PendingImage(val path: String, val mime: String)

    @Volatile
    var pending: PendingImage? = null

    /** 取出并清空待提交图片（一次性消费）。 */
    fun take(): PendingImage? {
        val p = pending
        pending = null
        return p
    }
}

/**
 * 图片选择中转 Activity（透明、无界面）。
 *
 * 输入法进程本身没有 Activity，无法直接使用系统图片选择器；本 Activity 作为跳板：
 * 1. 拉起系统内容选择器（GetContent 以 image 类型过滤，走 SAF / 相册，免存储权限）选图；
 * 2. 把所选图片字节复制到本应用 `cache/ime_images/`（供 FileProvider 暴露为 content:// URI）；
 * 3. 结果写入 [ImageCommitBridge]，输入法重新获得焦点时提交给编辑器。
 */
class ImagePickerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ImagePickerActivity"
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePicked(uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            pickImage.launch("image/*")
        } catch (e: Exception) {
            Log.e(TAG, "拉起图片选择器失败: ${e.message}", e)
            finish()
        }
    }

    /** 将所选图片复制到本地缓存并登记到 [ImageCommitBridge]。 */
    private fun handlePicked(uri: Uri) {
        try {
            val resolver = contentResolver
            val mime = resolver.getType(uri) ?: "image/*"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            val dir = File(cacheDir, ImageCommitBridge.CACHE_DIR_NAME).apply { mkdirs() }
            // 清理历史文件，避免缓存无限增长
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "pick_${System.currentTimeMillis()}.$ext")
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.w(TAG, "无法打开所选图片输入流")
                return
            }
            ImageCommitBridge.pending = ImageCommitBridge.PendingImage(file.absolutePath, mime)
            Log.d(TAG, "已复制待发送图片: ${file.absolutePath} ($mime)")
        } catch (e: Exception) {
            Log.e(TAG, "处理所选图片异常: ${e.message}", e)
        }
    }
}
