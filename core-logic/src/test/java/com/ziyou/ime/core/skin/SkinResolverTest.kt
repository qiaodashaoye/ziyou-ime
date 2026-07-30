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

    /** 内置三皮肤解析结果应与迁移前 ThemeManager 色值逐一一致（视觉零回归锚点）。 */
    @Test
    fun resolve_builtinSkins_matchLegacyThemeColors() {
        val light = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_LIGHT)!!)
        assertEquals(SkinColor.parse("#F5F5F5"), light.keyboardBackground)
        assertEquals(SkinColor.parse("#1976D2"), light.candidateHighlightColor)
        assertFalse(light.isDark)

        val dark = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_DARK)!!)
        assertEquals(SkinColor.parse("#303030"), dark.keyboardBackground)
        assertEquals(SkinColor.parse("#64B5F6"), dark.candidateHighlightColor)
        assertTrue(dark.isDark)

        val material = SkinResolver.resolve(SkinDefaults.builtinSpec(SkinDefaults.ID_MATERIAL)!!)
        assertEquals(SkinColor.parse("#E3F2FD"), material.keyboardBackground)
        assertEquals(SkinColor.parse("#FFC107"), material.candidateHighlightColor)
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
