package com.ziyou.ime.core.skin

/**
 * 皮肤默认值链的最底层 + 内置三皮肤规格。
 *
 * 尺寸/字体默认值与迁移前视图层硬编码常量一一对应（BaseKeyboardView /
 * SimpleCandidatesView / PreeditOverlayView），保证"空皮肤 ≈ 迁移前 Light 主题"，
 * 老视觉零回归；Light/Material 配色迁移自原 ThemeManager 预设，云雾拟态为全新设计配色。
 */
object SkinDefaults {

    /** 当前应用支持的皮肤规格版本。 */
    const val SPEC_VERSION = 1

    // ===== 内置皮肤 id =====
    const val ID_LIGHT = "builtin.light"
    const val ID_YUNWU = "builtin.yunwu"
    const val ID_FLOAT3D = "builtin.float3d"
    const val ID_MATERIAL = "builtin.material"

    /** 默认皮肤（始终可用的兜底）。 */
    const val DEFAULT_SKIN_ID = ID_LIGHT

    // ===== 尺寸默认值（dp，源自 BaseKeyboardView 迁移前常量）=====
    const val KEY_CORNER_RADIUS_DP = 5f
    const val KEY_GAP_DP = 3f
    const val KEYBOARD_PADDING_DP = 3f
    const val KEY_HEIGHT_SCALE = 1f
    const val KEY_BORDER_WIDTH_DP = 0f

    // ===== 字体默认值（sp，源自各视图迁移前常量）=====
    const val KEY_TEXT_SIZE_SP = 18f
    const val FUNC_TEXT_SIZE_SP = 12f
    const val CANDIDATE_TEXT_SIZE_SP = 16f
    const val PREEDIT_TEXT_SIZE_SP = 12f

    // ===== 效果默认值 =====
    const val BACKGROUND_ALPHA = 1f
    const val BACKGROUND_DIM = 0f

    /** 按键阴影默认色（源自 BaseKeyboardView.keyShadowPaint 迁移前硬编码）。 */
    const val KEY_SHADOW_COLOR = 0x22000000

    /** 功能键底色派生混色比例（沿用 BaseKeyboardView.rebuildPaints 迁移前规则）。 */
    const val FUNC_KEY_BLEND_RATIO = 0.55f

    // ===== 工具栏默认值（源自 CandidateToolbarView 皮肤化前常量，零视觉回归）=====

    /** 工具栏胶囊底色派生混色比（背景色向边框色靠拢，原 PILL_BLEND_RATIO）。 */
    const val TOOLBAR_PILL_BLEND_RATIO = 0.16f

    /** 工具栏文字相对功能键字号的默认增量（sp，Light 基线 12+2=14）。 */
    const val TOOLBAR_TEXT_DELTA_SP = 2f

    /** 工具栏按钮在单元格内的默认左右留白（dp，原 PILL_H_INSET_DP）。 */
    const val TOOLBAR_BUTTON_SPACING_DP = 5f

    /** 工具栏按钮圆角的「胶囊全圆角」哨兵值（解析期缺省时使用）。 */
    const val TOOLBAR_CAPSULE_RADIUS = -1f

    /** 默认阴影：向下偏移 1dp 的实心投影（迁移前 drawKey 行为）。 */
    val DEFAULT_SHADOW = SkinShadowSpec(enabled = true, radiusDp = 0f, dxDp = 0f, dyDp = 1f)

    // ===== 内置配色（Light/Material 迁移自 ThemeManager 预设；云雾拟态为全新配色）=====

