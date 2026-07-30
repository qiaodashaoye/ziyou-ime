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
import com.ziyou.ime.core.skin.SkinMeta
import com.ziyou.ime.core.skin.SkinShadowSpec
import com.ziyou.ime.core.skin.SkinSpec
import com.ziyou.ime.core.skin.SkinSpecValidator
import com.ziyou.ime.core.skin.SkinTypography
import org.json.JSONObject

/**
 * skin.json / 用户覆盖 JSON 的编解码器（app 层，依赖平台 org.json）。
 *
 * 仅做 JSON ↔ 数据模型的结构转换（含色值字符串 → ARGB Int 解析），字段合法性
 * 统一交给 [SkinSpecValidator]（core-logic 纯逻辑，可单测）；与技能域
 * SkillManifestParser 遵循同一职责划分。解析失败抛 [IllegalArgumentException]，
 * message 可直接向用户展示。
 */
object SkinSpecCodec {

    /**
     * 解析并校验 skin.json 文本。
     * @throws IllegalArgumentException JSON 结构错误 / 色值非法 / 校验失败
     */
    fun decodeSpec(json: String): SkinSpec {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("skin.json 不是合法 JSON: ${e.message}")
        }

        val metaObj = obj.optJSONObject("meta")
            ?: throw IllegalArgumentException("skin.json 缺少 meta 节点")
        val darkModeId = metaObj.optString("darkMode", SkinDarkMode.LIGHT.id)
        val darkMode = SkinDarkMode.fromId(darkModeId)
            ?: throw IllegalArgumentException("未知 meta.darkMode: $darkModeId")

        val spec = SkinSpec(
            specVersion = obj.optInt("specVersion", 0),
            meta = SkinMeta(
                id = metaObj.optString("id"),
                name = metaObj.optString("name"),
                author = metaObj.optString("author").takeIf { it.isNotEmpty() },
                version = metaObj.optString("version", "1.0.0"),
                minSpecVersion = metaObj.optInt("minSpecVersion", 1),
                darkMode = darkMode
            ),
            layer = decodeLayer(obj)
        )

