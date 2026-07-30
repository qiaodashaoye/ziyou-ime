package com.ziyou.ime.ime

import android.view.KeyEvent

/**
 * 按键码映射工具
 *
 * 将Android KeyEvent的keyCode/软键盘自定义keyCode 转换为
 * Rime引擎所需的X11 keysym值。
 *
 * 参考: librime/include/X11/keysymdef.h
 */
object KeyCode {

    // ===== X11 Keysym 常量定义 =====

    /** 退格键 */
    const val XK_BackSpace = 0xff08
    /** Tab键 */
    const val XK_Tab = 0xff09
    /** 回车键 */
    const val XK_Return = 0xff0d
    /** Escape键 */
    const val XK_Escape = 0xff1b
    /** 删除键 */
    const val XK_Delete = 0xffff
    /** 空格键 */
    const val XK_space = 0x0020

    // 方向键
    /** Home键 */
    const val XK_Home = 0xff50
    /** 左方向键 */
    const val XK_Left = 0xff51
    /** 上方向键 */
    const val XK_Up = 0xff52
    /** 右方向键 */
    const val XK_Right = 0xff53
    /** 下方向键 */
    const val XK_Down = 0xff54
    /** Page Up */
    const val XK_Page_Up = 0xff55
    /** Page Down */
    const val XK_Page_Down = 0xff56
    /** End键 */
    const val XK_End = 0xff57
    /** 小键盘左方向键（Navigator 按字符精确左移，供退格重打定位确认边界） */
    const val XK_KP_Left = 0xff96

    // 修饰键 Keysym (用于独立发送修饰键时)
    /** 左Shift */
    const val XK_Shift_L = 0xffe1
    /** 右Shift */
    const val XK_Shift_R = 0xffe2
    /** 左Control */
    const val XK_Control_L = 0xffe3
    /** 右Control */
    const val XK_Control_R = 0xffe4
    /** Caps Lock */
    const val XK_Caps_Lock = 0xffe5
    /** 左Alt */
    const val XK_Alt_L = 0xffe9
    /** 右Alt */
    const val XK_Alt_R = 0xffea

    // ===== Rime Mask 位标志 =====

    /** Shift 修饰掩码 */
    const val kShiftMask = 1 shl 0   // 1
    /** Lock (Caps Lock) 修饰掩码 */
    const val kLockMask = 1 shl 1    // 2
    /** Control 修饰掩码 */
    const val kControlMask = 1 shl 2 // 4
    /** Alt 修饰掩码 */
    const val kAltMask = 1 shl 3     // 8
    /** Release事件标志 */
    const val kReleaseMask = 1 shl 30

    // ===== 软键盘自定义 keyCode（非Android标准，用于软键盘按键标识） =====

    /** 用于内部标识中英文切换 */
    const val KEYCODE_SWITCH_LANGUAGE = -100
    /** 用于内部标识符号键盘切换 */
    const val KEYCODE_SYMBOL = -101
    /** 用于内部标识键盘布局切换（全键盘 / 九宫格等） */
    const val KEYCODE_SWITCH_KEYBOARD = -102
    /** 用于内部标识九宫格「中数转换」键：切换到数字键盘布局（与 KEYCODE_NUMBER_KEYBOARD 同路径） */
    const val KEYCODE_SWITCH_NUMBER_MODE = -103
    /** 用于内部标识悬浮/停靠形态切换（游戏悬浮键盘） */
    const val KEYCODE_TOGGLE_FLOATING = -104
    /** 用于内部标识技能面板开关（技能插件系统） */
    const val KEYCODE_SKILL_PANEL = -105
    /** 用于内部标识收起键盘（候选区按钮栏） */
    const val KEYCODE_HIDE_KEYBOARD = -106
    /** 用于内部标识打开设置页（候选区按钮栏） */
    const val KEYCODE_OPEN_SETTINGS = -107
    /** 用于内部标识循环切换主题（候选区按钮栏） */
    const val KEYCODE_SWITCH_THEME = -108
    // -109 曾为发送图片（图片选择工具），已移除，编号保留不复用
    /** 用于内部标识 AI 问答面板开关（候选区按钮栏） */
    const val KEYCODE_AI_ASSISTANT = -110
    /** 用于内部标识涂鸦画板面板开关（候选区按钮栏 → commitContent 富媒体提交） */
    const val KEYCODE_DOODLE_PANEL = -111
    /** 用于内部标识粘贴板历史面板开关（候选区按钮栏） */
    const val KEYCODE_CLIPBOARD_PANEL = -112
    /** 用于内部标识循环切换全键盘输入方案（候选区按钮栏，仅 QWERTY 布局生效） */
    const val KEYCODE_SWITCH_SCHEMA = -113
    /** 用于内部标识数字键盘开关（临时面板，与符号键盘同模式） */
    const val KEYCODE_NUMBER_KEYBOARD = -114
    /** 用于内部标识工具面板开关（候选区按钮栏 Logo 键，展示全部工具项） */
    const val KEYCODE_TOOL_PANEL = -115

