package com.ziyou.ime.ime

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ziyou.ime.skin.SkinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片发送与保存辅助类（从 ZiYouInputMethodService 拆分）
 *
 * 统一处理 AI 答案文本渲染 / 涂鸦快照导出 → commitContent 发送或相册保存的通用流程，
 * 收敛 Service 内四个结构高度相似的图片方法为两个通用入口。
 *
 * @param service 输入法服务引用（Toast / FileProvider / packageName）
 * @param scope 服务协程作用域（与 Service 生命周期对齐）
 * @param inputLogic 输入逻辑控制器（图片能力检测 + commitContent 提交）
 * @param onDoodleSent 涂鸦发送成功后的面板清理回调（关闭涂鸦面板）
 * @param onDoodleSaved 涂鸦保存成功后的面板清理回调（关闭涂鸦面板）
 */
class ImageCommitHelper(
    private val service: ZiYouInputMethodService,
    private val scope: CoroutineScope,
    private val inputLogic: InputLogicController,
    private val onDoodleSent: () -> Unit,
    private val onDoodleSaved: () -> Unit,
) {
    companion object {
        private const val TAG = "ImageCommitHelper"
    }

    /**
     * AI 面板「发图/存图」统一入口：按当前编辑器图片能力实时路由——
     * 可收图走 commitContent 直发，否则保存到相册。
     */
    fun submitAnswer(content: CharSequence) {
        if (inputLogic.acceptsImageContent()) {
            sendTextAsImage(content, "AI 答案图片")
        } else {
            saveTextAsImage(content, "ziyou_ai")
        }
    }

    /**
     * 将文本内容渲染为主题卡片图并经 commitContent 发送到当前输入框。
     * 渲染/PNG 压缩在后台线程执行，提交与 Toast 反馈回主线程。
     */
    private fun sendTextAsImage(content: CharSequence, description: String) {
        if (!inputLogic.acceptsImageContent()) {
            Toast.makeText(service, "当前输入框不支持发送图片", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(service, "正在生成图片…", Toast.LENGTH_SHORT).show()
        val skin = SkinManager.getCurrentSkin(service)
        scope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    TextImageRenderer.renderToPng(service.applicationContext, content, skin)
                }
                val uri = FileProvider.getUriForFile(
                    service, "${service.packageName}.imecontent", file)
                val ok = inputLogic.commitImageToEditor(uri, "image/png", description)
                if (!ok) {
                    Toast.makeText(service,
                        "发送图片失败或当前输入框不支持", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "文本转图片发送失败: ${e.message}", e)
                Toast.makeText(service, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将文本内容渲染为主题卡片图并保存到系统相册（编辑器不收图片时的兜底出口）。
     * Android 10 以下 MediaStore 免权限写入不可用，直接提示。
     */
    private fun saveTextAsImage(content: CharSequence, albumPrefix: String) {
        if (!GalleryImageSaver.isSupported) {
            Toast.makeText(service, "保存到相册需要 Android 10 及以上系统", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(service, "正在生成图片…", Toast.LENGTH_SHORT).show()
        val skin = SkinManager.getCurrentSkin(service)
        scope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    TextImageRenderer.renderToPng(service.applicationContext, content, skin)
                }
                val ok = withContext(Dispatchers.IO) {
                    GalleryImageSaver.savePng(service.applicationContext, file.readBytes(), albumPrefix)
                }
                Toast.makeText(service,
                    if (ok) "已保存到相册" else "保存到相册失败", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "文本转图片保存失败: ${e.message}", e)
                Toast.makeText(service, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 涂鸦面板「发送/保存」统一入口：按编辑器图片能力路由。
     * 快照所有权在本方法，导出完成后 recycle；发送/保存成功后经回调关闭面板。
     */
    fun submitDoodle(snapshot: Bitmap) {
        if (inputLogic.acceptsImageContent()) {
            sendDoodleAsImage(snapshot)
        } else {
            saveDoodleAsImage(snapshot)
        }
    }

    /**
     * 将涂鸦快照导出为 PNG 并经 commitContent 发送到当前输入框。
     * 发送失败时降级转存相册。
     */
    private fun sendDoodleAsImage(snapshot: Bitmap) {
        if (!inputLogic.acceptsImageContent()) {
            // 按钮态滞后兜底：点击瞬间编辑器已不收图则转存相册
            saveDoodleAsImage(snapshot)
            return
        }
        Toast.makeText(service, "正在生成图片…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    try {
                        DoodleImageExporter.exportToPng(service.applicationContext, snapshot)
                    } finally {
                        snapshot.recycle()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    service, "${service.packageName}.imecontent", file)
                val ok = inputLogic.commitImageToEditor(uri, "image/png", "涂鸦图片")
                if (ok) {
                    onDoodleSent()
                } else {
                    Toast.makeText(service,
                        "发送图片失败或当前输入框不支持", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "涂鸦转图片失败: ${e.message}", e)
                Toast.makeText(service, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将涂鸦快照导出为 PNG 并保存到系统相册（编辑器不收图片时的兜底出口）。
     */
    private fun saveDoodleAsImage(snapshot: Bitmap) {
        if (!GalleryImageSaver.isSupported) {
            snapshot.recycle()
            Toast.makeText(service, "保存到相册需要 Android 10 及以上系统", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(service, "正在生成图片…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    try {
                        DoodleImageExporter.exportToPng(service.applicationContext, snapshot)
                    } finally {
                        snapshot.recycle()
                    }
                }
                val ok = withContext(Dispatchers.IO) {
                    GalleryImageSaver.savePng(service.applicationContext, file.readBytes(), "ziyou_doodle")
                }
                if (ok) {
                    Toast.makeText(service, "已保存到相册", Toast.LENGTH_SHORT).show()
                    onDoodleSaved()
                } else {
                    Toast.makeText(service, "保存到相册失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "涂鸦存图失败: ${e.message}", e)
                Toast.makeText(service, "图片生成失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
