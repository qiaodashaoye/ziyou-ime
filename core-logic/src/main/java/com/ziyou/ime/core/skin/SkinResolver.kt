package com.ziyou.ime.core.skin

/**
 * 皮肤解析器：把稀疏的 [SkinSpec] 与用户覆盖 [SkinLayer] 合并为全字段落定的
 * [ResolvedSkin]。
 *
 * 合并优先级：UserOverride > skin.json > [SkinDefaults]。
 * 全部为纯函数（无 IO / 无 Android 依赖），数值经 [SkinSpecValidator] 范围
 * 防御性钳制，保证任何输入都不产生越界渲染参数。
 */
object SkinResolver {

    /**
     * 解析皮肤。
     *
     * @param spec 基础皮肤规格（skin.json 解码产物或内置规格）
     * @param override 用户自定义覆盖（稀疏层，null / 空层 = 无覆盖）
     * @param systemDark 系统当前是否为深色模式（darkMode=both 的皮肤据此选变体）
     */
    fun resolve(
        spec: SkinSpec,
        override: SkinLayer? = null,
        systemDark: Boolean = false
    ): ResolvedSkin {
        // 变体选取：both 跟随系统，light/dark 固定
        val isDark = when (spec.meta.darkMode) {
            SkinDarkMode.BOTH -> systemDark
            SkinDarkMode.DARK -> true
            SkinDarkMode.LIGHT -> false
        }

        val o = override?.takeIf { !it.isEmpty() }
        val s = spec.layer

        // 配色链：覆盖 > 规格 > 内置默认（深色变体缺失时兜底对应内置配色）
        val oc = o?.colorsFor(isDark)
        val sc = s.colorsFor(isDark)
        val dc = if (isDark) SkinDefaults.YUNWU_DARK_COLORS else SkinDefaults.LIGHT_COLORS

        val keyBackground = oc?.keyBackground ?: sc?.keyBackground ?: dc.keyBackground!!
        val borderColor = oc?.borderColor ?: sc?.borderColor ?: dc.borderColor!!

        val od = o?.dimens
        val sd = s.dimens
        val ot = o?.typography
        val st = s.typography
        val oe = o?.effects
        val se = s.effects
        val ob = o?.background
        val sb = s.background

        // 背景图：覆盖层声明了 background 节点即整体接管（含"清除背景图"语义），
        // 否则取规格层按深浅色变体选定的图
        val backgroundImage = if (ob != null) {
            pickImage(ob, isDark)
        } else {
            pickImage(sb, isDark)
        }

        return ResolvedSkin(
            id = spec.meta.id,
            name = spec.meta.name,
            isDark = isDark,
            keyboardBackground = oc?.keyboardBackground ?: sc?.keyboardBackground
                ?: dc.keyboardBackground!!,
            keyBackground = keyBackground,
            keyTextColor = oc?.keyTextColor ?: sc?.keyTextColor ?: dc.keyTextColor!!,
            keyPressedBackground = oc?.keyPressedBackground ?: sc?.keyPressedBackground
                ?: dc.keyPressedBackground!!,
            // 功能键底色：显式声明优先，缺省按现行混色规则派生
            funcKeyBackground = oc?.funcKeyBackground ?: sc?.funcKeyBackground
                ?: SkinColor.blend(keyBackground, borderColor, SkinDefaults.FUNC_KEY_BLEND_RATIO),
            candidateBackground = oc?.candidateBackground ?: sc?.candidateBackground
                ?: dc.candidateBackground!!,
            candidateTextColor = oc?.candidateTextColor ?: sc?.candidateTextColor
                ?: dc.candidateTextColor!!,
            candidateHighlightColor = oc?.candidateHighlightColor ?: sc?.candidateHighlightColor
                ?: dc.candidateHighlightColor!!,
            preeditTextColor = oc?.preeditTextColor ?: sc?.preeditTextColor
                ?: dc.preeditTextColor!!,
            borderColor = borderColor,
            keyShadowColor = oc?.keyShadowColor ?: sc?.keyShadowColor
                ?: SkinDefaults.KEY_SHADOW_COLOR,

            keyCornerRadiusDp = (od?.keyCornerRadiusDp ?: sd?.keyCornerRadiusDp
                ?: SkinDefaults.KEY_CORNER_RADIUS_DP)
                .coerceIn(SkinSpecValidator.CORNER_RADIUS_RANGE),
            keyGapDp = (od?.keyGapDp ?: sd?.keyGapDp ?: SkinDefaults.KEY_GAP_DP)
                .coerceIn(SkinSpecValidator.KEY_GAP_RANGE),
            keyboardPaddingDp = (od?.keyboardPaddingDp ?: sd?.keyboardPaddingDp
                ?: SkinDefaults.KEYBOARD_PADDING_DP)
                .coerceIn(SkinSpecValidator.PADDING_RANGE),
            keyHeightScale = (od?.keyHeightScale ?: sd?.keyHeightScale
                ?: SkinDefaults.KEY_HEIGHT_SCALE)
                .coerceIn(SkinSpecValidator.KEY_HEIGHT_SCALE_RANGE),
            keyBorderWidthDp = (od?.keyBorderWidthDp ?: sd?.keyBorderWidthDp
                ?: SkinDefaults.KEY_BORDER_WIDTH_DP)
                .coerceIn(SkinSpecValidator.BORDER_WIDTH_RANGE),

            keyTextSizeSp = (ot?.keyTextSizeSp ?: st?.keyTextSizeSp
                ?: SkinDefaults.KEY_TEXT_SIZE_SP).coerceIn(SkinSpecValidator.TEXT_SIZE_RANGE),
            funcTextSizeSp = (ot?.funcTextSizeSp ?: st?.funcTextSizeSp
                ?: SkinDefaults.FUNC_TEXT_SIZE_SP).coerceIn(SkinSpecValidator.TEXT_SIZE_RANGE),
            candidateTextSizeSp = (ot?.candidateTextSizeSp ?: st?.candidateTextSizeSp
                ?: SkinDefaults.CANDIDATE_TEXT_SIZE_SP).coerceIn(SkinSpecValidator.TEXT_SIZE_RANGE),
            preeditTextSizeSp = (ot?.preeditTextSizeSp ?: st?.preeditTextSizeSp
                ?: SkinDefaults.PREEDIT_TEXT_SIZE_SP).coerceIn(SkinSpecValidator.TEXT_SIZE_RANGE),
            fontFamily = ot?.fontFamily ?: st?.fontFamily,
            keyTextBold = ot?.keyTextBold ?: st?.keyTextBold ?: false,

            keyStyle = oe?.keyStyle ?: se?.keyStyle ?: SkinKeyStyle.FILLED,
            keyShadow = resolveShadow(oe, se),
            backgroundAlpha = (oe?.backgroundAlpha ?: se?.backgroundAlpha
                ?: SkinDefaults.BACKGROUND_ALPHA)
                .coerceIn(SkinSpecValidator.BACKGROUND_ALPHA_RANGE),

            backgroundImage = backgroundImage,
            backgroundScaleMode = ob?.scaleMode ?: sb?.scaleMode
                ?: SkinBackgroundScaleMode.CENTER_CROP,
            backgroundDim = (ob?.dimAmount ?: sb?.dimAmount ?: SkinDefaults.BACKGROUND_DIM)
                .coerceIn(SkinSpecValidator.BACKGROUND_DIM_RANGE)
        )
    }

    /** 阴影链：覆盖 > 规格 > 默认；enabled=false 归一为 null（关闭）。 */
    private fun resolveShadow(o: SkinEffects?, s: SkinEffects?): SkinShadowSpec? {
        val shadow = o?.keyShadow ?: s?.keyShadow ?: SkinDefaults.DEFAULT_SHADOW
        return shadow.takeIf { it.enabled }
    }

    /** 按深浅色变体选背景图（深色变体缺失时沿用浅色图）。 */
    private fun pickImage(bg: SkinBackgroundSpec?, isDark: Boolean): String? {
        if (bg == null) return null
        return if (isDark) bg.imageDark ?: bg.image else bg.image
    }
}