    /**
     * 将Android KeyEvent的keyCode转换为Rime keysym
     *
     * @param keyCode Android keyCode（来自KeyEvent或软键盘自定义）
     * @param isShifted 是否处于Shift状态（影响字母大小写）
     * @return Rime keysym值，0表示不处理
     */
    fun androidKeyCodeToRimeKeyCode(keyCode: Int, isShifted: Boolean = false): Int {
        return when (keyCode) {
            // 字母键 A-Z → 返回对应ASCII码
            // 如果Shift按下，返回大写字母；否则返回小写字母
            KeyEvent.KEYCODE_A -> if (isShifted) 'A'.code else 'a'.code
            KeyEvent.KEYCODE_B -> if (isShifted) 'B'.code else 'b'.code
            KeyEvent.KEYCODE_C -> if (isShifted) 'C'.code else 'c'.code
            KeyEvent.KEYCODE_D -> if (isShifted) 'D'.code else 'd'.code
            KeyEvent.KEYCODE_E -> if (isShifted) 'E'.code else 'e'.code
            KeyEvent.KEYCODE_F -> if (isShifted) 'F'.code else 'f'.code
            KeyEvent.KEYCODE_G -> if (isShifted) 'G'.code else 'g'.code
            KeyEvent.KEYCODE_H -> if (isShifted) 'H'.code else 'h'.code
            KeyEvent.KEYCODE_I -> if (isShifted) 'I'.code else 'i'.code
            KeyEvent.KEYCODE_J -> if (isShifted) 'J'.code else 'j'.code
            KeyEvent.KEYCODE_K -> if (isShifted) 'K'.code else 'k'.code
            KeyEvent.KEYCODE_L -> if (isShifted) 'L'.code else 'l'.code
            KeyEvent.KEYCODE_M -> if (isShifted) 'M'.code else 'm'.code
            KeyEvent.KEYCODE_N -> if (isShifted) 'N'.code else 'n'.code
            KeyEvent.KEYCODE_O -> if (isShifted) 'O'.code else 'o'.code
            KeyEvent.KEYCODE_P -> if (isShifted) 'P'.code else 'p'.code
            KeyEvent.KEYCODE_Q -> if (isShifted) 'Q'.code else 'q'.code
            KeyEvent.KEYCODE_R -> if (isShifted) 'R'.code else 'r'.code
            KeyEvent.KEYCODE_S -> if (isShifted) 'S'.code else 's'.code
            KeyEvent.KEYCODE_T -> if (isShifted) 'T'.code else 't'.code
            KeyEvent.KEYCODE_U -> if (isShifted) 'U'.code else 'u'.code
            KeyEvent.KEYCODE_V -> if (isShifted) 'V'.code else 'v'.code
            KeyEvent.KEYCODE_W -> if (isShifted) 'W'.code else 'w'.code
            KeyEvent.KEYCODE_X -> if (isShifted) 'X'.code else 'x'.code
            KeyEvent.KEYCODE_Y -> if (isShifted) 'Y'.code else 'y'.code
            KeyEvent.KEYCODE_Z -> if (isShifted) 'Z'.code else 'z'.code

            // 数字键 0-9 → 返回对应ASCII码
            KeyEvent.KEYCODE_0 -> '0'.code
            KeyEvent.KEYCODE_1 -> '1'.code
            KeyEvent.KEYCODE_2 -> '2'.code
            KeyEvent.KEYCODE_3 -> '3'.code
            KeyEvent.KEYCODE_4 -> '4'.code
            KeyEvent.KEYCODE_5 -> '5'.code
            KeyEvent.KEYCODE_6 -> '6'.code
            KeyEvent.KEYCODE_7 -> '7'.code
            KeyEvent.KEYCODE_8 -> '8'.code
            KeyEvent.KEYCODE_9 -> '9'.code

            // 功能键
            KeyEvent.KEYCODE_DEL -> XK_BackSpace
            KeyEvent.KEYCODE_FORWARD_DEL -> XK_Delete
            KeyEvent.KEYCODE_ENTER -> XK_Return
            KeyEvent.KEYCODE_TAB -> XK_Tab
            KeyEvent.KEYCODE_ESCAPE -> XK_Escape
            KeyEvent.KEYCODE_SPACE -> XK_space

            // 方向键
            KeyEvent.KEYCODE_DPAD_LEFT -> XK_Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> XK_Right
            KeyEvent.KEYCODE_DPAD_UP -> XK_Up
            KeyEvent.KEYCODE_DPAD_DOWN -> XK_Down
            KeyEvent.KEYCODE_MOVE_HOME -> XK_Home
            KeyEvent.KEYCODE_MOVE_END -> XK_End
            KeyEvent.KEYCODE_PAGE_UP -> XK_Page_Up
            KeyEvent.KEYCODE_PAGE_DOWN -> XK_Page_Down

            // 符号键（主键盘区）
            KeyEvent.KEYCODE_COMMA -> ','.code
            KeyEvent.KEYCODE_PERIOD -> '.'.code
            KeyEvent.KEYCODE_SEMICOLON -> ';'.code
            KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
            KeyEvent.KEYCODE_SLASH -> '/'.code
            KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
            KeyEvent.KEYCODE_MINUS -> '-'.code
            KeyEvent.KEYCODE_EQUALS -> '='.code
            KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
            KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
            KeyEvent.KEYCODE_GRAVE -> '`'.code
            KeyEvent.KEYCODE_AT -> '@'.code
            KeyEvent.KEYCODE_STAR -> '*'.code
            KeyEvent.KEYCODE_POUND -> '#'.code
            KeyEvent.KEYCODE_PLUS -> '+'.code

            else -> 0
        }
    }

