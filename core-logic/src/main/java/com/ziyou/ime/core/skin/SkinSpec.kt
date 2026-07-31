package com.ziyou.ime.core.skin

/**
 * 皮肤规格数据模型（specVersion = 1）。
 *
 * 对应 `.zyskin` 包内 `skin.json` 的结构：除 [SkinMeta] 外所有字段均可缺省
 * （null 表示"未声明"，由 [SkinResolver] 按 UserOverride > skin.json > [SkinDefaults]
 * 的优先级链落定）。色值在解码期已解析为 ARGB Int，本层不含字符串色值。
 *
 * 纯 Kotlin 数据类，无 Android 依赖，可独立 JVM 单测。
 */

/** 皮肤深浅色支持声明。 */
enum class SkinDarkMode(val id: String) {
    /** 仅浅色配色 */
    LIGHT("light"),

    /** 仅深色配色 */
    DARK("dark"),

    /** 双套配色，跟随系统深浅色切换 */
    BOTH("both");

    companion object {
        fun fromId(id: String?): SkinDarkMode? = entries.firstOrNull { it.id == id }
    }
}

/** 按键渲染风格。 */
enum class SkinKeyStyle(val id: String) {
    /** 填充键面（现状默认） */
    FILLED("filled"),

    /** 描边键面（无填充） */
    OUTLINE("outline"),

    /** 无键面纯文字（Gboard 无边框风格），仅按下态绘制高亮 */
    FLAT("flat");

    companion object {
        fun fromId(id: String?): SkinKeyStyle? = entries.firstOrNull { it.id == id }
    }
}

/** 背景图缩放模式。 */
enum class SkinBackgroundScaleMode(val id: String) {
    CENTER_CROP("centerCrop"),
    FIT_XY("fitXY"),
    TILE("tile");

    companion object {
        fun fromId(id: String?): SkinBackgroundScaleMode? = entries.firstOrNull { it.id == id }
    }
}

/** 按键阴影参数（dp 语义，绘制期经 dp2px×scaleFactor 转物理像素）。 */
data class SkinShadowSpec(
    val enabled: Boolean = true,
    val radiusDp: Float = 0f,
    val dxDp: Float = 0f,
    val dyDp: Float = 1f
)

/** 皮肤元信息（skin.json 必填部分）。 */
data class SkinMeta(
    /** 全局唯一 id（即安装目录名），格式 `[a-z0-9_.]{3,64}` */
    val id: String,
    /** 展示名（内置皮肤同时作为 LevelEngine 解锁表键名） */
    val name: String,
    val author: String? = null,
    val version: String = "1.0.0",
    /** 皮肤要求的最低规格版本（向后兼容声明） */
    val minSpecVersion: Int = 1,
    val darkMode: SkinDarkMode = SkinDarkMode.LIGHT
)

/** 单套配色方案（全部字段可缺省；色值为已解析的 ARGB Int）。 */
data class SkinColorScheme(
    val keyboardBackground: Int? = null,
    val keyBackground: Int? = null,
    val keyTextColor: Int? = null,
    val keyPressedBackground: Int? = null,
    /** 功能键底色；缺省时按 keyBackground 与 borderColor 混色派生（沿用现行规则） */
    val funcKeyBackground: Int? = null,
    val candidateBackground: Int? = null,
    val candidateTextColor: Int? = null,
    val candidateHighlightColor: Int? = null,
    val preeditTextColor: Int? = null,
    val borderColor: Int? = null,
    val keyShadowColor: Int? = null,
    /** 工具栏背景色；缺省沿用 candidateBackground（与候选区连续，现行视觉） */
    val toolbarBackground: Int? = null,
    /** 工具栏按钮底色；缺省按 toolbarBackground 与 borderColor 混色派生（现行胶囊规则） */
    val toolbarButtonBackground: Int? = null,
    /** 工具栏文字色；缺省沿用 candidateTextColor */
    val toolbarTextColor: Int? = null
) {
    /** 是否所有字段均未声明（用于 darkMode=both 的完整性校验）。 */
    fun isEmpty(): Boolean =
        keyboardBackground == null && keyBackground == null && keyTextColor == null &&
            keyPressedBackground == null && funcKeyBackground == null &&
            candidateBackground == null && candidateTextColor == null &&
            candidateHighlightColor == null && preeditTextColor == null &&
            borderColor == null && keyShadowColor == null &&
            toolbarBackground == null && toolbarButtonBackground == null &&
            toolbarTextColor == null
}

/** 尺寸参数（dp 语义）。 */
data class SkinDimens(
    val keyCornerRadiusDp: Float? = null,
    val keyGapDp: Float? = null,
    val keyboardPaddingDp: Float? = null,
    /** 键高倍率（乘在各键盘自有 keyHeightMultiplier 之上） */
    val keyHeightScale: Float? = null,
    /** 按键描边宽度；0 = 不描边 */
    val keyBorderWidthDp: Float? = null
)

/** 字体参数（sp 语义）。 */
data class SkinTypography(
    val keyTextSizeSp: Float? = null,
    val funcTextSizeSp: Float? = null,
    val candidateTextSizeSp: Float? = null,
    val preeditTextSizeSp: Float? = null,
    /** 包内字体相对路径（如 `fonts/custom.ttf`）；null = 系统默认字体 */
    val fontFamily: String? = null,
    val keyTextBold: Boolean? = null
)

/** 视觉效果参数。 */
data class SkinEffects(
    val keyStyle: SkinKeyStyle? = null,
    val keyShadow: SkinShadowSpec? = null,
    /** 键面整体透明度（0.3–1.0），悬浮/游戏场景与背景图透出用 */
    val backgroundAlpha: Float? = null
)

