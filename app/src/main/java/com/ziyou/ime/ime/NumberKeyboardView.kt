package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * 数字键盘视图 —— 对应 [KeyboardType.NUMBER]。
 *
 * 继承 [BaseKeyboardView]，复用基类的绘制 / 触摸 / 长按 / 皮肤逻辑；
 * 布局为 4 列结构（3 列数字 + 1 列功能列），与九宫格 [NineGridKeyboardView] 列结构对称，
 * 共享 [BaseKeyboardView.T9_FAMILY_HEIGHT_FACTOR] / [BaseKeyboardView.T9_FAMILY_FUNC_COL_WIDTH]
 * 设计常量以保证按键尺寸和间距的视觉一致性。
 *
 * 停靠形态下左侧符号栏由 [KeyboardLayoutManager] 外挂 [PinyinSideBarView] 组装，
 * 与九宫格侧栏装配模式一致；悬浮形态无侧栏，视图独立渲染。
 *
 *     [1]   [2]   [3]   [⌫]
 *     [4]   [5]   [6]   [空格]
 *     [7]   [8]   [9]   [换行]  ← 跨第 3~4 行
 *   [返回]  [0]   [.]
 *
 * 底行「符号」入口已由左侧 [PinyinSideBarView] 底部固定键提供，不再重复。
 *
 * 交互（与符号键盘同模式，见 docs/符号键盘调研与设计方案.md 的临时面板约定）：
 * - 数字与 . 直接上屏（回调 [onNumberInput]，经 Service 统一 commit 出口），
 *   不经 Rime 编码路径，避免中文模式下数字被吃进 preedit。
 * - 「返回」发送 [KeyCode.KEYCODE_NUMBER_KEYBOARD]，由 Service 恢复进入前的键盘布局。
 * - 「⌫」长按连续删除（复用基类的重复触发机制）；空格 / 换行走 Rime 按键路径。
 */
class NumberKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 数字键文字大小（sp）— 与九宫格字母键同号 */
        private const val DIGIT_TEXT_SIZE_SP = 16f
    }

    /** 数字/符号上屏回调：参数为要直接 commit 的字符内容 */
    var onNumberInput: ((value: String) -> Unit)? = null

    /** 数字键盘 4 行布局，与 T9 等高 */
    override val keyHeightMultiplier: Float = T9_FAMILY_HEIGHT_FACTOR

    // ===== 布局定义（4 列：3 数字列 + 1 功能列，行 1~3 总权重 3.8）=====
    // 与九宫格 [NineGridKeyboardView] 列结构对称，共享宽度计算公式。

    private val row1 = listOf(
        Key("1", '1'.code, 1f), Key("2", '2'.code, 1f), Key("3", '3'.code, 1f),
        Key("\u232B", KeyCode.XK_BackSpace, T9_FAMILY_FUNC_COL_WIDTH, isFunctional = true)
    )

    private val row2 = listOf(
        Key("4", '4'.code, 1f), Key("5", '5'.code, 1f), Key("6", '6'.code, 1f),
        Key("空格", KeyCode.XK_space, T9_FAMILY_FUNC_COL_WIDTH, isFunctional = true)
    )

    /** 第 3 行：「换行」键纵向跨第 3~4 行（与九宫格换行键同构） */
    private val row3 = listOf(
        Key("7", '7'.code, 1f), Key("8", '8'.code, 1f), Key("9", '9'.code, 1f),
        Key("换行", KeyCode.XK_Return, T9_FAMILY_FUNC_COL_WIDTH, isFunctional = true, heightSpan = 2)
    )

    /**
     * 第 4 行（底行）：3 键均取宽度 1f（与上方数字列等宽），严格对齐 7/8/9 列位。
     * 右侧区域由跨行的「换行」键占用。
     * 权重合计 3.0，与行 1~3 共享 unitWidth 使按键纵向对齐。
     */
    private val row4 = listOf(
        Key("返回", KeyCode.KEYCODE_NUMBER_KEYBOARD, 1f),
        Key("0", '0'.code, 1f),
        Key(".", '.'.code, 1f)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    // ===== 网格尺寸（供左侧栏对齐用，与 NineGridKeyboardView 同名接口）=====

    /** 3×3 数字网格的按键宽度单元值（px），由 recalculateKeyPositions 自动计算 */
    var gridUnitWidth: Float? = null
        private set

    /** 单行按键高度（px，含高度倍率），供侧栏底部「符号」键对齐 */
    override val gridRowHeight: Float
        get() = keyHeight * keyHeightMultiplier

    /** 网格首行按键顶部的 y 偏移（px），供侧栏列表顶部对齐 */
    override val gridTop: Float
        get() = keyboardPadding

    /** 网格行间距（px），供侧栏列表与底部「符号」键之间的间距对齐 */
    override val gridRowGap: Float
        get() = keyGap

    /** 第 4 行（底行）顶部相对本视图的 y 偏移（px），供侧栏底部「符号」键对齐 */
    override val bottomRowTop: Float
        get() = keyboardPadding + 3 * (gridRowHeight + keyGap)

    // ===== 布局：与九宫格共享宽度计算公式 =====

    override fun recalculateKeyPositions() {
        super.recalculateKeyPositions()
        val availableWidth = width - keyboardPadding * 2
        if (availableWidth > 0f) {
            gridUnitWidth = unitWidthOf(availableWidth)
        }
    }

    /**
     * 底行沿用行 1~3 的宽度单元值，使 [返回][0][.] 与上方 7/8/9 列严格对齐，
     * 右侧区域留给跨行的「换行」键。
     */
    override fun rowUnitWidth(rowIndex: Int, availableWidth: Float): Float? =
        if (rowIndex == 3 && availableWidth > 0f) unitWidthOf(availableWidth) else null

    /** 以行 1（4 键 / 权重 3.8）为基准计算宽度单元值（与九宫格同公式） */
    private fun unitWidthOf(availableWidth: Float): Float {
        val totalWeight = row1.sumOf { it.width.toDouble() }.toFloat()
        val totalGapWidth = (row1.size - 1) * keyGap
        return (availableWidth - totalGapWidth) / totalWeight
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

    /** 底行全部按键（返回 / 0 / .）使用数字字号，与上方数字键视觉一致 */
    override fun textPaintFor(key: Key): Paint =
        if (key.code in '0'.code..'9'.code ||
            key.code == '.'.code ||
            key.code == KeyCode.KEYCODE_NUMBER_KEYBOARD
        ) digitPaint
        else super.textPaintFor(key)

    // ===== 按键处理 =====

    override fun handleKeyUp(key: Key) {
        when (key.code) {
            // 「返回」：由 Service 切回进入前的键盘布局
            KeyCode.KEYCODE_NUMBER_KEYBOARD ->
                sendKey(KeyCode.KEYCODE_NUMBER_KEYBOARD, 0)

            // 退格 / 空格 / 换行：走 Rime 按键路径（无编码时由 Service 兜底发给编辑器）
            KeyCode.XK_BackSpace, KeyCode.XK_space, KeyCode.XK_Return ->
                sendKey(key.code, 0)

            else -> {
                // 共用功能键（中英文切换等）
                if (handleCommonKey(key)) return
                // 数字与 .：直接上屏，不经 Rime 编码路径
                onNumberInput?.invoke(key.label)
            }
        }
    }
}