    /**
     * 根据字符直接获取Rime keysym
     * 用于软键盘直接发送字符的场景
     *
     * @param char 要发送的字符
     * @return 对应的Rime keysym（对于ASCII字符直接返回code point）
     */
    fun charToRimeKeyCode(char: Char): Int {
        return char.code
    }

    /**
     * 将Android KeyEvent的meta状态转换为Rime mask
     *
     * @param event Android KeyEvent
     * @return Rime modifier mask
     */
    fun getModifierMask(event: KeyEvent): Int {
        var mask = 0
        if (event.isShiftPressed) mask = mask or kShiftMask
        if (event.isCtrlPressed) mask = mask or kControlMask
        if (event.isAltPressed) mask = mask or kAltMask
        if (event.isCapsLockOn) mask = mask or kLockMask
        return mask
    }

    /**
     * 根据Shift状态构建mask（用于软键盘）
     *
     * @param isShifted 是否Shift按下
     * @return Rime modifier mask
     */
    fun buildMask(isShifted: Boolean = false, isCtrl: Boolean = false, isAlt: Boolean = false): Int {
        var mask = 0
        if (isShifted) mask = mask or kShiftMask
        if (isCtrl) mask = mask or kControlMask
        if (isAlt) mask = mask or kAltMask
        return mask
    }
}
