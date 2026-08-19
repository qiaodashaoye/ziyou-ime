package com.ziyou.ime.core.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [SkinColor] 单元测试：色值解析 / 编码回转 / 混色 / alpha 缩放。
 */
class SkinColorTest {

    @Test
    fun parse_sixDigit_fillsOpaqueAlpha() {
        assertEquals(0xFFF5F5F5.toInt(), SkinColor.parse("#F5F5F5"))
        assertEquals(0xFF000000.toInt(), SkinColor.parse("#000000"))
    }

    @Test
    fun parse_eightDigit_keepsAlpha() {
        assertEquals(0x22000000, SkinColor.parse("#22000000"))
        assertEquals(0x80FF0000.toInt(), SkinColor.parse("#80FF0000"))
    }

    @Test
    fun parse_invalid_throws() {
        assertThrows(IllegalArgumentException::class.java) { SkinColor.parse("F5F5F5") }
        assertThrows(IllegalArgumentException::class.java) { SkinColor.parse("#F5F5") }
        assertThrows(IllegalArgumentException::class.java) { SkinColor.parse("#GGGGGG") }
        assertThrows(IllegalArgumentException::class.java) { SkinColor.parse("#F5F5F5F5F5") }
    }

    @Test
    fun tryParse_invalid_returnsNull() {
        assertNull(SkinColor.tryParse(null))
        assertNull(SkinColor.tryParse(""))
        assertNull(SkinColor.tryParse("red"))
        assertEquals(0xFF212121.toInt(), SkinColor.tryParse("#212121"))
    }

    @Test
    fun toHex_roundTrip() {
        for (hex in listOf("#FFF5F5F5", "#22000000", "#80FF00FF")) {
            assertEquals(hex, SkinColor.toHex(SkinColor.parse(hex)))
        }
    }

    @Test
    fun blend_matchesLegacyBlendColor() {
        // 迁移前 BaseKeyboardView.blendColor 的语义：RGB 按比例线性混合，结果不透明
        val blended = SkinColor.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f)
        assertEquals(0xFF7F7F7F.toInt(), blended)
        // ratio=0 → 完全取 color1
        assertEquals(
            0xFF102030.toInt(),
            SkinColor.blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f)
        )
    }

    @Test
    fun scaleAlpha_scalesOnlyAlphaChannel() {
        assertEquals(0x7FFFFFFF, SkinColor.scaleAlpha(0xFFFFFFFF.toInt(), 0.5f))
        // factor 越界钳制
        assertEquals(0xFF123456.toInt(), SkinColor.scaleAlpha(0xFF123456.toInt(), 2f))
        assertEquals(0x00123456, SkinColor.scaleAlpha(0xFF123456.toInt(), -1f))
    }
}
