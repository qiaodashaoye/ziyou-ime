package com.ziyou.ime.core.skin

/**
 * 皮肤规格语义校验器（纯逻辑，可独立 JVM 单测）。
 *
 * 输入为解码后的 [SkinSpec]（色值合法性已在解码期保证），本层校验：
 * specVersion 兼容性、meta 字段格式、darkMode 完整性、数值范围、
 * 包内资源相对路径安全性与扩展名。返回错误明细列表（空 = 通过），
 * 供安装流程拒绝非法皮肤包并向用户展示原因。
 *
 * 范围常量同时是自定义编辑器滑杆的取值边界（单一来源）。
 */
object SkinSpecValidator {

    /** 皮肤 id 格式：小写字母/数字/下划线/点，3–64 位。 */
    val ID_REGEX = Regex("[a-z0-9_.]{3,64}")

    // ===== 数值范围（编辑器滑杆共用同一边界）=====
    val CORNER_RADIUS_RANGE = 0f..24f
    val KEY_GAP_RANGE = 0f..12f
    val PADDING_RANGE = 0f..16f
    val KEY_HEIGHT_SCALE_RANGE = 0.8f..1.3f
    val BORDER_WIDTH_RANGE = 0f..4f
    val TEXT_SIZE_RANGE = 8f..32f
    val BACKGROUND_ALPHA_RANGE = 0.3f..1f
    val BACKGROUND_DIM_RANGE = 0f..0.7f
    val SHADOW_RADIUS_RANGE = 0f..8f
    val SHADOW_OFFSET_RANGE = -4f..4f

    /** 背景图允许的扩展名。 */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** 字体允许的扩展名。 */
    val FONT_EXTENSIONS = setOf("ttf", "otf")

    /** 校验完整皮肤规格，返回错误明细（空列表 = 通过）。 */
    fun validate(spec: SkinSpec): List<String> {
        val errors = mutableListOf<String>()

        if (spec.specVersion !in 1..SkinDefaults.SPEC_VERSION) {
            errors += "不支持的 specVersion: ${spec.specVersion}（当前支持 1..${SkinDefaults.SPEC_VERSION}）"
        }
        if (spec.meta.minSpecVersion > SkinDefaults.SPEC_VERSION) {
            errors += "皮肤要求 minSpecVersion=${spec.meta.minSpecVersion}，请升级应用"
        }
        if (!ID_REGEX.matches(spec.meta.id)) {
            errors += "meta.id 格式非法（要求 [a-z0-9_.]{3,64}）: ${spec.meta.id}"
        }
        if (spec.meta.name.isBlank()) {
            errors += "meta.name 不能为空"
        }
        if (spec.meta.darkMode == SkinDarkMode.BOTH &&
            (spec.layer.colorsDark == null || spec.layer.colorsDark.isEmpty())
        ) {
            errors += "darkMode=both 时必须提供 colors.dark 配色"
        }

        errors += validateLayer(spec.layer)
        return errors
    }

    /**
     * 校验样式层（skin.json 的样式部分与用户覆盖共用）。
     * @param prefix 错误信息前缀（覆盖校验时标注来源）
     */
    fun validateLayer(layer: SkinLayer, prefix: String = ""): List<String> {
        val errors = mutableListOf<String>()

        layer.dimens?.let { d ->
            checkRange(errors, prefix, "dimens.keyCornerRadiusDp", d.keyCornerRadiusDp, CORNER_RADIUS_RANGE)
            checkRange(errors, prefix, "dimens.keyGapDp", d.keyGapDp, KEY_GAP_RANGE)
            checkRange(errors, prefix, "dimens.keyboardPaddingDp", d.keyboardPaddingDp, PADDING_RANGE)
            checkRange(errors, prefix, "dimens.keyHeightScale", d.keyHeightScale, KEY_HEIGHT_SCALE_RANGE)
            checkRange(errors, prefix, "dimens.keyBorderWidthDp", d.keyBorderWidthDp, BORDER_WIDTH_RANGE)
        }

        layer.typography?.let { t ->
            checkRange(errors, prefix, "typography.keyTextSizeSp", t.keyTextSizeSp, TEXT_SIZE_RANGE)
            checkRange(errors, prefix, "typography.funcTextSizeSp", t.funcTextSizeSp, TEXT_SIZE_RANGE)
            checkRange(errors, prefix, "typography.candidateTextSizeSp", t.candidateTextSizeSp, TEXT_SIZE_RANGE)
            checkRange(errors, prefix, "typography.preeditTextSizeSp", t.preeditTextSizeSp, TEXT_SIZE_RANGE)
            t.fontFamily?.let { path ->
                if (!isSafeResourcePath(path, FONT_EXTENSIONS)) {
                    errors += "${prefix}typography.fontFamily 路径非法或扩展名不支持: $path"
                }
            }
        }

        layer.effects?.let { e ->
            checkRange(errors, prefix, "effects.backgroundAlpha", e.backgroundAlpha, BACKGROUND_ALPHA_RANGE)
            e.keyShadow?.let { s ->
                checkRange(errors, prefix, "effects.keyShadow.radiusDp", s.radiusDp, SHADOW_RADIUS_RANGE)
                checkRange(errors, prefix, "effects.keyShadow.dxDp", s.dxDp, SHADOW_OFFSET_RANGE)
                checkRange(errors, prefix, "effects.keyShadow.dyDp", s.dyDp, SHADOW_OFFSET_RANGE)
            }
        }

        layer.toolbar?.let { t ->
            checkRange(errors, prefix, "toolbar.buttonCornerRadiusDp", t.buttonCornerRadiusDp, CORNER_RADIUS_RANGE)
            checkRange(errors, prefix, "toolbar.buttonBorderWidthDp", t.buttonBorderWidthDp, BORDER_WIDTH_RANGE)
            checkRange(errors, prefix, "toolbar.buttonSpacingDp", t.buttonSpacingDp, KEY_GAP_RANGE)
            checkRange(errors, prefix, "toolbar.textSizeSp", t.textSizeSp, TEXT_SIZE_RANGE)
        }

        layer.background?.let { b ->
            checkRange(errors, prefix, "background.dimAmount", b.dimAmount, BACKGROUND_DIM_RANGE)
            b.image?.let { path ->
                if (!isSafeResourcePath(path, IMAGE_EXTENSIONS)) {
                    errors += "${prefix}background.image 路径非法或扩展名不支持: $path"
                }
            }
            b.imageDark?.let { path ->
                if (!isSafeResourcePath(path, IMAGE_EXTENSIONS)) {
                    errors += "${prefix}background.imageDark 路径非法或扩展名不支持: $path"
                }
            }
        }

        return errors
    }

    /**
     * 包内资源相对路径安全性 + 扩展名白名单校验。
     * 拒绝：绝对路径、`..`/`.` 路径段、反斜杠/盘符/NUL、超长路径、白名单外扩展名。
     */
    fun isSafeResourcePath(path: String, allowedExtensions: Set<String>): Boolean {
        if (path.isBlank() || path.length > 255) return false
        if (path.startsWith("/")) return false
        if (path.contains('\\') || path.contains(':') || path.contains('\u0000')) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == ".." || it == "." }) return false
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in allowedExtensions
    }

    private fun checkRange(
        errors: MutableList<String>,
        prefix: String,
        field: String,
        value: Float?,
        range: ClosedFloatingPointRange<Float>
    ) {
        if (value != null && value !in range) {
            errors += "$prefix$field 超出范围 [${range.start}, ${range.endInclusive}]: $value"
        }
    }
}
