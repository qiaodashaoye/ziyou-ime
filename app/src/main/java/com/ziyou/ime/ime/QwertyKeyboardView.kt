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
 * - 中英文切换、数字键盘
 *
 * 布局（基于基类的 10 列网格，见 [gridColumns]）：
 * [q][w][e][r][t][y][u][i][o][p]         ← 10 列，每键 1 列
 *  [a][s][d][f][g][h][j][k][l]           ← 整行右移半列，居中
 * [⇧]  [z][x][c][v][b][n][m]  [⌫]       ← z 对齐 s、m 对齐 k，两侧功能键各跨 1.5 列
 * [123][，][   空格   ][中/英][换行]
 *
 * 注：技能面板「技」与悬浮切换「浮」已移至候选区按钮栏 [CandidateToolbarView]；
 * 九宫格切换由功能栏 / 工具面板的「键盘切换」提供，符号面板由「123」数字键盘
 * 内的「符号」键进入，因此底行不再占用键位。
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    /** 当前 Shift 状态 */
    var shiftState: ShiftState = ShiftState.OFF
        private set

    // ===== 键盘布局定义（width = 跨列数，各行合计 10 列填满整行）=====

    /** 以第 1 行 10 个字母键为基准的列网格，各行严格对齐同一套列 */
    override val gridColumns: Int = 10

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

    // 两侧功能键各跨 1.5 列（外侧与第 1 行齐边），并向字母区一侧额外让出 1 个间距，
    // 使字母区（z~m 占第 1.5~8.5 列）与功能键的分隔更清晰
    private val row3 = listOf(
        Key("⇧", KeyCode.XK_Shift_L, 1.5f, isFunctional = true, insetGapEnd = 1f),
        Key("z", 'z'.code, 1f), Key("x", 'x'.code, 1f), Key("c", 'c'.code, 1f),
        Key("v", 'v'.code, 1f), Key("b", 'b'.code, 1f), Key("n", 'n'.code, 1f),
        Key("m", 'm'.code, 1f),
        Key("⌫", KeyCode.XK_BackSpace, 1.5f, isFunctional = true, insetGapStart = 1f)
    )

    // 底行跨列数取自参考版式：空格略窄，中英 / 换行略宽以容纳双汉字
    private val row4 = listOf(
        Key("123", KeyCode.KEYCODE_NUMBER_KEYBOARD, 2f, isFunctional = true),
        Key("，", ','.code, 1f),
        Key("空格", KeyCode.XK_space, 3.8f),
        Key("中", KeyCode.KEYCODE_SWITCH_LANGUAGE, 1.1f, isFunctional = true),
        Key("换行", KeyCode.XK_Return, 2.1f, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    // 第二行（9 键）右移半列（半个列宽 + 半个列间距），在 10 列网格内居中
    override fun rowIndent(rowIndex: Int, unitWidth: Float): Float =
        if (rowIndex == 1) (unitWidth + keyGap) * 0.5f else 0f

    // ===== 配色：Shift 键激活时高亮（回车强调色/按下态等由基类统一处理）=====
    override fun backgroundPaintFor(key: Key, isPressed: Boolean): Paint = when {
        !isPressed && key.code == KeyCode.XK_Shift_L && shiftState != ShiftState.OFF -> accentBgPaint
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
        // 逗号随模式展示全角 / 半角，与实际上屏结果一致
        key.code == ','.code -> if (isChineseMode) "，" else ","
        key.code in 'a'.code..'z'.code ->
            if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
        // 换行键文案（搜索 / 发送…）等由基类统一处理
        else -> super.getKeyDisplayText(key)
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

            // 数字键盘（临时面板，由 Service 记录进入前布局并切换）
            KeyCode.KEYCODE_NUMBER_KEYBOARD ->
                sendKey(KeyCode.KEYCODE_NUMBER_KEYBOARD, 0)

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
