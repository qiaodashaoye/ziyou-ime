package com.ziyou.ime.core.skin

/**
 * 皮肤色值工具（纯 Kotlin，不依赖 android.graphics.Color）。
 *
 * 负责皮肤配置中 `#RRGGBB` / `#AARRGGBB` 字符串与 ARGB Int 的双向转换，
 * 以及功能键混色等派生色计算。全部为纯函数，可独立 JVM 单测。
 */
object SkinColor {

    /**
     * 解析 `#RRGGBB` 或 `#AARRGGBB` 色值字符串为 ARGB Int。
     * 6 位形式补全不透明 alpha（0xFF）。
     * @throws IllegalArgumentException 格式非法
     */
    fun parse(value: String): Int {
        require(value.startsWith("#")) { "色值必须以 # 开头: $value" }
        val hex = value.substring(1)
        require(hex.length == 6 || hex.length == 8) { "色值长度必须为 6 或 8 位十六进制: $value" }
        val parsed = hex.toLongOrNull(16)
            ?: throw IllegalArgumentException("色值不是合法十六进制: $value")
        return if (hex.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
    }

    /** 宽容解析：非法输入返回 null。 */
    fun tryParse(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return try {
            parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** ARGB Int → `#AARRGGBB` 字符串（编码皮肤配置 / 用户覆盖时使用）。 */
    fun toHex(color: Int): String =
        "#%08X".format(color.toLong() and 0xFFFFFFFFL)

    /**
     * 按比例混合两种颜色的 RGB 通道（ratio 越大越偏向 color2），结果不透明。
     * 迁移自 BaseKeyboardView.blendColor，语义保持一致（功能键底色派生）。
     */
    fun blend(color1: Int, color2: Int, ratio: Float): Int {
        val r = (red(color1) * (1 - ratio) + red(color2) * ratio).toInt()
        val g = (green(color1) * (1 - ratio) + green(color2) * ratio).toInt()
        val b = (blue(color1) * (1 - ratio) + blue(color2) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 把颜色的 alpha 通道按 [factor]（0..1）缩放，RGB 不变。 */
    fun scaleAlpha(color: Int, factor: Float): Int {
        val clamped = factor.coerceIn(0f, 1f)
        val a = (alpha(color) * clamped).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF
}
