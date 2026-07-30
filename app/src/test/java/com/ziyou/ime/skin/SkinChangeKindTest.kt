package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinBackgroundSpec
import com.ziyou.ime.core.skin.SkinColorScheme
import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.core.skin.SkinDimens
import com.ziyou.ime.core.skin.SkinLayer
import com.ziyou.ime.core.skin.SkinResolver
import com.ziyou.ime.core.skin.SkinTypography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SkinManager.changeKind] 变更类型判定测试。
 *
 * STYLE_ONLY = 仅样式字段（颜色/字号/圆角等）变化且背景/字体/布局尺寸不变；
 * 保守策略：换皮肤、背景节点、布局类尺寸变化一律 FULL。
 */
class SkinChangeKindTest {

    private fun theme(
        layer: SkinLayer? = null,
        skinId: String = SkinDefaults.ID_LIGHT
    ): SkinTheme {
        val spec = SkinDefaults.builtinSpec(skinId)!!
        return SkinTheme(SkinResolver.resolve(spec, layer))
    }

    @Test
    fun oldSnapshotAbsent_isFull() {
        assertEquals(SkinManager.SkinChangeKind.FULL,
            SkinManager.changeKind(null, theme()))
    }

    @Test
    fun colorOnlyChange_isStyleOnly() {
        val old = theme()
        val new = theme(SkinLayer(colorsLight = SkinColorScheme(
            keyBackground = 0xFF112233.toInt())))
        assertEquals(SkinManager.SkinChangeKind.STYLE_ONLY,
            SkinManager.changeKind(old, new))
    }

    @Test
    fun textSizeAndCornerRadiusChange_isStyleOnly() {
        val old = theme()
        val new = theme(SkinLayer(
            dimens = SkinDimens(keyCornerRadiusDp = 20f),
            typography = SkinTypography(keyTextSizeSp = 24f)
        ))
        assertEquals(SkinManager.SkinChangeKind.STYLE_ONLY,
            SkinManager.changeKind(old, new))
    }

    @Test
    fun layoutDimensChange_isFull() {
        val old = theme()
        val gapChanged = theme(SkinLayer(dimens = SkinDimens(keyGapDp = 10f)))
        assertEquals(SkinManager.SkinChangeKind.FULL,
            SkinManager.changeKind(old, gapChanged))

        val heightChanged = theme(SkinLayer(dimens = SkinDimens(keyHeightScale = 1.2f)))
        assertEquals(SkinManager.SkinChangeKind.FULL,
            SkinManager.changeKind(old, heightChanged))
    }

    @Test
    fun backgroundNodeChange_isFull() {
        val old = theme()
        val imageAdded = theme(SkinLayer(
            background = SkinBackgroundSpec(image = "images/bg.png")))
        assertEquals(SkinManager.SkinChangeKind.FULL,
            SkinManager.changeKind(old, imageAdded))
    }

    @Test
    fun differentSkinId_isFull() {
        assertEquals(SkinManager.SkinChangeKind.FULL,
            SkinManager.changeKind(theme(), theme(skinId = SkinDefaults.ID_MATERIAL)))
    }
}