    val LIGHT_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#F5F5F5"),
        keyBackground = SkinColor.parse("#FFFFFF"),
        keyTextColor = SkinColor.parse("#212121"),
        keyPressedBackground = SkinColor.parse("#E0E0E0"),
        candidateBackground = SkinColor.parse("#FFFFFF"),
        candidateTextColor = SkinColor.parse("#212121"),
        candidateHighlightColor = SkinColor.parse("#1976D2"),
        preeditTextColor = SkinColor.parse("#424242"),
        borderColor = SkinColor.parse("#BDBDBD")
    )

    /**
     * 云雾拟态风格 · 浅色变体（builtin.yunwu 皮肤，取自参考图）：
     * 浅灰面板 + 近白胶囊键面 + 粉珊瑚强调色，柔和投影。
     */
    val YUNWU_LIGHT_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#E8E9ED"),
        keyBackground = SkinColor.parse("#F8F8FB"),
        keyTextColor = SkinColor.parse("#1C1C21"),
        keyPressedBackground = SkinColor.parse("#DDDEE4"),
        funcKeyBackground = SkinColor.parse("#EDEEF2"),
        candidateBackground = SkinColor.parse("#F1F1F5"),
        candidateTextColor = SkinColor.parse("#1C1C21"),
        candidateHighlightColor = SkinColor.parse("#F5637F"),
        preeditTextColor = SkinColor.parse("#55565E"),
        borderColor = SkinColor.parse("#D9DAE0"),
        keyShadowColor = SkinColor.parse("#33A0A3AD")
    )

    /**
     * 云雾拟态风格 · 深色变体（builtin.yunwu 皮肤深色模式），沿用同一层次关系与
     * 粉珊瑚强调色；同时作为解析链中所有皮肤深色变体缺省字段的兜底配色。
     */
    val YUNWU_DARK_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#26272C"),
        keyBackground = SkinColor.parse("#35363D"),
        keyTextColor = SkinColor.parse("#F2F2F5"),
        keyPressedBackground = SkinColor.parse("#4A4B54"),
        funcKeyBackground = SkinColor.parse("#2D2E34"),
        candidateBackground = SkinColor.parse("#2A2B31"),
        candidateTextColor = SkinColor.parse("#E9E9EE"),
        candidateHighlightColor = SkinColor.parse("#F5738C"),
        preeditTextColor = SkinColor.parse("#B8B9C2"),
        borderColor = SkinColor.parse("#45464F"),
        keyShadowColor = SkinColor.parse("#4D000000")
    )

    /**
     * 悬浮立体风格 · 浅色变体（builtin.float3d 皮肤）：
     * 冷灰底板 + 近白悬浮键面的亮度差营造 3D 受光面，中灰功能键退后一个
     * 层次，宝蓝强调色；半透明冷灰蓝投影是「悬浮」而非「贴片」的关键。
     * 候选区背景与底板同色（候选区 = 工具栏 = 键盘底板），消除输入态接缝
     * 与显隐切换闪变，整面板一体化。
     */
    val FLOAT3D_LIGHT_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#E4E6ED"),
        keyBackground = SkinColor.parse("#FDFDFF"),
        keyTextColor = SkinColor.parse("#2A2C33"),
        keyPressedBackground = SkinColor.parse("#DEE0E8"),
        funcKeyBackground = SkinColor.parse("#C9CCD6"),
        candidateBackground = SkinColor.parse("#E4E6ED"),
        candidateTextColor = SkinColor.parse("#33353C"),
        candidateHighlightColor = SkinColor.parse("#3D6BFF"),
        preeditTextColor = SkinColor.parse("#7A7D8A"),
        borderColor = SkinColor.parse("#D4D7E0"),
        keyShadowColor = SkinColor.parse("#2E9AA0B3")
    )

    /**
     * 悬浮立体风格 · 深色变体：层次关系不变（键面亮于底板、功能键退后、
     * 按下下沉），投影加重补偿暗底对比损失，宝蓝提亮保证强调色可读；
     * 候选区背景同样与底板同色，与浅色变体的一体化规则一致。
     */
    val FLOAT3D_DARK_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#1F2126"),
        keyBackground = SkinColor.parse("#2E3038"),
        keyTextColor = SkinColor.parse("#EDEEF3"),
        keyPressedBackground = SkinColor.parse("#23252C"),
        funcKeyBackground = SkinColor.parse("#262830"),
        candidateBackground = SkinColor.parse("#1F2126"),
        candidateTextColor = SkinColor.parse("#E4E5EB"),
        candidateHighlightColor = SkinColor.parse("#5C82FF"),
        preeditTextColor = SkinColor.parse("#9FA2B0"),
        borderColor = SkinColor.parse("#3A3D47"),
        keyShadowColor = SkinColor.parse("#66000000")
    )

    val MATERIAL_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#E3F2FD"),
        keyBackground = SkinColor.parse("#FFFFFF"),
        keyTextColor = SkinColor.parse("#1565C0"),
        keyPressedBackground = SkinColor.parse("#BBDEFB"),
        candidateBackground = SkinColor.parse("#1976D2"),
        candidateTextColor = SkinColor.parse("#FFFFFF"),
        candidateHighlightColor = SkinColor.parse("#FFC107"),
        preeditTextColor = SkinColor.parse("#0D47A1"),
        borderColor = SkinColor.parse("#90CAF9")
    )

    // ===== 内置皮肤规格 =====
    // name 即设置页展示名，与 LevelEngine 皮肤解锁表键名保持一致

    private val lightSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_LIGHT, name = "Light", darkMode = SkinDarkMode.LIGHT),
        layer = SkinLayer(colorsLight = LIGHT_COLORS)
    )

    // 云雾拟态皮肤：双套配色跟随系统深浅色；
    // 大圆角胶囊键面 + 柔和弥散投影 + 粗体键文字，还原参考图视觉
    private val yunwuSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_YUNWU, name = "云雾拟态", darkMode = SkinDarkMode.BOTH),
        layer = SkinLayer(
            colorsLight = YUNWU_LIGHT_COLORS,
            colorsDark = YUNWU_DARK_COLORS,
            dimens = SkinDimens(
                keyCornerRadiusDp = 16f,
                keyGapDp = 5f,
                keyboardPaddingDp = 6f,
                keyHeightScale = 1f,
                keyBorderWidthDp = 1f
            ),
            typography = SkinTypography(
                keyTextSizeSp = 19f,
                funcTextSizeSp = 13f,
                candidateTextSizeSp = 17f,
                preeditTextSizeSp = 12f,
                keyTextBold = true
            ),
            effects = SkinEffects(
                keyStyle = SkinKeyStyle.FILLED,
                keyShadow = SkinShadowSpec(enabled = true, radiusDp = 4f, dxDp = 0f, dyDp = 2f),
                backgroundAlpha = 1f
            )
        )
    )

    // 悬浮立体皮肤（Lv.3 解锁）：参考图 3D 悬浮键盘风格——
    // 大键距留出投影落脚空间，dy=3dp 弥散下投影（radiusDp 经 BlurMaskFilter 柔化），
    // 按下态切入底板色域形成「按压下陷」深度反转；
    // 工具栏与键盘底板同色、按钮按悬浮键面样式绘制（同圆角 + 投影 + 无分隔线），
    // 与键盘主体一体化
    private val float3dSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_FLOAT3D, name = "悬浮立体", darkMode = SkinDarkMode.BOTH),
        layer = SkinLayer(
            colorsLight = FLOAT3D_LIGHT_COLORS.copy(
                toolbarBackground = SkinColor.parse("#E4E6ED"),
                toolbarButtonBackground = SkinColor.parse("#FDFDFF"),
                toolbarTextColor = SkinColor.parse("#2A2C33")
            ),
            colorsDark = FLOAT3D_DARK_COLORS.copy(
                toolbarBackground = SkinColor.parse("#1F2126"),
                toolbarButtonBackground = SkinColor.parse("#2E3038"),
                toolbarTextColor = SkinColor.parse("#EDEEF3")
            ),
            dimens = SkinDimens(
                keyCornerRadiusDp = 12f,
                keyGapDp = 7f,
                keyboardPaddingDp = 7f,
                keyHeightScale = 1.05f,
                keyBorderWidthDp = 0f
            ),
            typography = SkinTypography(
                keyTextSizeSp = 19f,
                funcTextSizeSp = 13f,
                candidateTextSizeSp = 17f,
                preeditTextSizeSp = 12f,
                keyTextBold = false
            ),
            effects = SkinEffects(
                keyStyle = SkinKeyStyle.FILLED,
                keyShadow = SkinShadowSpec(enabled = true, radiusDp = 6f, dxDp = 0f, dyDp = 3f),
                backgroundAlpha = 1f
            ),
            // 一体化工具栏：按钮按悬浮键面规格绘制（与按键同圆角 + 同投影），
            // 取消分隔线后工具栏背景与键盘底板无缝衔接
            toolbar = SkinToolbarSpec(
                buttonCornerRadiusDp = 12f,
                buttonShadow = true,
                buttonSpacingDp = 4f,
                textBold = false,
                showDivider = false
            )
        )
    )

    private val materialSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_MATERIAL, name = "Material", darkMode = SkinDarkMode.LIGHT),
        layer = SkinLayer(colorsLight = MATERIAL_COLORS)
    )

    /** 全部内置皮肤规格（设置页展示顺序）。 */
    val builtinSpecs: List<SkinSpec> = listOf(lightSpec, yunwuSpec, float3dSpec, materialSpec)

    fun isBuiltin(id: String): Boolean = builtinSpecs.any { it.meta.id == id }

    fun builtinSpec(id: String): SkinSpec? = builtinSpecs.firstOrNull { it.meta.id == id }

    /** 旧版 ThemeManager 主题名 → 内置皮肤 id 的迁移映射（键为历史持久化值，不可改动）。 */
    fun legacyThemeToSkinId(themeName: String?): String = when (themeName) {
        "Dark" -> ID_YUNWU
        "Material" -> ID_MATERIAL
        else -> ID_LIGHT
    }
}
