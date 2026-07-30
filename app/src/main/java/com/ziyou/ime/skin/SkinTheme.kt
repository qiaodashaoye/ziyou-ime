package com.ziyou.ime.skin

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.ziyou.ime.core.skin.ResolvedSkin
import com.ziyou.ime.core.skin.SkinKeyStyle
import com.ziyou.ime.core.skin.SkinShadowSpec
import java.io.File

/**
 * 皮肤运行时快照（视图消费的唯一模型，取代旧 KeyboardTheme）。
 *
 * 不可变：所有解析、合并（UserOverride > skin.json > 默认值链）、资源加载均已在
 * 快照构建期（[SkinManager]）完成，绘制帧内零解析、零 IO。颜色字段名与旧
 * KeyboardTheme 逐一对应，尺寸为 dp / sp 语义（物理像素转换留给视图层的
 * dp2px×scaleFactor，悬浮缩放自动生效）。
 *
 * [backgroundBitmap] / [typeface] 由 [SkinAssetCache] 持有并复用，本类仅持引用。
 */
class SkinTheme(
    val resolved: ResolvedSkin,
    val backgroundBitmap: Bitmap? = null,
    val typeface: Typeface? = null
) {
    val id: String get() = resolved.id
    val name: String get() = resolved.name
    val isDark: Boolean get() = resolved.isDark

    // ===== 颜色（与旧 KeyboardTheme 字段一一对应）=====
    val keyboardBackground: Int get() = resolved.keyboardBackground
    val keyBackground: Int get() = resolved.keyBackground
    val keyTextColor: Int get() = resolved.keyTextColor
    val keyPressedBackground: Int get() = resolved.keyPressedBackground
    val candidateBackground: Int get() = resolved.candidateBackground
    val candidateTextColor: Int get() = resolved.candidateTextColor
    val candidateHighlightColor: Int get() = resolved.candidateHighlightColor
    val preeditTextColor: Int get() = resolved.preeditTextColor
    val borderColor: Int get() = resolved.borderColor

    // ===== 新增维度（解析期已落定）=====
    /** 功能键底色（原 BaseKeyboardView 混色派生已前置到解析期） */
    val funcKeyBackground: Int get() = resolved.funcKeyBackground
    val keyShadowColor: Int get() = resolved.keyShadowColor
    val keyCornerRadiusDp: Float get() = resolved.keyCornerRadiusDp
    val keyGapDp: Float get() = resolved.keyGapDp
    val keyboardPaddingDp: Float get() = resolved.keyboardPaddingDp
    val keyHeightScale: Float get() = resolved.keyHeightScale
    val keyBorderWidthDp: Float get() = resolved.keyBorderWidthDp
    val keyTextSizeSp: Float get() = resolved.keyTextSizeSp
    val funcTextSizeSp: Float get() = resolved.funcTextSizeSp
    val candidateTextSizeSp: Float get() = resolved.candidateTextSizeSp
    val preeditTextSizeSp: Float get() = resolved.preeditTextSizeSp
    val keyTextBold: Boolean get() = resolved.keyTextBold
    val keyStyle: SkinKeyStyle get() = resolved.keyStyle

    /** 按键阴影参数；null = 关闭 */
    val keyShadow: SkinShadowSpec? get() = resolved.keyShadow

    /** 键面整体透明度（背景图透出 / 悬浮半透明场景），1.0 = 完全不透明 */
    val backgroundAlpha: Float get() = resolved.backgroundAlpha

    /** 普通按键文字字体（bold 变体已合成） */
    val keyTypeface: Typeface
        get() = when {
            typeface != null && keyTextBold -> Typeface.create(typeface, Typeface.BOLD)
            typeface != null -> typeface
            keyTextBold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }

    /** 候选/编码等正文字体（不加粗） */
    val textTypeface: Typeface get() = typeface ?: Typeface.DEFAULT

    /**
     * 构建键盘整体背景 Drawable（背景图 + 压暗遮罩）。
     * 无背景图时返回 null（调用方回退纯色背景）。由 Service 层设置在输入视图
     * 根容器上，各 View 不感知背景图。
     */
    fun createBackgroundDrawable(): Drawable? {
        val bitmap = backgroundBitmap ?: return null
        return SkinBackgroundDrawable(
            bitmap = bitmap,
            scaleMode = resolved.backgroundScaleMode,
            dimAmount = resolved.backgroundDim
        )
    }
}

/** 已安装皮肤的列表条目（设置页 / 管理页展示用）。 */
data class SkinInfo(
    val id: String,
    val name: String,
    val author: String?,
    val version: String,
    val isBuiltin: Boolean,
    /** 安装目录（内置皮肤为 null） */
    val installDir: File?
) {
    /** 包内预览图（存在时优先于现渲预览） */
    val previewFile: File?
        get() = installDir?.resolve("preview.png")?.takeIf { it.isFile }
}
