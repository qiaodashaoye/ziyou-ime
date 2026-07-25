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
import com.ziyou.ime.config.KeyboardTheme
import com.ziyou.ime.config.ThemeManager
import com.ziyou.ime.core.CompositionProto

/**
 * 键盘视图基类
 *
 * 抽象出所有键盘布局共用的能力，使新增键盘类型（全键盘、九宫格、符号、手写等）
 * 只需继承本类并提供布局与按键处理，而无需重复实现绘制 / 触摸 / 主题逻辑。
 *
 * 共用能力：
 * - 基于「行 × 相对宽度」的布局模型（[rows]）与自动尺寸计算
 * - Canvas 绘制（背景、圆角、阴影、文字）
 * - 触摸按下高亮 + 触觉反馈
 * - 与 [ThemeManager] 集成的主题着色（[applyTheme]）
 * - 统一的按键回调 [onKeyPress]、键盘切换回调 [onSwitchKeyboard]
 * - 中英文模式、preedit 编码同步
 *
 * 子类需实现：
 * - [rows]：布局定义
 * - [handleKeyUp]：按键抬起处理
 * 可按需覆写：
 * - [getKeyDisplayText] / [drawKeyContent]：按键文字渲染
 * - [backgroundPaintFor] / [textPaintFor]：按键配色
 * - [rowIndent]：整行缩进
 */
