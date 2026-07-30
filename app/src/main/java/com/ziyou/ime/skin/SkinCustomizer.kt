package com.ziyou.ime.skin

import android.content.Context
import com.ziyou.ime.core.skin.SkinLayer
import com.ziyou.ime.core.skin.SkinResolver
import com.ziyou.ime.core.skin.SkinSpecValidator

/**
 * 用户自定义覆盖的读写门面（皮肤编辑器数据层）。
 *
 * 覆盖是与 skin.json 样式部分同构的**稀疏层**（只存用户改过的字段），
 * 按皮肤 id 独立持久化；基础皮肤（包目录 / 内置规格）永不被改写，
 * 恢复默认 = 删除覆盖。保存后经 [SkinManager.invalidate] 触发快照重建。
 */
object SkinCustomizer {

    /** 读取皮肤的用户覆盖（无覆盖返回空层）。 */
    fun getOverride(context: Context, skinId: String): SkinLayer =
        SkinRepository.getOverride(context, skinId) ?: SkinLayer.EMPTY

    /**
     * 校验并保存用户覆盖（空层等价恢复默认）。
     * @return 校验错误明细；空列表 = 已保存并触发快照重建
     */
    fun saveOverride(context: Context, skinId: String, layer: SkinLayer): List<String> {
        val errors = SkinSpecValidator.validateLayer(layer, prefix = "自定义 ")
        if (errors.isNotEmpty()) return errors
        SkinRepository.setOverride(context, skinId, layer)
        SkinManager.invalidate(context)
        return emptyList()
    }

    /** 恢复默认：删除该皮肤的全部用户覆盖并触发快照重建。 */
    fun resetOverride(context: Context, skinId: String) {
        SkinRepository.clearOverride(context, skinId)
        SkinManager.invalidate(context)
    }

    /** 该皮肤是否存在用户覆盖（编辑器"恢复默认"按钮可用态）。 */
    fun hasOverride(context: Context, skinId: String): Boolean =
        SkinRepository.getOverride(context, skinId) != null

    /**
     * 用临时覆盖合成皮肤快照（编辑器实时预览用，**不落盘**）。
     * 越界值由解析器钳制，无需先过校验；含背景图/字体的同步加载
     * （编辑器场景可接受，且资源大概率已在缓存中）。
     */
    fun previewWith(context: Context, skinId: String, layer: SkinLayer): SkinTheme {
        val spec = SkinRepository.loadSpec(context, skinId)
        val current = SkinManager.getCurrentSkin(context)
        val resolved = SkinResolver.resolve(spec, layer, systemDark = current.isDark)
        val skinDir = SkinRepository.skinDir(context, skinId)
        return SkinTheme(
            resolved = resolved,
            backgroundBitmap = SkinAssetCache.loadBackground(context, skinDir, resolved),
            typeface = SkinAssetCache.loadTypeface(skinDir, resolved)
        )
    }
}
