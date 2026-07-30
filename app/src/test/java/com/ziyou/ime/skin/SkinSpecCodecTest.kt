package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinBackgroundScaleMode
import com.ziyou.ime.core.skin.SkinBackgroundSpec
import com.ziyou.ime.core.skin.SkinColor
import com.ziyou.ime.core.skin.SkinColorScheme
import com.ziyou.ime.core.skin.SkinDarkMode
import com.ziyou.ime.core.skin.SkinDimens
import com.ziyou.ime.core.skin.SkinEffects
import com.ziyou.ime.core.skin.SkinKeyStyle
import com.ziyou.ime.core.skin.SkinLayer
import com.ziyou.ime.core.skin.SkinShadowSpec
import com.ziyou.ime.core.skin.SkinTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkinSpecCodec] 单元测试：skin.json 解码、错误路径、覆盖层编码/解码回转。
 * 依赖真实 org.json（testImplementation libs.json）。
 */
class SkinSpecCodecTest {

    private val fullJson = """
        {
          "specVersion": 1,
          "meta": {
            "id": "com.test.ocean",
            "name": "海洋",
            "author": "tester",
            "version": "1.2.0",
            "darkMode": "both"
          },
          "colors": {
            "light": { "keyboardBackground": "#F5F5F5", "keyTextColor": "#212121" },
            "dark": { "keyboardBackground": "#101010" }
          },
          "dimens": { "keyCornerRadiusDp": 12.0, "keyGapDp": 4.5 },
          "typography": { "keyTextSizeSp": 20.0, "keyTextBold": true },
          "effects": {
            "keyStyle": "outline",
            "keyShadow": { "enabled": false },
            "backgroundAlpha": 0.85
          },
          "background": {
            "image": "images/bg.png",
            "imageDark": "images/bg_dark.png",
            "scaleMode": "tile",
            "dimAmount": 0.3
          }
        }
    """.trimIndent()

    @Test
    fun decodeSpec_fullDocument() {
        val spec = SkinSpecCodec.decodeSpec(fullJson)
        assertEquals(1, spec.specVersion)
        assertEquals("com.test.ocean", spec.meta.id)
        assertEquals("海洋", spec.meta.name)
        assertEquals("tester", spec.meta.author)
        assertEquals(SkinDarkMode.BOTH, spec.meta.darkMode)
        assertEquals(SkinColor.parse("#F5F5F5"), spec.layer.colorsLight?.keyboardBackground)
        assertEquals(SkinColor.parse("#101010"), spec.layer.colorsDark?.keyboardBackground)
        assertNull(spec.layer.colorsLight?.borderColor) // 未声明字段保持 null
        assertEquals(12f, spec.layer.dimens?.keyCornerRadiusDp)
        assertEquals(4.5f, spec.layer.dimens?.keyGapDp)
        assertEquals(20f, spec.layer.typography?.keyTextSizeSp)
        assertEquals(true, spec.layer.typography?.keyTextBold)
        assertEquals(SkinKeyStyle.OUTLINE, spec.layer.effects?.keyStyle)
        assertEquals(false, spec.layer.effects?.keyShadow?.enabled)
        assertEquals(0.85f, spec.layer.effects?.backgroundAlpha)
        assertEquals("images/bg.png", spec.layer.background?.image)
        assertEquals(SkinBackgroundScaleMode.TILE, spec.layer.background?.scaleMode)
        assertEquals(0.3f, spec.layer.background?.dimAmount)
    }

    @Test
    fun decodeSpec_minimalDocument() {
        val spec = SkinSpecCodec.decodeSpec(
            """{ "specVersion": 1, "meta": { "id": "com.test.min", "name": "Min" } }""")
        assertEquals(SkinDarkMode.LIGHT, spec.meta.darkMode)
        assertTrue(spec.layer.isEmpty())
    }

    @Test
    fun decodeSpec_errors() {
        // 非法 JSON
        assertThrows(IllegalArgumentException::class.java) {
            SkinSpecCodec.decodeSpec("not json")
        }
        // 缺 meta
        assertThrows(IllegalArgumentException::class.java) {
            SkinSpecCodec.decodeSpec("""{ "specVersion": 1 }""")
        }
        // 色值非法（错误信息带字段定位）
        val colorError = assertThrows(IllegalArgumentException::class.java) {
            SkinSpecCodec.decodeSpec("""
                { "specVersion": 1, "meta": { "id": "com.test.bad", "name": "Bad" },
                  "colors": { "light": { "keyTextColor": "red" } } }
            """.trimIndent())
        }
        assertTrue(colorError.message!!.contains("colors.light.keyTextColor"))
        // 未知枚举
        assertThrows(IllegalArgumentException::class.java) {
            SkinSpecCodec.decodeSpec("""
                { "specVersion": 1, "meta": { "id": "com.test.bad", "name": "Bad" },
                  "effects": { "keyStyle": "neon" } }
            """.trimIndent())
        }
        // 校验失败（越界值经 SkinSpecValidator 拦截）
        assertThrows(IllegalArgumentException::class.java) {
            SkinSpecCodec.decodeSpec("""
                { "specVersion": 1, "meta": { "id": "com.test.bad", "name": "Bad" },
                  "dimens": { "keyCornerRadiusDp": 999 } }
            """.trimIndent())
        }
    }

    @Test
    fun encodeLayer_roundTrip() {
        val layer = SkinLayer(
            colorsLight = SkinColorScheme(
                keyBackground = SkinColor.parse("#80FFFFFF"),
                candidateHighlightColor = SkinColor.parse("#1976D2")
            ),
            colorsDark = SkinColorScheme(keyboardBackground = SkinColor.parse("#101010")),
            dimens = SkinDimens(keyCornerRadiusDp = 16f, keyHeightScale = 1.1f),
            typography = SkinTypography(candidateTextSizeSp = 18f, keyTextBold = false),
            effects = SkinEffects(
                keyStyle = SkinKeyStyle.FLAT,
                keyShadow = SkinShadowSpec(enabled = true, radiusDp = 2f, dxDp = 0.5f, dyDp = 1.5f),
                backgroundAlpha = 0.6f
            ),
            background = SkinBackgroundSpec(
                image = "custom_bg.png",
                scaleMode = SkinBackgroundScaleMode.CENTER_CROP,
                dimAmount = 0.2f
            )
        )
        val decoded = SkinSpecCodec.decodeLayerString(SkinSpecCodec.encodeLayer(layer))
        assertEquals(layer, decoded)
    }

    @Test
    fun encodeLayer_sparse_keepsUnsetFieldsNull() {
        val layer = SkinLayer(dimens = SkinDimens(keyGapDp = 6f))
        val decoded = SkinSpecCodec.decodeLayerString(SkinSpecCodec.encodeLayer(layer))
        assertEquals(6f, decoded.dimens?.keyGapDp)
        assertNull(decoded.dimens?.keyCornerRadiusDp)
        assertNull(decoded.colorsLight)
        assertNull(decoded.effects)
        assertNull(decoded.background)
    }

    /** 空 background 节点（用户"清除背景图"语义）编码后必须保留节点本身。 */
    @Test
    fun encodeLayer_emptyBackgroundNode_preserved() {
        val layer = SkinLayer(background = SkinBackgroundSpec())
        val decoded = SkinSpecCodec.decodeLayerString(SkinSpecCodec.encodeLayer(layer))
        assertEquals(SkinBackgroundSpec(), decoded.background)
    }
}
