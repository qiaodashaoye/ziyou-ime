package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinKeyStyle
import com.ziyou.ime.core.skin.SkinResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 「云朵奶油」皮肤包（skins-dev/com.ziyou.cloudcream.zyskin）的落地校验：
 * 用真实安装流水线 [SkinPackLoader.validateZip] 验证包体可被导入，
 * 并断言解析结果与设计意图一致（配色 / 圆角 / 键距 / 背景图 / 强调色）。
 *
 * 包体为 pack.sh 的产物，未构建时用 assumeTrue 显式标记为 skipped
 * （而非静默通过）；存在时必须通过——防止后续改包体或改校验规则时
 * 静默破坏这个示例皮肤。
 */
class CloudCreamSkinPackTest {

    private val packFile = File(
        "../skins-dev/com.ziyou.cloudcream.zyskin").let { relative ->
        if (relative.isFile) relative else File("skins-dev/com.ziyou.cloudcream.zyskin")
    }

    @Test
    fun cloudCreamPack_installableAndResolvesAsDesigned() {
        assumeTrue("皮肤包未构建（先执行 skins-dev/com.ziyou.cloudcream/pack.sh）",
            packFile.isFile)

        val result = SkinPackLoader.validateZip(packFile)
        assertTrue("皮肤包校验应通过，实际: $result",
            result is SkinPackLoader.ValidateResult.Ok)
        val spec = (result as SkinPackLoader.ValidateResult.Ok).spec

        assertEquals("com.ziyou.cloudcream", spec.meta.id)
        assertEquals("云朵奶油", spec.meta.name)

        val resolved = SkinResolver.resolve(spec)
        // 配色：白键面 + 樱粉强调 + 淡紫灰底
        assertEquals(0xFFFFFFFF.toInt(), resolved.keyBackground)
        assertEquals(0xFFE8879C.toInt(), resolved.candidateHighlightColor)
        assertEquals(0xFFE3E5F1.toInt(), resolved.keyboardBackground)
        // 形态：大圆角 + 宽键距 + 填充键面 + 下投影
        assertEquals(14f, resolved.keyCornerRadiusDp, 0f)
        assertEquals(6f, resolved.keyGapDp, 0f)
        assertEquals(SkinKeyStyle.FILLED, resolved.keyStyle)
        assertEquals(2f, resolved.keyShadow!!.dyDp, 0f)
        // 背景图随包提供
        assertEquals("images/bg.png", resolved.backgroundImage)
    }
}
