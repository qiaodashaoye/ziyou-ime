package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet

/**
 * QWERTY 全键盘视图
 *
 * 继承 [BaseKeyboardView]，仅负责 QWERTY 特有部分：
 * - 标准 QWERTY 4 行布局
 * - Shift 大小写切换（OFF / ONCE / LOCKED）
 * - 中英文切换、切换到九宫格
 *
 * 布局：
 * [q][w][e][r][t][y][u][i][o][p]
 *  [a][s][d][f][g][h][j][k][l]
 * [⇧] [z][x][c][v][b][n][m] [⌫]
 * [九][中/英][   空格   ][.][↵]
 *
 * 注：技能面板「技」与悬浮切换「浮」已移至候选区按钮栏 [CandidateToolbarView]。
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    /** 当前 Shift 状态 */
    var shiftState: ShiftState = ShiftState.OFF
        private set

    // ===== 键盘布局定义 =====
    private val row1 = listOf(
        Key("q", 'q'.code, 1f), Key("w", 'w'.code, 1f), Key("e", 'e'.code, 1f),
        Key("r", 'r'.code, 1f), Key("t", 't'.code, 1f), Key("y", 'y'.code, 1f),
        Key("u", 'u'.code, 1f), Key("i", 'i'.code, 1f), Key("o", 'o'.code, 1f),
        Key("p", 'p'.code, 1f)
    )

    private val row2 = listOf(
        Key("a", 'a'.code, 1f), Key("s", 's'.code, 1f), Key("d", 'd'.code, 1f),
        Key("f", 'f'.code, 1f), Key("g", 'g'.code, 1f), Key("h", 'h'.code, 1f),
        Key("j", 'j'.code, 1f), Key("k", 'k'.code, 1f), Key("l", 'l'.code, 1f)
    )

    private val row3 = listOf(
        Key("⇧", KeyCode.XK_Shift_L, 1.5f, isFunctional = true),
        Key("z", 'z'.code, 1f), Key("x", 'x'.code, 1f), Key("c", 'c'.code, 1f),
        Key("v", 'v'.code, 1f), Key("b", 'b'.code, 1f), Key("n", 'n'.code, 1f),
        Key("m", 'm'.code, 1f),
        Key("⌫", KeyCode.XK_BackSpace, 1.5f, isFunctional = true)
    )

    private val row4 = listOf(
        Key("九", KeyCode.KEYCODE_SWITCH_KEYBOARD, 1.5f, isFunctional = true),
        Key("中", KeyCode.KEYCODE_SWITCH_LANGUAGE, 1.2f, isFunctional = true),
        Key("空格", KeyCode.XK_space, 4.6f),
        Key(".", '.'.code, 1f),
        Key("↵", KeyCode.XK_Return, 1.7f, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    // 第二行缩进半个键宽
    override fun rowIndent(rowIndex: Int, unitWidth: Float): Float =
        if (rowIndex == 1) unitWidth * 0.5f else 0f

    // ===== 配色：Shift 键激活时高亮 =====
    override fun backgroundPaintFor(key: Key, isPressed: Boolean): Paint = when {
        isPressed -> pressedKeyBgPaint
        key.code == KeyCode.XK_Shift_L && shiftState != ShiftState.OFF -> accentBgPaint
        else -> super.backgroundPaintFor(key, isPressed)
    }

    override fun textPaintFor(key: Key): Paint =
        if (key.code == KeyCode.XK_Shift_L && shiftState != ShiftState.OFF) accentTextPaint
        else super.textPaintFor(key)

    // ===== 文字：Shift 状态、中英文、字母大小写 =====
    override fun getKeyDisplayText(key: Key): String = when {
        key.code == KeyCode.XK_Shift_L -> when (shiftState) {
            ShiftState.OFF, ShiftState.ONCE -> "⇧"
            ShiftState.LOCKED -> "⇪"
        }
        key.code == KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
        key.code in 'a'.code..'z'.code ->
            if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
        else -> key.label
    }

    // ===== 按键处理 =====
    override fun handleKeyUp(key: Key) {
        when (key.code) {
            // Shift：循环切换状态
            KeyCode.XK_Shift_L -> {
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> ShiftState.LOCKED
                    ShiftState.LOCKED -> ShiftState.OFF
                }
                invalidate()
            }

            // 切换到九宫格
            KeyCode.KEYCODE_SWITCH_KEYBOARD -> onSwitchKeyboard?.invoke(KeyboardType.NINE_GRID)

            else -> {
                // 中英文切换 / 符号键盘等共用逻辑
                if (handleCommonKey(key)) return

                // 普通按键：字母根据 Shift 状态大小写
                val isShifted = shiftState != ShiftState.OFF
                val actualKeyCode =
                    if (key.code in 'a'.code..'z'.code && isShifted) key.code - 32 else key.code
                sendKey(actualKeyCode, 0)

                // ONCE 状态输入一个字母后恢复
                if (shiftState == ShiftState.ONCE && key.code in 'a'.code..'z'.code) {
                    shiftState = ShiftState.OFF
                    invalidate()
                }
            }
        }
    }
}
