package com.ziyou.ime.core.skill

/**
 * 技能面板高度规格（提升挂载形态，needs_input 技能）。
 *
 * 面板高度以键盘实测高度的倍数表达；技能可经 `ui.setPanelHeight(ratio)`
 * 自定义，宿主统一钳制到 [MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO]，
 * 防止脚本把面板缩没或撑满全屏。
 */
object SkillPanelSpec {

    /** 默认提升高度：键盘高度 × 0.6（紧凑，下方键盘完整可用） */
    const val DEFAULT_HEIGHT_RATIO = 0.6f

    /** 最小高度比例（再小内容区扣除标题栏后基本不可用） */
    const val MIN_HEIGHT_RATIO = 0.4f

    /** 最大高度比例（面板 + 候选区 + 键盘的窗口总高仍需可控） */
    const val MAX_HEIGHT_RATIO = 1.2f

    /**
     * 钳制脚本传入的高度比例：非有限值（NaN / Infinity）回退默认值，
     * 其余钳制到合法区间。
     */
    fun clampHeightRatio(ratio: Float): Float {
        if (!ratio.isFinite()) return DEFAULT_HEIGHT_RATIO
        return ratio.coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
    }
}
