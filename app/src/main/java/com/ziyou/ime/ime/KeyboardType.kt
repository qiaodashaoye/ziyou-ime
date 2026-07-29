package com.ziyou.ime.ime

/**
 * 键盘布局类型
 *
 * 用于在多种输入布局之间切换。新增键盘类型时只需：
 * 1. 在此枚举中追加一项（若需专用方案，声明 [forcedSchemaId]）
 * 2. 编写对应的 [BaseKeyboardView] 子类
 * 3. 在 ZiYouInputMethodService.createKeyboardView() 中登记
 *
 * 「布局 ↔ 方案」映射的单一来源：
 * - [forcedSchemaId] 非空表示该布局强制绑定专用方案（如九宫格绑定 t9），
 *   由 ZiYouInputMethodService.applyEngineForKeyboard 负责同步；
 * - [allowsSchemaChoice] 表示该布局下是否允许用户自选方案，
 *   设置页与功能栏的方案选择均按此过滤/限制。
 *
 * 现有 / 规划中的布局：
 * - [QWERTY]     标准全键盘（26 键）
 * - [NINE_GRID]  九宫格（T9，2-9 多字母键 + 多击循环选择）
 * - [SYMBOL]     符号键盘（分类导航 + 符号网格，临时面板不持久化）
 * - 后续可扩展：HANDWRITING（手写）、CUSTOM（自定义布局）等
 */
enum class KeyboardType(
    /** 强制绑定的专用方案 id；null 表示不强制（跟随用户的全键盘方案偏好） */
    val forcedSchemaId: String? = null,
    /** 该布局下是否允许用户自选输入方案 */
    val allowsSchemaChoice: Boolean = false
) {
    QWERTY(allowsSchemaChoice = true),
    NINE_GRID(forcedSchemaId = "t9"), // 与伴生对象 T9_SCHEMA_ID 一致（枚举项构造时伴生对象尚未初始化，不能直接引用）
    SYMBOL;

    companion object {
        /** 九宫格键盘专用的 T9 方案 id */
        const val T9_SCHEMA_ID = "t9"

        /** 全部布局专用方案 id（设置页/功能栏方案选择时过滤，不作为用户选项暴露） */
        val FORCED_SCHEMA_IDS: Set<String> = entries.mapNotNull { it.forcedSchemaId }.toSet()

        /** 按名称安全解析，未知名称回退到 [QWERTY] */
        fun fromName(name: String?): KeyboardType =
            entries.firstOrNull { it.name == name } ?: QWERTY
    }
}
