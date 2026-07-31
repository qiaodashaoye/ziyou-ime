package com.ziyou.ime.core.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkinResolver] 单元测试：默认值链、覆盖优先级、深浅色变体、派生色与范围钳制。
 */
class SkinResolverTest {

    /** 空规格（只有 meta）：全部字段应落到 SkinDefaults，等价迁移前 Light 视觉。 */
    @Test
    fun resolve_emptySpec_fallsBackToDefaults() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.empty", name = "Empty")
        )
        val resolved = SkinResolver.resolve(spec)

        assertEquals(SkinDefaults.LIGHT_COLORS.keyboardBackground!!, resolved.keyboardBackground)
        assertEquals(SkinDefaults.LIGHT_COLORS.keyTextColor!!, resolved.keyTextColor)
        assertEquals(SkinDefaults.KEY_CORNER_RADIUS_DP, resolved.keyCornerRadiusDp, 0f)
        assertEquals(SkinDefaults.KEY_GAP_DP, resolved.keyGapDp, 0f)
        assertEquals(SkinDefaults.KEY_TEXT_SIZE_SP, resolved.keyTextSizeSp, 0f)
        assertEquals(SkinDefaults.PREEDIT_TEXT_SIZE_SP, resolved.preeditTextSizeSp, 0f)
        assertEquals(SkinKeyStyle.FILLED, resolved.keyStyle)
        assertEquals(SkinDefaults.BACKGROUND_ALPHA, resolved.backgroundAlpha, 0f)
        assertEquals(SkinDefaults.DEFAULT_SHADOW, resolved.keyShadow)
        assertNull(resolved.backgroundImage)
        assertFalse(resolved.isDark)
        // funcKey 缺省按现行混色规则派生
        assertEquals(
            SkinColor.blend(
                SkinDefaults.LIGHT_COLORS.keyBackground!!,
                SkinDefaults.LIGHT_COLORS.borderColor!!,
                SkinDefaults.FUNC_KEY_BLEND_RATIO
            ),
            resolved.funcKeyBackground
        )
    }

    /** 内置三皮肤解析结果应与预设色值逐一一致（视觉回归锚点）。 */
    @Test
    fun resolve_builtinSkins_matchLegacyThemeColors() {
        val light = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_LIGHT)!!)
        assertEquals(SkinColor.parse("#F5F5F5"), light.keyboardBackground)
        assertEquals(SkinColor.parse("#1976D2"), light.candidateHighlightColor)
        assertFalse(light.isDark)

        // 云雾拟态皮肤（builtin.yunwu）：darkMode=both，跟随系统深浅色切变体
        val yunwuLight = SkinResolver.resolve(
            SkinDefaults.builtinSpec(SkinDefaults.ID_YUNWU)!!, systemDark = false
        )
        assertFalse(yunwuLight.isDark)
        assertEquals("云雾拟态", yunwuLight.name)
        assertEquals(SkinColor.parse("#E8E9ED"), yunwuLight.keyboardBackground)
        assertEquals(SkinColor.parse("#F5637F"), yunwuLight.candidateHighlightColor)
        assertEquals(16f, yunwuLight.keyCornerRadiusDp, 0f)
        assertTrue(yunwuLight.keyTextBold)

        val yunwuDark = SkinResolver.resolve(
            SkinDefaults.builtinSpec(SkinDefaults.ID_YUNWU)!!, systemDark = true
        )
        assertTrue(yunwuDark.isDark)
        assertEquals(SkinColor.parse("#26272C"), yunwuDark.keyboardBackground)
        assertEquals(SkinColor.parse("#F5738C"), yunwuDark.candidateHighlightColor)

        val material = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_MATERIAL)!!)
        assertEquals(SkinColor.parse("#E3F2FD"), material.keyboardBackground)
        assertEquals(SkinColor.parse("#FFC107"), material.candidateHighlightColor)
    }

    /** 悬浮立体皮肤（builtin.float3d）：解析结果应与设计规格逐一一致（视觉回归锚点）。 */
    @Test
    fun resolve_float3dSkin_matchesDesignSpec() {
        val light = SkinResolver.resolve(
            SkinDefaults.builtinSpec(SkinDefaults.ID_FLOAT3D)!!, systemDark = false
        )
        assertFalse(light.isDark)
        assertEquals("悬浮立体", light.name)
        assertEquals(SkinColor.parse("#E4E6ED"), light.keyboardBackground)
        assertEquals(SkinColor.parse("#FDFDFF"), light.keyBackground)
        assertEquals(SkinColor.parse("#C9CCD6"), light.funcKeyBackground)
        assertEquals(SkinColor.parse("#3D6BFF"), light.candidateHighlightColor)
        assertEquals(SkinColor.parse("#2E9AA0B3"), light.keyShadowColor)
        // 一体化不变量：候选区 = 工具栏 = 键盘底板同色（无输入态接缝/显隐闪变）
        assertEquals(light.keyboardBackground, light.candidateBackground)
        assertEquals(light.keyboardBackground, light.toolbarBackground)
        assertEquals(12f, light.keyCornerRadiusDp, 0f)
        assertEquals(7f, light.keyGapDp, 0f)
        assertEquals(1.05f, light.keyHeightScale, 0f)
        assertFalse(light.keyTextBold)
        // 弥散下投影：悬浮感核心参数
        assertEquals(
            SkinShadowSpec(enabled = true, radiusDp = 6f, dxDp = 0f, dyDp = 3f),
            light.keyShadow
        )

        val dark = SkinResolver.resolve(
            SkinDefaults.builtinSpec(SkinDefaults.ID_FLOAT3D)!!, systemDark = true
        )
        assertTrue(dark.isDark)
        assertEquals(SkinColor.parse("#1F2126"), dark.keyboardBackground)
        assertEquals(SkinColor.parse("#5C82FF"), dark.candidateHighlightColor)
        assertEquals(SkinColor.parse("#66000000"), dark.keyShadowColor)
        // 深色变体同样保持三区同色的一体化规则
        assertEquals(dark.keyboardBackground, dark.candidateBackground)
        assertEquals(dark.keyboardBackground, dark.toolbarBackground)
    }

    /** 工具栏缺省值链：颜色从候选区配色派生，样式落到现行视觉（零回归锚点）。 */
    @Test
    fun resolve_toolbarDefaults_deriveFromCandidateColors() {
        val resolved = SkinResolver.resolve(
            SkinSpec(specVersion = 1, meta = SkinMeta(id = "test.tb", name = "Tb"))
        )
        assertEquals(resolved.candidateBackground, resolved.toolbarBackground)
        assertEquals(
            SkinColor.blend(
                resolved.toolbarBackground, resolved.borderColor,
                SkinDefaults.TOOLBAR_PILL_BLEND_RATIO
            ),
            resolved.toolbarButtonBackground
        )
        assertEquals(resolved.candidateTextColor, resolved.toolbarTextColor)
        assertEquals(
            resolved.funcTextSizeSp + SkinDefaults.TOOLBAR_TEXT_DELTA_SP,
            resolved.toolbarTextSizeSp, 0f
        )
        assertEquals(SkinDefaults.TOOLBAR_CAPSULE_RADIUS, resolved.toolbarButtonCornerRadiusDp, 0f)
        assertEquals(SkinDefaults.TOOLBAR_BUTTON_SPACING_DP, resolved.toolbarButtonSpacingDp, 0f)
        assertEquals(0f, resolved.toolbarButtonBorderWidthDp, 0f)
        assertFalse(resolved.toolbarButtonShadow)
        assertTrue(resolved.toolbarTextBold)
        assertTrue(resolved.toolbarShowDivider)
    }

    /** 悬浮立体的一体化工具栏规格 + 覆盖层对 toolbar 节点的优先级。 */
    @Test
    fun resolve_toolbarSpecAndOverride() {
        val f3 = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_FLOAT3D)!!)
        // 一体化：工具栏与键盘底板同色，按钮按悬浮键面规格绘制
        assertEquals(f3.keyboardBackground, f3.toolbarBackground)
        assertEquals(f3.keyBackground, f3.toolbarButtonBackground)
        assertEquals(12f, f3.toolbarButtonCornerRadiusDp, 0f)
        assertTrue(f3.toolbarButtonShadow)
        assertFalse(f3.toolbarTextBold)
        assertFalse(f3.toolbarShowDivider)

        val override = SkinLayer(
            toolbar = SkinToolbarSpec(buttonCornerRadiusDp = 8f, showDivider = true)
        )
        val merged = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_FLOAT3D)!!, override)
        assertEquals(8f, merged.toolbarButtonCornerRadiusDp, 0f)      // 覆盖 > 规格
        assertTrue(merged.toolbarShowDivider)                            // 覆盖 > 规格
        assertTrue(merged.toolbarButtonShadow)                           // 未覆盖字段沿用规格
    }

    /** 覆盖层优先于规格层，规格层优先于默认值。 */
    @Test
    fun resolve_overrideBeatsSpecBeatsDefaults() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.skin", name = "Test"),
            layer = SkinLayer(
                colorsLight = SkinColorScheme(keyTextColor = 0xFF111111.toInt()),
                dimens = SkinDimens(keyCornerRadiusDp = 10f, keyGapDp = 6f)
            )
        )
        val override = SkinLayer(
            colorsLight = SkinColorScheme(keyTextColor = 0xFF222222.toInt()),
            dimens = SkinDimens(keyCornerRadiusDp = 16f)
        )
        val resolved = SkinResolver.resolve(spec, override)

        assertEquals(0xFF222222.toInt(), resolved.keyTextColor)      // 覆盖 > 规格
        assertEquals(16f, resolved.keyCornerRadiusDp, 0f)                // 覆盖 > 规格
        assertEquals(6f, resolved.keyGapDp, 0f)                          // 规格 > 默认
        assertEquals(SkinDefaults.KEYBOARD_PADDING_DP, resolved.keyboardPaddingDp, 0f) // 默认兜底
    }

    /** darkMode=both：跟随 systemDark 选变体；深色字段缺失回退浅色配色。 */
    @Test
    fun resolve_bothDarkMode_followsSystemDark() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.both", name = "Both", darkMode = SkinDarkMode.BOTH),
            layer = SkinLayer(
                colorsLight = SkinColorScheme(keyboardBackground = 0xFFEEEEEE.toInt()),
                colorsDark = SkinColorScheme(keyboardBackground = 0xFF111111.toInt()),
                background = SkinBackgroundSpec(image = "images/bg.png", imageDark = "images/bg_dark.png")
            )
        )
        val light = SkinResolver.resolve(spec, systemDark = false)
        assertFalse(light.isDark)
        assertEquals(0xFFEEEEEE.toInt(), light.keyboardBackground)
        assertEquals("images/bg.png", light.backgroundImage)

        val dark = SkinResolver.resolve(spec, systemDark = true)
        assertTrue(dark.isDark)
        assertEquals(0xFF111111.toInt(), dark.keyboardBackground)
        assertEquals("images/bg_dark.png", dark.backgroundImage)
    }

    /** darkMode=light 的皮肤不受 systemDark 影响。 */
    @Test
    fun resolve_lightOnlySkin_ignoresSystemDark() {
        val spec = SkinDefaults.builtinSpec(SkinDefaults.ID_LIGHT)!!
        val resolved = SkinResolver.resolve(spec, systemDark = true)
        assertFalse(resolved.isDark)
        assertEquals(SkinColor.parse("#F5F5F5"), resolved.keyboardBackground)
    }

    /** 阴影 enabled=false 归一为 null（关闭）；显式 funcKey 优先于派生。 */
    @Test
    fun resolve_shadowDisabled_andExplicitFuncKey() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.fx", name = "Fx"),
            layer = SkinLayer(
                colorsLight = SkinColorScheme(funcKeyBackground = 0xFFABCDEF.toInt()),
                effects = SkinEffects(keyShadow = SkinShadowSpec(enabled = false))
            )
        )
        val resolved = SkinResolver.resolve(spec)
        assertNull(resolved.keyShadow)
        assertEquals(0xFFABCDEF.toInt(), resolved.funcKeyBackground)
    }

    /** 越界数值被防御性钳制到合法范围。 */
    @Test
    fun resolve_outOfRangeValues_clamped() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.clamp", name = "Clamp"),
            layer = SkinLayer(
                dimens = SkinDimens(keyCornerRadiusDp = 99f, keyHeightScale = 0.1f),
                typography = SkinTypography(keyTextSizeSp = 100f),
                effects = SkinEffects(backgroundAlpha = 0f)
            )
        )
        val resolved = SkinResolver.resolve(spec)
        assertEquals(SkinSpecValidator.CORNER_RADIUS_RANGE.endInclusive, resolved.keyCornerRadiusDp, 0f)
        assertEquals(SkinSpecValidator.KEY_HEIGHT_SCALE_RANGE.start, resolved.keyHeightScale, 0f)
        assertEquals(SkinSpecValidator.TEXT_SIZE_RANGE.endInclusive, resolved.keyTextSizeSp, 0f)
        assertEquals(SkinSpecValidator.BACKGROUND_ALPHA_RANGE.start, resolved.backgroundAlpha, 0f)
    }

    /** 覆盖层声明 background 节点即整体接管（可清除规格层背景图）。 */
    @Test
    fun resolve_overrideBackgroundNode_takesOver() {
        val spec = SkinSpec(
            specVersion = 1,
            meta = SkinMeta(id = "test.bg", name = "Bg"),
            layer = SkinLayer(background = SkinBackgroundSpec(image = "images/bg.png"))
        )
        // 覆盖层声明空 background = 清除背景图
        val resolved = SkinResolver.resolve(spec, SkinLayer(background = SkinBackgroundSpec()))
        assertNull(resolved.backgroundImage)
    }
}
