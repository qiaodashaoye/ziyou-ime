package com.ziyou.ime.core.skin

/**
 * 皮肤默认值链的最底层 + 内置三皮肤规格。
 *
 * 尺寸/字体默认值与迁移前视图层硬编码常量一一对应（BaseKeyboardView /
 * SimpleCandidatesView / PreeditOverlayView），保证"空皮肤 ≈ 迁移前 Light 主题"，
 * 老视觉零回归；内置皮肤色值迁移自原 ThemeManager 的三套预设。
 */
object SkinDefaults {

    /** 当前应用支持的皮肤规格版本。 */
    const val SPEC_VERSION = 1

    // ===== 内置皮肤 id =====
    const val ID_LIGHT = "builtin.light"
    const val ID_DARK = "builtin.dark"
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

    /** 默认阴影：向下偏移 1dp 的实心投影（迁移前 drawKey 行为）。 */
    val DEFAULT_SHADOW = SkinShadowSpec(enabled = true, radiusDp = 0f, dxDp = 0f, dyDp = 1f)

    // ===== 内置配色（迁移自 ThemeManager 预设，色值逐一对应）=====

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

    val DARK_COLORS = SkinColorScheme(
        keyboardBackground = SkinColor.parse("#303030"),
        keyBackground = SkinColor.parse("#424242"),
        keyTextColor = SkinColor.parse("#EEEEEE"),
        keyPressedBackground = SkinColor.parse("#616161"),
        candidateBackground = SkinColor.parse("#212121"),
        candidateTextColor = SkinColor.parse("#E0E0E0"),
        candidateHighlightColor = SkinColor.parse("#64B5F6"),
        preeditTextColor = SkinColor.parse("#BDBDBD"),
        borderColor = SkinColor.parse("#555555")
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
    // name 沿用原主题名（Light/Dark/Material），与 LevelEngine 皮肤解锁表键名保持一致

    private val lightSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_LIGHT, name = "Light", darkMode = SkinDarkMode.LIGHT),
        layer = SkinLayer(colorsLight = LIGHT_COLORS)
    )

    private val darkSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_DARK, name = "Dark", darkMode = SkinDarkMode.DARK),
        layer = SkinLayer(colorsDark = DARK_COLORS)
    )

    private val materialSpec = SkinSpec(
        specVersion = SPEC_VERSION,
        meta = SkinMeta(id = ID_MATERIAL, name = "Material", darkMode = SkinDarkMode.LIGHT),
        layer = SkinLayer(colorsLight = MATERIAL_COLORS)
    )

    /** 全部内置皮肤规格（设置页展示顺序）。 */
    val builtinSpecs: List<SkinSpec> = listOf(lightSpec, darkSpec, materialSpec)

    fun isBuiltin(id: String): Boolean = builtinSpecs.any { it.meta.id == id }

    fun builtinSpec(id: String): SkinSpec? = builtinSpecs.firstOrNull { it.meta.id == id }

    /** 旧版 ThemeManager 主题名 → 内置皮肤 id 的迁移映射。 */
    fun legacyThemeToSkinId(themeName: String?): String = when (themeName) {
        "Dark" -> ID_DARK
        "Material" -> ID_MATERIAL
        else -> ID_LIGHT
    }
}
