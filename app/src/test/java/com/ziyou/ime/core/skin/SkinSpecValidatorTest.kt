package com.ziyou.ime.core.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkinSpecValidator] 与 [SkinPackConstraints] 单元测试：
 * 规格校验规则、资源路径安全性、皮肤包条目白名单。
 */
class SkinSpecValidatorTest {

    private fun spec(
        specVersion: Int = 1,
        id: String = "com.test.skin",
        name: String = "Test",
        minSpecVersion: Int = 1,
        darkMode: SkinDarkMode = SkinDarkMode.LIGHT,
        layer: SkinLayer = SkinLayer.EMPTY
    ) = SkinSpec(specVersion, SkinMeta(id, name, minSpecVersion = minSpecVersion, darkMode = darkMode), layer)

    // ===== 规格校验 =====

    @Test
    fun validate_builtinSpecs_allPass() {
        for (builtin in SkinDefaults.builtinSpecs) {
            assertEquals(emptyList<String>(), SkinSpecValidator.validate(builtin))
        }
    }

    @Test
    fun validate_badMeta_rejected() {
        assertTrue(SkinSpecValidator.validate(spec(id = "ab")).isNotEmpty())          // 过短
        assertTrue(SkinSpecValidator.validate(spec(id = "Has.Upper")).isNotEmpty())   // 大写
        assertTrue(SkinSpecValidator.validate(spec(id = "bad/slash")).isNotEmpty())   // 非法字符
        assertTrue(SkinSpecValidator.validate(spec(name = " ")).isNotEmpty())         // 空名
    }

    @Test
    fun validate_versionCompatibility() {
        assertTrue(SkinSpecValidator.validate(spec(specVersion = 0)).isNotEmpty())
        assertTrue(SkinSpecValidator.validate(spec(specVersion = 99)).isNotEmpty())
        assertTrue(SkinSpecValidator.validate(spec(minSpecVersion = 99)).isNotEmpty())
    }

    @Test
    fun validate_bothDarkMode_requiresDarkColors() {
        assertTrue(SkinSpecValidator.validate(spec(darkMode = SkinDarkMode.BOTH)).isNotEmpty())
        // 提供深色配色后通过
        val ok = spec(
            darkMode = SkinDarkMode.BOTH,
            layer = SkinLayer(colorsDark = SkinColorScheme(keyboardBackground = 0xFF111111.toInt()))
        )
        assertEquals(emptyList<String>(), SkinSpecValidator.validate(ok))
    }

    @Test
    fun validate_toolbarOutOfRange_rejectedWithFieldName() {
        val bad = spec(
            layer = SkinLayer(
                toolbar = SkinToolbarSpec(buttonCornerRadiusDp = 99f, textSizeSp = 100f)
            )
        )
        val errors = SkinSpecValidator.validate(bad)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("toolbar.buttonCornerRadiusDp") })
        assertTrue(errors.any { it.contains("toolbar.textSizeSp") })
        // 合法 toolbar 节点通过
        val ok = spec(layer = SkinLayer(toolbar = SkinToolbarSpec(buttonCornerRadiusDp = 12f)))
        assertEquals(emptyList<String>(), SkinSpecValidator.validate(ok))
    }

    @Test
    fun validate_outOfRangeValues_rejectedWithFieldName() {
        val bad = spec(
            layer = SkinLayer(
                dimens = SkinDimens(keyCornerRadiusDp = 99f),
                effects = SkinEffects(backgroundAlpha = 0.1f)
            )
        )
        val errors = SkinSpecValidator.validate(bad)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("keyCornerRadiusDp") })
        assertTrue(errors.any { it.contains("backgroundAlpha") })
    }

    @Test
    fun validate_resourcePaths_checked() {
        val bad = spec(
            layer = SkinLayer(
                typography = SkinTypography(fontFamily = "../evil.ttf"),
                background = SkinBackgroundSpec(image = "images/bg.exe")
            )
        )
        val errors = SkinSpecValidator.validate(bad)
        assertTrue(errors.any { it.contains("fontFamily") })
        assertTrue(errors.any { it.contains("background.image") })
    }

    // ===== 资源路径安全性 =====

    @Test
    fun isSafeResourcePath_rejectsTraversalAndBadExtension() {
        val img = SkinSpecValidator.IMAGE_EXTENSIONS
        assertTrue(SkinSpecValidator.isSafeResourcePath("images/bg.png", img))
        assertFalse(SkinSpecValidator.isSafeResourcePath("../bg.png", img))       // 上跳
        assertFalse(SkinSpecValidator.isSafeResourcePath("/etc/bg.png", img))     // 绝对路径
        assertFalse(SkinSpecValidator.isSafeResourcePath("a\\b.png", img))        // 反斜杠
        assertFalse(SkinSpecValidator.isSafeResourcePath("c:evil.png", img))      // 盘符
        assertFalse(SkinSpecValidator.isSafeResourcePath("images//bg.png", img))  // 空段
        assertFalse(SkinSpecValidator.isSafeResourcePath("images/./bg.png", img)) // 当前段
        assertFalse(SkinSpecValidator.isSafeResourcePath("images/bg.svg", img))   // 白名单外
        assertFalse(SkinSpecValidator.isSafeResourcePath("", img))
    }

    // ===== 皮肤包条目约束 =====

    @Test
    fun packConstraints_entryWhitelist() {
        assertTrue(SkinPackConstraints.isAllowedEntry("skin.json"))
        assertTrue(SkinPackConstraints.isAllowedEntry("preview.png"))
        assertTrue(SkinPackConstraints.isAllowedEntry("images/bg.webp"))
        assertTrue(SkinPackConstraints.isAllowedEntry("fonts/custom.otf"))
        // 可执行/脚本内容一律拒绝
        assertFalse(SkinPackConstraints.isAllowedEntry("script.js"))
        assertFalse(SkinPackConstraints.isAllowedEntry("evil.so"))
        assertFalse(SkinPackConstraints.isAllowedEntry("index.html"))
        // Zip Slip
        assertFalse(SkinPackConstraints.isAllowedEntry("../../etc/passwd.json"))
    }
}
