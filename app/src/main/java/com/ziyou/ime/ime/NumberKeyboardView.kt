package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * 数字键盘视图 —— 对应 [KeyboardType.NUMBER]。
 *
 * 继承 [BaseKeyboardView]，复用基类的绘制 / 触摸 / 长按 / 皮肤逻辑；
 * 布局为 5 列结构（左侧快捷符号列 + 3 列数字 + 右侧功能列）：
 *
 * [@]  [1][2][3]  [⌫]
 * [%]  [4][5][6]  [空格]
 * [-]  [7][8][9]  [😊]
 * [+]  └ 左列 4 键纵向均分前 3 行高度
 * [符号][返回][0][.][换行]
 *
 * 左列快捷符号（@ % - +）跨 3 行摞 4 键，是分数行高，超出基类「行 × 相对宽度」
 * 模型的表达能力，故在 [recalculateKeyPositions] 中追加自定义矩形到 keyRects，
 * 绘制 / 触摸 / 按压高亮由基类自动复用。
 *
 * 交互（与符号键盘同模式，见 docs/符号键盘调研与设计方案.md 的临时面板约定）：
 * - 数字与 . @ % - + 直接上屏（回调 [onNumberInput]，经 Service 统一 commit 出口），
 *   不经 Rime 编码路径，避免中文模式下数字被吃进 preedit。
 * - 「返回」发送 [KeyCode.KEYCODE_NUMBER_KEYBOARD]，由 Service 恢复进入前的键盘布局。
 * - 「符号」/「😊」发送 [KeyCode.KEYCODE_SYMBOL] 打开符号面板（表情位于其分类中）。
 * - 「⌫」长按连续删除（复用基类的重复触发机制）；空格 / 换行走 Rime 按键路径。
 */
class NumberKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 与九宫格一致的按键高度倍率，使 4 行布局总高与 T9 键盘对齐 */
        private const val T9_HEIGHT_FACTOR = 1.08f
        /** 左侧快捷符号列 / 右侧功能列相对宽度（与截图版式一致，略窄于数字列） */
        private const val SIDE_COL_WIDTH = 0.9f
        /** 数字键文字大小（sp）— 与九宫格字母键同号 */
        private const val DIGIT_TEXT_SIZE_SP = 16f
        /** 表情键的伪键码（打开符号面板，仅本视图内部使用） */
        private const val EMOJI_CODE = -1000
    }

    /** 数字/符号上屏回调：参数为要直接 commit 的字符内容 */
    var onNumberInput: ((value: String) -> Unit)? = null

    /** 数字键盘 4 行布局，与 T9 等高 */
    override val keyHeightMultiplier: Float = T9_HEIGHT_FACTOR

    // ===== 布局定义（5 列：左符号列 + 3 数字列 + 右功能列，总权重 4.8）=====
    // 行 1~3 只含数字列 + 右功能列（左列区域由跨 3 行的快捷符号键占用），
    // 通过 rowIndent 缩进一个左列宽度；第 4 行横贯全部 5 列。

    /** 左侧快捷符号列：@ % - +，4 键纵向均分前 3 行高度（自定义矩形） */
    private val sideSymbolKeys = listOf(
        Key("@", '@'.code, SIDE_COL_WIDTH, isFunctional = true),
        Key("%", '%'.code, SIDE_COL_WIDTH, isFunctional = true),
        Key("-", '-'.code, SIDE_COL_WIDTH, isFunctional = true),
        Key("+", '+'.code, SIDE_COL_WIDTH, isFunctional = true)
    )

    private val row1 = listOf(
        Key("1", '1'.code, 1f), Key("2", '2'.code, 1f), Key("3", '3'.code, 1f),
        Key("\u232B", KeyCode.XK_BackSpace, SIDE_COL_WIDTH, isFunctional = true)
    )

    private val row2 = listOf(
        Key("4", '4'.code, 1f), Key("5", '5'.code, 1f), Key("6", '6'.code, 1f),
        Key("空格", KeyCode.XK_space, SIDE_COL_WIDTH, isFunctional = true)
    )

    private val row3 = listOf(
        Key("7", '7'.code, 1f), Key("8", '8'.code, 1f), Key("9", '9'.code, 1f),
        Key("\uD83D\uDE0A", EMOJI_CODE, SIDE_COL_WIDTH, isFunctional = true)
    )

    /** 第 4 行：横贯 5 列，[符号][返回][0][.][换行] */
    private val row4 = listOf(
        Key("符号", KeyCode.KEYCODE_SYMBOL, SIDE_COL_WIDTH, isFunctional = true),
        Key("返回", KeyCode.KEYCODE_NUMBER_KEYBOARD, 1f),
        Key("0", '0'.code, 1f),
        Key(".", '.'.code, 1f),
        Key("换行", KeyCode.XK_Return, SIDE_COL_WIDTH, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    // ===== 布局：全局统一宽度单元 + 行 1~3 缩进左列 =====

    /** 全 5 列的总权重（左列 + 3 数字列 + 右列） */
    private val totalColumnWeight =
        SIDE_COL_WIDTH * 2 + 3f

    /** 以完整 5 列（4 个间距）为基准计算全局宽度单元值，各行共用保证纵向对齐 */
    override fun rowUnitWidth(rowIndex: Int, availableWidth: Float): Float? {
        if (availableWidth <= 0f) return null
        return (availableWidth - 4 * keyGap) / totalColumnWeight
    }

    /** 行 1~3 左侧缩进一个快捷符号列宽度（该区域由跨 3 行的 @ % - + 占用） */
    override fun rowIndent(rowIndex: Int, unitWidth: Float): Float =
        if (rowIndex < 3) unitWidth * SIDE_COL_WIDTH + keyGap else 0f

    /** 追加左列 4 个快捷符号键的自定义矩形（纵向均分前 3 行高度） */
    override fun recalculateKeyPositions() {
        super.recalculateKeyPositions()
        val availableWidth = width - keyboardPadding * 2
        val unitWidth = rowUnitWidth(0, availableWidth) ?: return
        val colWidth = unitWidth * SIDE_COL_WIDTH
        // 前 3 行总高度均分为 4 键（含键间距）
        val zoneHeight = 3 * keyHeight * keyHeightMultiplier + 2 * keyGap
        val itemHeight = (zoneHeight - 3 * keyGap) / 4
        for (i in sideSymbolKeys.indices) {
            val top = keyboardPadding + i * (itemHeight + keyGap)
            val rect = RectF(keyboardPadding, top, keyboardPadding + colWidth, top + itemHeight)
            keyRects.add(KeyRect(sideSymbolKeys[i], rect, i, -1))
        }
    }

    // ===== 画笔：数字键使用大号字体（与九宫格字母键风格一致）=====

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(DIGIT_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = skin.keyTextColor
    }

    override fun applySkin(newSkin: com.ziyou.ime.skin.SkinTheme) {
        super.applySkin(newSkin)
        digitPaint.apply { color = skin.keyTextColor; typeface = skin.keyTypeface }
    }

    /** 悬浮缩放时同步数字画笔的文字大小 */
    override fun onScaleChanged() {
        digitPaint.textSize = sp2px(DIGIT_TEXT_SIZE_SP)
    }

    override fun textPaintFor(key: Key): Paint =
        if (key.code in '0'.code..'9'.code) digitPaint else super.textPaintFor(key)

    // ===== 按键处理 =====

    override fun handleKeyUp(key: Key) {
        when (key.code) {
            // 「返回」：由 Service 切回进入前的键盘布局
            KeyCode.KEYCODE_NUMBER_KEYBOARD ->
                sendKey(KeyCode.KEYCODE_NUMBER_KEYBOARD, 0)

            // 「😊」：打开符号面板（表情分类），与「符号」键同路径
            EMOJI_CODE -> sendKey(KeyCode.KEYCODE_SYMBOL, 0)

            // 退格 / 空格 / 换行：走 Rime 按键路径（无编码时由 Service 兜底发给编辑器）
            KeyCode.XK_BackSpace, KeyCode.XK_space, KeyCode.XK_Return ->
                sendKey(key.code, 0)

            else -> {
                // 「符号」等共用功能键
                if (handleCommonKey(key)) return
                // 数字与 . @ % - +：直接上屏，不经 Rime 编码路径
                onNumberInput?.invoke(key.label)
            }
        }
    }
}
