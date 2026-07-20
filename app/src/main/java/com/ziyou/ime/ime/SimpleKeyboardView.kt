package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ziyou.ime.core.CompositionProto

/**
 * QWERTY键盘视图
 *
 * 使用Canvas自定义绘制实现高性能键盘：
 * - 标准QWERTY布局（4行）
 * - 支持Shift切换大小写
 * - 支持中英文切换
 * - 退格、回车、空格等功能键
 * - 按键触摸反馈（高亮+振动）
 * - 顶部可显示preedit编码
 *
 * 布局：
 * [q][w][e][r][t][y][u][i][o][p]
 *  [a][s][d][f][g][h][j][k][l]
 * [⇧] [z][x][c][v][b][n][m] [⌫]
 * [?123][中/英][     空格     ][.][↵]
 */
class SimpleKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SimpleKeyboardView"

        /** 键盘行数 */
        private const val ROW_COUNT = 4
        /** 按键高度（dp） */
        private const val KEY_HEIGHT_DP = 48
        /** 按键间距（dp） */
        private const val KEY_GAP_DP = 3
        /** 按键圆角（dp） */
        private const val KEY_RADIUS_DP = 5f
        /** 按键文字大小（sp） */
        private const val KEY_TEXT_SIZE_SP = 18f
        /** 功能键文字大小（sp） */
        private const val FUNC_TEXT_SIZE_SP = 12f
        /** 编码区高度（dp） */
        private const val PREEDIT_HEIGHT_DP = 0 // 编码区移到候选词栏
        /** 键盘内边距（dp） */
        private const val KEYBOARD_PADDING_DP = 3
    }

    // ===== Shift 状态枚举 =====
    enum class ShiftState {
        /** 未按下 */
        OFF,
        /** 单次（输入一个字母后恢复） */
        ONCE,
        /** 锁定（Caps Lock） */
        LOCKED
    }

    // ===== 按键数据类 =====
    data class Key(
        val label: String,       // 显示文本
        val code: Int,           // 要发送的keycode（Rime keysym 或自定义码）
        val width: Float,        // 相对宽度（1.0=标准键宽）
        val isFunctional: Boolean = false  // 是否为功能键（不同颜色）
    )

    // ===== 回调 =====
    /** 按键回调: (rimeKeyCode, mask) */
    var onKeyPress: ((keyCode: Int, mask: Int) -> Unit)? = null

    // ===== 状态 =====
    /** 当前Shift状态 */
    var shiftState: ShiftState = ShiftState.OFF
        private set

    /** 是否为中文模式 */
    var isChineseMode: Boolean = true
        private set

    /** preedit编码文本 */
    private var compositionText: String? = null

    // ===== 键盘布局定义 =====
    private val row1 = listOf(
        Key("q", 'q'.code, 1f),
        Key("w", 'w'.code, 1f),
        Key("e", 'e'.code, 1f),
        Key("r", 'r'.code, 1f),
        Key("t", 't'.code, 1f),
        Key("y", 'y'.code, 1f),
        Key("u", 'u'.code, 1f),
        Key("i", 'i'.code, 1f),
        Key("o", 'o'.code, 1f),
        Key("p", 'p'.code, 1f)
    )

    private val row2 = listOf(
        Key("a", 'a'.code, 1f),
        Key("s", 's'.code, 1f),
        Key("d", 'd'.code, 1f),
        Key("f", 'f'.code, 1f),
        Key("g", 'g'.code, 1f),
        Key("h", 'h'.code, 1f),
        Key("j", 'j'.code, 1f),
        Key("k", 'k'.code, 1f),
        Key("l", 'l'.code, 1f)
    )

    private val row3 = listOf(
        Key("⇧", KeyCode.XK_Shift_L, 1.5f, isFunctional = true),
        Key("z", 'z'.code, 1f),
        Key("x", 'x'.code, 1f),
        Key("c", 'c'.code, 1f),
        Key("v", 'v'.code, 1f),
        Key("b", 'b'.code, 1f),
        Key("n", 'n'.code, 1f),
        Key("m", 'm'.code, 1f),
        Key("⌫", KeyCode.XK_BackSpace, 1.5f, isFunctional = true)
    )

    private val row4 = listOf(
        Key("?123", KeyCode.KEYCODE_SYMBOL, 1.5f, isFunctional = true),
        Key("中", KeyCode.KEYCODE_SWITCH_LANGUAGE, 1.2f, isFunctional = true),
        Key("空格", KeyCode.XK_space, 4.6f),
        Key(".", '.'.code, 1f),
        Key("↵", KeyCode.XK_Return, 1.7f, isFunctional = true)
    )

    private val rows = listOf(row1, row2, row3, row4)

    // ===== 按键矩形位置缓存 =====
    private data class KeyRect(
        val key: Key,
        val rect: RectF,
        val row: Int,
        val col: Int
    )
    private val keyRects = mutableListOf<KeyRect>()

    // 当前按下的按键索引
    private var pressedKeyIndex: Int = -1

    // ===== 画笔 =====
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val funcKeyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4D7DD")
        style = Paint.Style.FILL
    }

    private val pressedKeyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBDEFB")
        style = Paint.Style.FILL
    }

    private val shiftOnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")
        style = Paint.Style.FILL
    }

    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(KEY_TEXT_SIZE_SP)
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val funcTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(FUNC_TEXT_SIZE_SP)
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val shiftOnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(FUNC_TEXT_SIZE_SP)
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val boardBgPaint = Paint().apply {
        color = Color.parseColor("#ECEFF1")
        style = Paint.Style.FILL
    }

    private val keyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        style = Paint.Style.FILL
    }

    // ===== 尺寸缓存 =====
    private var keyHeight = 0f
    private var keyGap = 0f
    private var keyRadius = 0f
    private var keyboardPadding = 0f

    init {
        // 初始化尺寸
        keyHeight = dp2px(KEY_HEIGHT_DP.toFloat())
        keyGap = dp2px(KEY_GAP_DP.toFloat())
        keyRadius = dp2px(KEY_RADIUS_DP)
        keyboardPadding = dp2px(KEYBOARD_PADDING_DP.toFloat())

        // 开启触觉反馈
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 键盘总高度 = 行数 * 按键高度 + 行间距 + 上下内边距
        val totalHeight = (ROW_COUNT * keyHeight +
                (ROW_COUNT - 1) * keyGap +
                keyboardPadding * 2).toInt()
        setMeasuredDimension(width, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateKeyPositions()
    }

    /**
     * 重新计算所有按键的位置
     */
    private fun recalculateKeyPositions() {
        keyRects.clear()
        val availableWidth = width - keyboardPadding * 2

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val totalWeight = row.sumOf { it.width.toDouble() }.toFloat()
            val totalGapWidth = (row.size - 1) * keyGap
            val unitWidth = (availableWidth - totalGapWidth) / totalWeight

            var x = keyboardPadding
            val y = keyboardPadding + rowIndex * (keyHeight + keyGap)

            // 第二行缩进半个键宽
            if (rowIndex == 1) {
                val indent = unitWidth * 0.5f
                x += indent
            }

            for (colIndex in row.indices) {
                val key = row[colIndex]
                val keyWidth = unitWidth * key.width
                val rect = RectF(x, y, x + keyWidth, y + keyHeight)
                keyRects.add(KeyRect(key, rect, rowIndex, colIndex))
                x += keyWidth + keyGap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制键盘背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint)

        // 绘制每个按键
        for (i in keyRects.indices) {
            val keyRect = keyRects[i]
            drawKey(canvas, keyRect, i == pressedKeyIndex)
        }
    }

    /**
     * 绘制单个按键
     */
    private fun drawKey(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val key = keyRect.key
        val rect = keyRect.rect

        // 绘制按键阴影（向下偏移1dp）
        val shadowOffset = dp2px(1f)
        val shadowRect = RectF(rect.left, rect.top + shadowOffset, rect.right, rect.bottom + shadowOffset)
        canvas.drawRoundRect(shadowRect, keyRadius, keyRadius, keyShadowPaint)

        // 选择背景画笔
        val bgPaint = when {
            isPressed -> pressedKeyBgPaint
            key.code == KeyCode.XK_Shift_L && shiftState != ShiftState.OFF -> shiftOnBgPaint
            key.isFunctional -> funcKeyBgPaint
            else -> keyBgPaint
        }

        // 绘制按键背景
        canvas.drawRoundRect(rect, keyRadius, keyRadius, bgPaint)

        // 确定文字
        val displayText = getKeyDisplayText(key)

        // 选择文字画笔
        val textPaint = when {
            key.code == KeyCode.XK_Shift_L && shiftState != ShiftState.OFF -> shiftOnTextPaint
            key.isFunctional -> funcTextPaint
            else -> keyTextPaint
        }

        // 绘制文字（垂直居中）
        val textX = rect.centerX()
        val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(displayText, textX, textY, textPaint)
    }

    /**
     * 获取按键显示文本（考虑Shift状态和中英文状态）
     */
    private fun getKeyDisplayText(key: Key): String {
        return when {
            // Shift键显示状态
            key.code == KeyCode.XK_Shift_L -> when (shiftState) {
                ShiftState.OFF -> "⇧"
                ShiftState.ONCE -> "⇧"
                ShiftState.LOCKED -> "⇪"
            }
            // 中英文切换键
            key.code == KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
            // 字母键：根据Shift状态显示大小写
            key.code in 'a'.code..'z'.code -> {
                if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
            }
            else -> key.label
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val index = findKeyAt(event.x, event.y)
                if (index >= 0) {
                    pressedKeyIndex = index
                    invalidate()
                    // 触觉反馈
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = findKeyAt(event.x, event.y)
                if (index != pressedKeyIndex) {
                    pressedKeyIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedKeyIndex >= 0 && event.action == MotionEvent.ACTION_UP) {
                    val keyRect = keyRects[pressedKeyIndex]
                    handleKeyUp(keyRect.key)
                }
                pressedKeyIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 处理按键抬起事件
     */
    private fun handleKeyUp(key: Key) {
        when (key.code) {
            // Shift键：切换Shift状态
            KeyCode.XK_Shift_L -> {
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> ShiftState.LOCKED
                    ShiftState.LOCKED -> ShiftState.OFF
                }
                invalidate()
            }

            // 中英文切换：通知外部处理
            KeyCode.KEYCODE_SWITCH_LANGUAGE -> {
                isChineseMode = !isChineseMode
                // 通知Service层处理（通过发送特殊keycode）
                onKeyPress?.invoke(KeyCode.KEYCODE_SWITCH_LANGUAGE, 0)
                invalidate()
            }

            // 符号键盘切换（暂不实现，预留）
            KeyCode.KEYCODE_SYMBOL -> {
                onKeyPress?.invoke(KeyCode.KEYCODE_SYMBOL, 0)
            }

            // 普通按键：发送给Rime
            else -> {
                val isShifted = shiftState != ShiftState.OFF
                val actualKeyCode = if (key.code in 'a'.code..'z'.code && isShifted) {
                    // 大写字母
                    key.code - 32 // 'a' -> 'A'
                } else {
                    key.code
                }
                val mask = if (isShifted && key.code in 'a'.code..'z'.code) {
                    0 // 大写字母已经在keycode中体现，不需要mask
                } else {
                    0
                }

                onKeyPress?.invoke(actualKeyCode, mask)

                // 如果是ONCE状态，输入一个字母后恢复
                if (shiftState == ShiftState.ONCE && key.code in 'a'.code..'z'.code) {
                    shiftState = ShiftState.OFF
                    invalidate()
                }
            }
        }
    }

    /**
     * 查找指定坐标处的按键索引
     * @return 按键索引，未找到返回-1
     */
    private fun findKeyAt(x: Float, y: Float): Int {
        for (i in keyRects.indices) {
            if (keyRects[i].rect.contains(x, y)) {
                return i
            }
        }
        return -1
    }

    /**
     * 更新编码区显示
     * @param composition 编码信息
     */
    fun updateComposition(composition: CompositionProto?) {
        compositionText = composition?.preedit
        invalidate()
    }

    /**
     * 设置中文模式状态（由Service层调用）
     */
    fun setChineseMode(chinese: Boolean) {
        if (isChineseMode != chinese) {
            isChineseMode = chinese
            invalidate()
        }
    }

    // ===== 单位转换工具 =====
    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density
    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
