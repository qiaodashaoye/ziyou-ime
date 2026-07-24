package com.ziyou.ime.ime

/**
 * 键盘布局类型
 *
 * 用于在多种输入布局之间切换。新增键盘类型时只需：
 * 1. 在此枚举中追加一项
 * 2. 编写对应的 [BaseKeyboardView] 子类
 * 3. 在 ZiYouInputMethodService.createKeyboardView() 中登记
 *
 * 现有 / 规划中的布局：
 * - [QWERTY]     标准全键盘（26 键）
 * - [NINE_GRID]  九宫格（T9，2-9 多字母键 + 多击循环选择）
 * - 后续可扩展：SYMBOL（符号）、HANDWRITING（手写）、CUSTOM（自定义布局）等
 */
enum class KeyboardType {
    QWERTY,
    NINE_GRID;

    companion object {
        /** 按名称安全解析，未知名称回退到 [QWERTY] */
        fun fromName(name: String?): KeyboardType =
            entries.firstOrNull { it.name == name } ?: QWERTY
    }
}