abstract class BaseKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 按键高度（dp） */
        private const val KEY_HEIGHT_DP = 48
        /** 按键间距（dp） */
        private const val KEY_GAP_DP = 3
        /** 按键圆角（dp） */
        private const val KEY_RADIUS_DP = 5f
        /** 普通按键文字大小（sp） */
        private const val KEY_TEXT_SIZE_SP = 18f
        /** 功能键文字大小（sp） */
        private const val FUNC_TEXT_SIZE_SP = 12f
        /** 键盘内边距（dp） */
        private const val KEYBOARD_PADDING_DP = 3
        /** 长按触发连续重复前的初始延迟（ms） */
        private const val REPEAT_START_DELAY_MS = 400L
        /** 连续重复的触发间隔（ms） */
        private const val REPEAT_INTERVAL_MS = 60L
    }

    // ===== Shift 状态枚举（供全键盘等使用） =====
    enum class ShiftState { OFF, ONCE, LOCKED }

    // ===== 按键数据类 =====
    data class Key(
        /** 显示文本 */
        val label: String,
        /** 要发送的 keycode（Rime keysym 或自定义功能码） */
        val code: Int,
        /** 相对宽度（1.0 = 标准键宽） */
        val width: Float,
        /** 是否为功能键（不同配色） */
        val isFunctional: Boolean = false,
        /** 九宫格多字母键所承载的字母序列，如 "abc"；普通键为 null */
        val letters: String? = null
    )

    // ===== 回调 =====
    /** 按键回调: (rimeKeyCode, mask) */
    var onKeyPress: ((keyCode: Int, mask: Int) -> Unit)? = null

    /** 键盘布局切换回调：请求切换到指定 [KeyboardType] */
    var onSwitchKeyboard: ((target: KeyboardType) -> Unit)? = null

    /**
     * 编码预览回调：用于把尚未提交给 Rime 的输入状态（如九宫格多击预览字母）
     * 实时反馈给候选栏拼音区。preview 为 null 表示无预览。
     */
    var onComposingPreview: ((preview: String?) -> Unit)? = null

    /**
     * 九宫格“中→英”专用回调：强制切换到 26 键英文模式。
     * 不走 [onKeyPress] 异步路径，避免与 [onSwitchKeyboard] 产生竞态。
     */
    var onSwitchToQwertyEnglish: (() -> Unit)? = null

    // ===== 状态 =====
    /** 是否为中文模式（由 Service 层同步；变更时自动刷新） */
    var isChineseMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** preedit 编码文本（编码区已移至候选栏，此处保留以兼容调用方） */
    protected var compositionText: String? = null

    /** 当前主题（默认取用户已保存的主题） */
    protected var theme: KeyboardTheme = ThemeManager.getCurrentTheme(context)

    // ===== 布局定义（由子类提供） =====
    protected abstract val rows: List<List<Key>>

    // ===== 按键矩形位置缓存 =====
    protected data class KeyRect(
        val key: Key,
        val rect: RectF,
        val row: Int,
        val col: Int
    )
    private val keyRects = mutableListOf<KeyRect>()

    /** 当前按下的按键索引 */
    private var pressedKeyIndex: Int = -1

    // ===== 长按连续触发（如退格键长按持续删除） =====

    /** 本次按下过程中重复触发是否已执行过（执行过则 ACTION_UP 时不再补发一次按键） */
    private var keyRepeatFired = false

    /** 周期性触发按键的任务：每次执行等同一次完整点按，并重新调度自身 */
    private val keyRepeatRunnable = object : Runnable {
        override fun run() {
            val index = pressedKeyIndex
            if (index < 0 || index >= keyRects.size) return
            val key = keyRects[index].key
            if (!isRepeatableKey(key)) return
            keyRepeatFired = true
            handleKeyUp(key)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /**
     * 判断按键是否支持长按连续触发。默认仅退格键支持，
     * 子类可覆写以扩展（如方向键等）。
     */
    protected open fun isRepeatableKey(key: Key): Boolean = key.code == KeyCode.XK_BackSpace

    /** 取消尚未触发或正在进行的连续重复调度 */
    private fun cancelKeyRepeat() {
        removeCallbacks(keyRepeatRunnable)
    }

    // ===== 画笔（随主题重建） =====
    protected val keyBgPaint = fillPaint()
    protected val funcKeyBgPaint = fillPaint()
    protected val pressedKeyBgPaint = fillPaint()
    protected val accentBgPaint = fillPaint()
    protected val boardBgPaint = Paint().apply { style = Paint.Style.FILL }
    protected val keyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        style = Paint.Style.FILL
    }
    protected val keyTextPaint = textPaint(KEY_TEXT_SIZE_SP, Typeface.DEFAULT)
    protected val funcTextPaint = textPaint(FUNC_TEXT_SIZE_SP, Typeface.DEFAULT)
    protected val accentTextPaint = textPaint(FUNC_TEXT_SIZE_SP, Typeface.DEFAULT_BOLD)

    // ===== 尺寸缓存 =====
    protected var keyHeight = 0f
    protected var keyGap = 0f
    protected var keyRadius = 0f
    protected var keyboardPadding = 0f

    /** 按键高度倍率（默认 1.0）。子类可覆写以调整按键高度，如九宫格 5 行布局可适当加大。 */
    protected open val keyHeightMultiplier: Float = 1.0f

    /**
     * 强制指定按键宽度单元值（px）。
     * 当非 null 时，recalculateKeyPositions 使用该值代替根据当前视图宽度自动计算的值，
     * 用于底栏等需要与上方网格保持按键宽度一致的场景。
     */
    var forcedUnitWidth: Float? = null

    init {
        keyHeight = dp2px(KEY_HEIGHT_DP.toFloat())
        keyGap = dp2px(KEY_GAP_DP.toFloat())
        keyRadius = dp2px(KEY_RADIUS_DP)
        keyboardPadding = dp2px(KEYBOARD_PADDING_DP.toFloat())
        isHapticFeedbackEnabled = true
        rebuildPaints()
    }

    // ===== 主题 =====

    /**
     * 应用主题并刷新（由 Service 层在创建 / 切换主题时调用）
     */
    open fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        rebuildPaints()
        invalidate()
    }

    /** 根据当前主题重建画笔颜色 */
    protected open fun rebuildPaints() {
        boardBgPaint.color = theme.keyboardBackground
        keyBgPaint.color = theme.keyBackground
        pressedKeyBgPaint.color = theme.keyPressedBackground
        // 功能键：在按键色与边框色之间取中间值，形成层次
        funcKeyBgPaint.color = blendColor(theme.keyBackground, theme.borderColor, 0.55f)
        accentBgPaint.color = theme.candidateHighlightColor
        keyTextPaint.color = theme.keyTextColor
        funcTextPaint.color = theme.keyTextColor
        accentTextPaint.color = Color.WHITE
    }

    // ===== 布局测量 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = rows.size
        val scaledKeyHeight = keyHeight * keyHeightMultiplier
        val totalHeight = (rowCount * scaledKeyHeight +
                (rowCount - 1).coerceAtLeast(0) * keyGap +
                keyboardPadding * 2).toInt()
        setMeasuredDimension(width, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateKeyPositions()
    }

    /** 重新计算所有按键的位置 */
    protected open fun recalculateKeyPositions() {
        keyRects.clear()
        val availableWidth = width - keyboardPadding * 2

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            if (row.isEmpty()) continue
            val totalWeight = row.sumOf { it.width.toDouble() }.toFloat()
            val totalGapWidth = (row.size - 1) * keyGap
            val unitWidth = forcedUnitWidth
                ?: (availableWidth - totalGapWidth) / totalWeight

            var x = keyboardPadding + rowIndent(rowIndex, unitWidth)
            val y = keyboardPadding + rowIndex * (keyHeight * keyHeightMultiplier + keyGap)

            for (colIndex in row.indices) {
                val key = row[colIndex]
                val keyWidth = unitWidth * key.width
                val rect = RectF(x, y, x + keyWidth, y + keyHeight * keyHeightMultiplier)
                keyRects.add(KeyRect(key, rect, rowIndex, colIndex))
                x += keyWidth + keyGap
            }
        }
    }

    /**
     * 整行左侧缩进（单位：px）。默认无缩进，子类可覆写实现如 QWERTY 第二行半键缩进。
     */
    protected open fun rowIndent(rowIndex: Int, unitWidth: Float): Float = 0f

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint)
        for (i in keyRects.indices) {
            drawKey(canvas, keyRects[i], i == pressedKeyIndex)
        }
    }

    /** 绘制单个按键（背景 + 阴影 + 内容） */
    private fun drawKey(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val rect = keyRect.rect

        // 阴影（向下偏移 1dp）
        val shadowOffset = dp2px(1f)
        val shadowRect = RectF(rect.left, rect.top + shadowOffset, rect.right, rect.bottom + shadowOffset)
        canvas.drawRoundRect(shadowRect, keyRadius, keyRadius, keyShadowPaint)

        // 背景
        canvas.drawRoundRect(rect, keyRadius, keyRadius, backgroundPaintFor(keyRect.key, isPressed))

        // 内容
        drawKeyContent(canvas, keyRect, isPressed)
    }

    /**
     * 绘制按键内容（文字）。默认居中绘制 [getKeyDisplayText]。
     * 九宫格等需要多段文字的键盘可覆写本方法。
     */
    protected open fun drawKeyContent(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val key = keyRect.key
        val rect = keyRect.rect
        val textPaint = textPaintFor(key)
        val displayText = getKeyDisplayText(key)
        val textX = rect.centerX()
        val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(displayText, textX, textY, textPaint)
    }

    /** 选择按键背景画笔。默认：按下 > 功能键 > 普通键 */
    protected open fun backgroundPaintFor(key: Key, isPressed: Boolean): Paint = when {
        isPressed -> pressedKeyBgPaint
        key.isFunctional -> funcKeyBgPaint
        else -> keyBgPaint
    }

    /** 选择按键文字画笔。默认：功能键用小号字，其余用普通字 */
    protected open fun textPaintFor(key: Key): Paint =
        if (key.isFunctional) funcTextPaint else keyTextPaint

    /** 按键显示文本。默认返回 label，子类可根据状态覆写 */
    protected open fun getKeyDisplayText(key: Key): String = key.label

    // ===== 触摸处理 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 使用 actionMasked：多指触摸时 ACTION_POINTER_DOWN/UP 携带 pointer index，
        // 直接比较 action 会漏掉事件，导致按压状态残留、后续点击无响应。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = findKeyAt(event.x, event.y)
                if (index >= 0) {
                    pressedKeyIndex = index
                    keyRepeatFired = false
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // 可重复键：延迟启动连续触发（长按持续删除）
                    if (isRepeatableKey(keyRects[index].key)) {
                        postDelayed(keyRepeatRunnable, REPEAT_START_DELAY_MS)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = findKeyAt(event.x, event.y)
                if (index != pressedKeyIndex) {
                    // 手指滑离原按键：停止连续触发
                    cancelKeyRepeat()
                    pressedKeyIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelKeyRepeat()
                // 连续触发已执行过时，抬起不再补发一次按键，避免多删一个字符
                if (pressedKeyIndex >= 0 && event.actionMasked == MotionEvent.ACTION_UP && !keyRepeatFired) {
                    handleKeyUp(keyRects[pressedKeyIndex].key)
                }
                pressedKeyIndex = -1
                keyRepeatFired = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 清理触摸按压相关的瞬时状态（按下高亮、长按连续触发）。
     * 在视图脱离窗口或窗口失焦（如跳转设置页 / 词库下载页）时调用，
     * 避免残留的 pressedKeyIndex / 重复任务使返回输入框后按键表现异常。
     */
    private fun clearPressedState() {
        cancelKeyRepeat()
        if (pressedKeyIndex != -1) {
            pressedKeyIndex = -1
            invalidate()
        }
        keyRepeatFired = false
    }

    override fun onDetachedFromWindow() {
        clearPressedState()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // 失焦时手指的 ACTION_UP/CANCEL 可能不会再派发到本视图，主动清理按压状态
        if (!hasWindowFocus) {
            clearPressedState()
        }
    }

    /** 按键抬起处理（子类实现具体布局的输入逻辑） */
    protected abstract fun handleKeyUp(key: Key)

    /**
     * 处理各布局共用的自定义功能键（中英文切换、符号键盘）。
     * @return true 表示已处理
     */
    protected fun handleCommonKey(key: Key): Boolean = when (key.code) {
        KeyCode.KEYCODE_SWITCH_LANGUAGE -> {
            // 不在视图层预翻转 isChineseMode：引擎繁忙（如词库下载后重新部署）时
            // 预翻转会使显示与 ascii_mode 实际状态错位，统一由 Service 确认后回写
            onKeyPress?.invoke(KeyCode.KEYCODE_SWITCH_LANGUAGE, 0)
            true
        }
        KeyCode.KEYCODE_SYMBOL -> {
            onKeyPress?.invoke(KeyCode.KEYCODE_SYMBOL, 0)
            true
        }
        else -> false
    }

    /** 发送按键给 Rime */
    protected fun sendKey(keyCode: Int, mask: Int = 0) {
        onKeyPress?.invoke(keyCode, mask)
    }

    private fun findKeyAt(x: Float, y: Float): Int {
        for (i in keyRects.indices) {
            if (keyRects[i].rect.contains(x, y)) return i
        }
        return -1
    }

    // ===== 对外状态同步 =====

    /** 更新编码区显示（子类可覆写以格式化 preedit） */
    open fun updateComposition(composition: CompositionProto?) {
        compositionText = composition?.preedit
        invalidate()
    }

    /**
     * 以外部计算好的预览串直接更新编码区。
     * 由 Service 层推送与候选栏编码区同源的内容（如九宫格按候选读音还原的拼音），
     * 避免键盘视图自行格式化 preedit 导致两处编码显示不一致。
     */
    fun updateCompositionPreview(preview: String?) {
        compositionText = preview
        invalidate()
    }

    /** 设置中文模式状态（保留供 Java 调用；Kotlin 侧可直接对 [isChineseMode] 赋值） */
    fun updateChineseMode(chinese: Boolean) {
        isChineseMode = chinese
    }

    /**
     * 重置输入相关的临时状态（如九宫格未提交的多击预览）。
     * 由 Service 层在结束输入（onFinishInputView）时调用，默认无操作。
     */
    open fun resetInputState() {}

    // ===== 工具 =====

    protected fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density
    protected fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    private fun fillPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun textPaint(sizeSp: Float, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(sizeSp)
        textAlign = Paint.Align.CENTER
        typeface = tf
    }

    /** 按比例混合两种颜色（ratio 越大越偏向 color2） */
    private fun blendColor(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