        val errors = SkinSpecValidator.validate(spec)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("skin.json 校验失败: ${errors.joinToString("; ")}")
        }
        return spec
    }

    /** 解析用户覆盖 JSON（与 skin.json 样式部分同构的稀疏层）。 */
    fun decodeLayerString(json: String): SkinLayer {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("覆盖配置不是合法 JSON: ${e.message}")
        }
        return decodeLayer(obj)
    }

    /** 编码稀疏样式层为 JSON 文本（用户覆盖持久化用，仅写非空字段）。 */
    fun encodeLayer(layer: SkinLayer): String {
        val obj = JSONObject()

        val colors = JSONObject()
        layer.colorsLight?.let { colors.put("light", encodeColors(it)) }
        layer.colorsDark?.let { colors.put("dark", encodeColors(it)) }
        if (colors.length() > 0) obj.put("colors", colors)

        layer.dimens?.let { d ->
            obj.put("dimens", JSONObject().apply {
                putFloat("keyCornerRadiusDp", d.keyCornerRadiusDp)
                putFloat("keyGapDp", d.keyGapDp)
                putFloat("keyboardPaddingDp", d.keyboardPaddingDp)
                putFloat("keyHeightScale", d.keyHeightScale)
                putFloat("keyBorderWidthDp", d.keyBorderWidthDp)
            })
        }

        layer.typography?.let { t ->
            obj.put("typography", JSONObject().apply {
                putFloat("keyTextSizeSp", t.keyTextSizeSp)
                putFloat("funcTextSizeSp", t.funcTextSizeSp)
                putFloat("candidateTextSizeSp", t.candidateTextSizeSp)
                putFloat("preeditTextSizeSp", t.preeditTextSizeSp)
                t.fontFamily?.let { put("fontFamily", it) }
                t.keyTextBold?.let { put("keyTextBold", it) }
            })
        }

        layer.effects?.let { e ->
            obj.put("effects", JSONObject().apply {
                e.keyStyle?.let { put("keyStyle", it.id) }
                e.keyShadow?.let { s ->
                    put("keyShadow", JSONObject().apply {
                        put("enabled", s.enabled)
                        putFloat("radiusDp", s.radiusDp)
                        putFloat("dxDp", s.dxDp)
                        putFloat("dyDp", s.dyDp)
                    })
                }
                putFloat("backgroundAlpha", e.backgroundAlpha)
            })
        }

        layer.background?.let { b ->
            obj.put("background", JSONObject().apply {
                b.image?.let { put("image", it) }
                b.imageDark?.let { put("imageDark", it) }
                b.scaleMode?.let { put("scaleMode", it.id) }
                putFloat("dimAmount", b.dimAmount)
            })
        }

        return obj.toString()
    }

    // ===== 内部：样式层解码 =====

    private fun decodeLayer(obj: JSONObject): SkinLayer {
        val colorsObj = obj.optJSONObject("colors")
        return SkinLayer(
            colorsLight = colorsObj?.optJSONObject("light")?.let { decodeColors(it, "colors.light") },
            colorsDark = colorsObj?.optJSONObject("dark")?.let { decodeColors(it, "colors.dark") },
            dimens = obj.optJSONObject("dimens")?.let { d ->
                SkinDimens(
                    keyCornerRadiusDp = d.floatOrNull("keyCornerRadiusDp"),
                    keyGapDp = d.floatOrNull("keyGapDp"),
                    keyboardPaddingDp = d.floatOrNull("keyboardPaddingDp"),
                    keyHeightScale = d.floatOrNull("keyHeightScale"),
                    keyBorderWidthDp = d.floatOrNull("keyBorderWidthDp")
                )
            },
            typography = obj.optJSONObject("typography")?.let { t ->
                SkinTypography(
                    keyTextSizeSp = t.floatOrNull("keyTextSizeSp"),
                    funcTextSizeSp = t.floatOrNull("funcTextSizeSp"),
                    candidateTextSizeSp = t.floatOrNull("candidateTextSizeSp"),
                    preeditTextSizeSp = t.floatOrNull("preeditTextSizeSp"),
                    fontFamily = t.stringOrNull("fontFamily"),
                    keyTextBold = if (t.has("keyTextBold")) t.optBoolean("keyTextBold") else null
                )
            },
            effects = obj.optJSONObject("effects")?.let { e ->
                SkinEffects(
                    keyStyle = e.stringOrNull("keyStyle")?.let { id ->
                        SkinKeyStyle.fromId(id)
                            ?: throw IllegalArgumentException("未知 effects.keyStyle: $id")
                    },
                    keyShadow = e.optJSONObject("keyShadow")?.let { s ->
                        SkinShadowSpec(
                            enabled = s.optBoolean("enabled", true),
                            radiusDp = s.floatOrNull("radiusDp") ?: 0f,
                            dxDp = s.floatOrNull("dxDp") ?: 0f,
                            dyDp = s.floatOrNull("dyDp") ?: 1f
                        )
                    },
                    backgroundAlpha = e.floatOrNull("backgroundAlpha")
                )
            },
            background = obj.optJSONObject("background")?.let { b ->
                SkinBackgroundSpec(
                    image = b.stringOrNull("image"),
                    imageDark = b.stringOrNull("imageDark"),
                    scaleMode = b.stringOrNull("scaleMode")?.let { id ->
                        SkinBackgroundScaleMode.fromId(id)
                            ?: throw IllegalArgumentException("未知 background.scaleMode: $id")
                    },
                    dimAmount = b.floatOrNull("dimAmount")
                )
            }
        )
    }

    private fun decodeColors(obj: JSONObject, node: String): SkinColorScheme = SkinColorScheme(
        keyboardBackground = obj.colorOrNull("keyboardBackground", node),
        keyBackground = obj.colorOrNull("keyBackground", node),
        keyTextColor = obj.colorOrNull("keyTextColor", node),
        keyPressedBackground = obj.colorOrNull("keyPressedBackground", node),
        funcKeyBackground = obj.colorOrNull("funcKeyBackground", node),
        candidateBackground = obj.colorOrNull("candidateBackground", node),
        candidateTextColor = obj.colorOrNull("candidateTextColor", node),
        candidateHighlightColor = obj.colorOrNull("candidateHighlightColor", node),
        preeditTextColor = obj.colorOrNull("preeditTextColor", node),
        borderColor = obj.colorOrNull("borderColor", node),
        keyShadowColor = obj.colorOrNull("keyShadowColor", node)
    )

    private fun encodeColors(scheme: SkinColorScheme): JSONObject = JSONObject().apply {
        putColor("keyboardBackground", scheme.keyboardBackground)
        putColor("keyBackground", scheme.keyBackground)
        putColor("keyTextColor", scheme.keyTextColor)
        putColor("keyPressedBackground", scheme.keyPressedBackground)
        putColor("funcKeyBackground", scheme.funcKeyBackground)
        putColor("candidateBackground", scheme.candidateBackground)
        putColor("candidateTextColor", scheme.candidateTextColor)
        putColor("candidateHighlightColor", scheme.candidateHighlightColor)
        putColor("preeditTextColor", scheme.preeditTextColor)
        putColor("borderColor", scheme.borderColor)
        putColor("keyShadowColor", scheme.keyShadowColor)
    }

    // ===== JSON 小工具（区分"字段缺失"与"字段非法"）=====

    private fun JSONObject.colorOrNull(key: String, node: String): Int? {
        val raw = stringOrNull(key) ?: return null
        return try {
            SkinColor.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$node.$key 色值非法: ${e.message}")
        }
    }

    private fun JSONObject.floatOrNull(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key).toFloat() else null

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun JSONObject.putFloat(key: String, value: Float?) {
        if (value != null) put(key, value.toDouble())
    }

    private fun JSONObject.putColor(key: String, value: Int?) {
        if (value != null) put(key, SkinColor.toHex(value))
    }
}