/**
 * 工具栏（候选区功能按钮栏）样式参数（全部可缺省）。
 *
 * 颜色类字段位于 [SkinColorScheme]（随深浅色变体切换），本节点只承载
 * 形状/字体/布局类样式；缺省值链保证空节点 ≈ 现行工具栏视觉（零回归）。
 */
data class SkinToolbarSpec(
    /** 按钮圆角（dp）；null = 胶囊全圆角（现行视觉），声明后与按键圆角同语义 */
    val buttonCornerRadiusDp: Float? = null,
    /** 按钮是否绘制投影（沿用 effects.keyShadow 参数）；缺省 false */
    val buttonShadow: Boolean? = null,
    /** 按钮描边宽度（dp，描边色取 borderColor）；缺省 0 = 不描边 */
    val buttonBorderWidthDp: Float? = null,
    /** 按钮在单元格内的左右留白（dp，即按钮间距的一半）；缺省 5dp（现行） */
    val buttonSpacingDp: Float? = null,
    /** 文字字号（sp）；缺省 = funcTextSizeSp + 2（现行增量映射） */
    val textSizeSp: Float? = null,
    /** 文字粗体；缺省 true（现行） */
    val textBold: Boolean? = null,
    /** 是否绘制底部与键盘区的分隔细线；缺省 true；一体化皮肤设 false */
    val showDivider: Boolean? = null
)

/** 键盘整体背景（图片）参数。 */
data class SkinBackgroundSpec(
    /** 包内背景图相对路径（浅色变体） */
    val image: String? = null,
    /** 深色变体背景图；缺省时深色下沿用 [image] */
    val imageDark: String? = null,
    val scaleMode: SkinBackgroundScaleMode? = null,
    /** 背景图上的压暗遮罩（0–0.7），保证按键可读性 */
    val dimAmount: Float? = null
)

/**
 * 皮肤样式层：skin.json 的样式部分，同时也是用户自定义覆盖（UserOverride）的载体
 * ——覆盖即一个稀疏的 SkinLayer，合并优先级见 [SkinResolver]。
 */
data class SkinLayer(
    val colorsLight: SkinColorScheme? = null,
    val colorsDark: SkinColorScheme? = null,
    val dimens: SkinDimens? = null,
    val typography: SkinTypography? = null,
    val effects: SkinEffects? = null,
    val background: SkinBackgroundSpec? = null,
    val toolbar: SkinToolbarSpec? = null
) {
    /** 按深浅色变体选取配色（深色缺失时回退浅色）。 */
    fun colorsFor(isDark: Boolean): SkinColorScheme? =
        if (isDark) colorsDark ?: colorsLight else colorsLight

    /** 是否所有字段均未声明（空覆盖等价于无覆盖）。 */
    fun isEmpty(): Boolean =
        colorsLight == null && colorsDark == null && dimens == null &&
            typography == null && effects == null && background == null &&
            toolbar == null

    companion object {
        val EMPTY = SkinLayer()
    }
}

/** 完整皮肤规格（skin.json 的解码产物）。 */
data class SkinSpec(
    val specVersion: Int,
    val meta: SkinMeta,
    val layer: SkinLayer = SkinLayer.EMPTY
)

/**
 * 解析结果：全部字段落定的纯数据（[SkinResolver] 产物）。
 * 颜色为 ARGB Int，尺寸为 dp / sp 语义（物理像素转换留给视图层的 dp2px×scaleFactor）。
 */
data class ResolvedSkin(
    val id: String,
    val name: String,
    val isDark: Boolean,
    // ===== 颜色 =====
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyTextColor: Int,
    val keyPressedBackground: Int,
    val funcKeyBackground: Int,
    val candidateBackground: Int,
    val candidateTextColor: Int,
    val candidateHighlightColor: Int,
    val preeditTextColor: Int,
    val borderColor: Int,
    val keyShadowColor: Int,
    // ===== 尺寸 =====
    val keyCornerRadiusDp: Float,
    val keyGapDp: Float,
    val keyboardPaddingDp: Float,
    val keyHeightScale: Float,
    val keyBorderWidthDp: Float,
    // ===== 字体 =====
    val keyTextSizeSp: Float,
    val funcTextSizeSp: Float,
    val candidateTextSizeSp: Float,
    val preeditTextSizeSp: Float,
    /** 包内字体相对路径；null = 系统默认字体 */
    val fontFamily: String?,
    val keyTextBold: Boolean,
    // ===== 效果 =====
    val keyStyle: SkinKeyStyle,
    /** null = 关闭阴影 */
    val keyShadow: SkinShadowSpec?,
    val backgroundAlpha: Float,
    // ===== 背景图 =====
    /** 包内背景图相对路径（已按深浅色变体选定）；null = 纯色背景 */
    val backgroundImage: String?,
    val backgroundScaleMode: SkinBackgroundScaleMode,
    val backgroundDim: Float,
    // ===== 工具栏（全部已落定，缺省时由解析期从候选区/键盘配色派生）=====
    val toolbarBackground: Int,
    val toolbarButtonBackground: Int,
    val toolbarTextColor: Int,
    /** 按钮圆角（dp）；负值 = 胶囊全圆角（高度一半，现行视觉） */
    val toolbarButtonCornerRadiusDp: Float,
    val toolbarButtonShadow: Boolean,
    val toolbarButtonBorderWidthDp: Float,
    val toolbarButtonSpacingDp: Float,
    val toolbarTextSizeSp: Float,
    val toolbarTextBold: Boolean,
    val toolbarShowDivider: Boolean
)
