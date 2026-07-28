package com.ziyou.ime.ime

import android.content.ClipDescription
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import com.ziyou.ime.core.image.ImageSupportLevel
import com.ziyou.ime.core.image.ImageSupportPolicy

/**
 * 当前编辑器图片能力检测（动态检测为主 + 白名单兜底）。
 *
 * 首要依据 [EditorInfoCompat.getContentMimeTypes]：微信等聊天框会声明可接收的
 * image 通配 MIME 类型（API 25+ 原生 contentMimeTypes 字段，API 24 由 androidx
 * 兼容协议从 privateImeOptions 读取，minSdk 24 全区间可用）；未声明时回退
 * [com.ziyou.ime.core.image.ImageCapableApp] 白名单（按 [EditorInfo.packageName] 匹配）。
 *
 * 检测结果驱动涂鸦面板「发送/保存」与 AI 面板「发图/存图」按钮切换，
 * 应用/输入框切换时经 onStartInputView 实时重判（EditorInfo 每次聚焦都会重发，
 * 天然携带最新目标应用信息，无需额外监听）。
 */
object EditorImageSupport {

    /** 检测编辑器图片能力级别（editorInfo 为 null 时视为仅可保存）。 */
    fun detect(editorInfo: EditorInfo?): ImageSupportLevel {
        if (editorInfo == null) return ImageSupportLevel.SAVE_ONLY
        val declaresImage = EditorInfoCompat.getContentMimeTypes(editorInfo)
            .any { mime -> ClipDescription.compareMimeTypes(mime, "image/*") }
        return ImageSupportPolicy.resolve(declaresImage, editorInfo.packageName)
    }
}
