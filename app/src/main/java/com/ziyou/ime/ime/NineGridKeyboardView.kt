package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ViewTreeObserver
import com.ziyou.ime.core.CompositionProto

/**
 * 九宫格（T9）键盘视图 —— 智能九键
 *
 * 采用标准九宫格 4 行结构（3×3 数字键网格 + 右侧功能列 + 底栏）：
 *
 * [1]      [2 ABC]  [3 DEF]  [⌫]
 * [4 GHI]  [5 JKL]  [6 MNO]  [重输]
 * [7 PQRS] [8 TUV]  [9 WXYZ] [符]
 * [全/中]  [中/数]  [0]      [ 空格 ] [⏎]
 *
 * 其中前三行为标准 T9 数字键网格，逐行读作 123 / 456 / 789。
 * 底栏横跨全宽，包含中英文切换、中文/数字切换、数字 0、空格和换行/确认。
 *
 * 输入逻辑（智能九键，非多击）：
 * - 每个字母键单击一次，向 Rime 发送对应「数字键」字符（2-9）。
 * - t9.schema.yaml 的 speller/algebra 已把拼音字母派生出数字写法
 *   （2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz），
 *   因此输入 4-8-6 会命中 guo/gun/huo/hun 等所有可能拼音，由引擎消歧。
 * - 「重输」键发送 Escape，清除当前编码重新开始输入。
 * - 拼音候选由 Service 层依据 Rime 输入状态实时填充，展示于左侧拼音侧栏。
 */
class NineGridKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 数字文字大小（sp）— 字母键上方主数字 */
        private const val DIGIT_TEXT_SIZE_SP = 20f
        /** 字母文字大小（sp）— 字母键下方字母序列 */
        private const val LETTER_TEXT_SIZE_SP = 11f
        /** Escape 键值（用于重输功能） */
        private const val ESCAPE_CODE = 0xff1b
        /** 九宫格按键高度比标准键盘增加 30%，使 4 行布局的按键更大更好点击 */
        private const val T9_HEIGHT_FACTOR = 1.3f
    }

    /** 九宫格使用更高的按键以适应 4 行布局 */
    override val keyHeightMultiplier: Float = T9_HEIGHT_FACTOR

    // ===== 布局定义（标准 T9 九宫格：3×3 数字键网格 + 右侧功能列 + 底栏）=====
    // 行 1~3 权重总和均为 3.6，中间三列宽度一致，确保数字键网格纵向对齐。

    /** 第 1 行：数字键 1 / 2ABC / 3DEF + 退格 */
    private val row1 = listOf(
        Key("1", '1'.code, 1f),
        Key("2", '2'.code, 1f, letters = "abc"),
        Key("3", '3'.code, 1f, letters = "def"),
        Key("\u232B", KeyCode.XK_BackSpace, 0.6f, isFunctional = true)
    )

    /** 第 2 行：数字键 4GHI / 5JKL / 6MNO + 重输 */
    private val row2 = listOf(
        Key("4", '4'.code, 1f, letters = "ghi"),
        Key("5", '5'.code, 1f, letters = "jkl"),
        Key("6", '6'.code, 1f, letters = "mno"),
        Key("重输", ESCAPE_CODE, 0.6f, isFunctional = true)
    )

    /** 第 3 行：数字键 7PQRS / 8TUV / 9WXYZ + 符号 */
    private val row3 = listOf(
        Key("7", '7'.code, 1f, letters = "pqrs"),
        Key("8", '8'.code, 1f, letters = "tuv"),
        Key("9", '9'.code, 1f, letters = "wxyz"),
        Key("符", KeyCode.KEYCODE_SYMBOL, 0.6f, isFunctional = true)
    )

    /** 第 4 行（底栏）：中英转换 + 中数切换 + 数字 0 + 空格 + 换行/确认 */
    private val row4 = listOf(
        Key("英", KeyCode.KEYCODE_SWITCH_LANGUAGE, 0.8f, isFunctional = true),
        Key("数", KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0.8f, isFunctional = true),
        Key("0", '0'.code, 0.8f),
        Key("空格", KeyCode.XK_space, 1.4f),
        Key("\u23CE", KeyCode.XK_Return, 0.8f, isFunctional = true)
    )

    override var rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    /** 所有行（包括底栏），供外部控制显示行数 */
    private val allGridRows = listOf(row1, row2, row3, row4)

    /**
     * 设置显示的网格行数（1-4）。
     * 底栏（第 4 行）被剥离时，由独立的 NineGridBottomBarView 全宽渲染。
     */
    fun setGridRowCount(count: Int) {
        rows = allGridRows.take(count)
    }

    // ===== 网格宽度单元值（供底栏对齐用） =====

    /**
     * 3×3 数字网格的按键宽度单元值（px），由 recalculateKeyPositions 自动计算。
     * 底栏视图通过读取此值设置 forcedUnitWidth，使底栏按键宽度与网格保持一致。
     */
    var gridUnitWidth: Float? = null
        private set

    override fun recalculateKeyPositions() {
        super.recalculateKeyPositions()
        val availableWidth = width - keyboardPadding * 2
        if (availableWidth > 0f) {
            val totalWeight = row1.sumOf { it.width.toDouble() }.toFloat()
            val totalGapWidth = (row1.size - 1) * keyGap
            gridUnitWidth = (availableWidth - totalGapWidth) / totalWeight
        }
    }

    // ===== 画笔 =====

    /** 字母键下方的字母序列画笔（小字、半透明） */
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(LETTER_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
        alpha = 160
    }

    /** 字母键上方的数字画笔（大字、醒目） */
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(DIGIT_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
    }

    override fun applyTheme(newTheme: com.ziyou.ime.config.KeyboardTheme) {
        super.applyTheme(newTheme)
        letterPaint.apply { color = theme.keyTextColor; alpha = 160 }
        digitPaint.color = theme.keyTextColor
    }

    // ===== 显示文本 =====

    override fun getKeyDisplayText(key: Key): String = when (key.code) {
        KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
        KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> if (isChineseMode) "中" else "数"
        else -> key.label
    }

    // ===== 按键渲染（三种样式）=====

    /**
     * 根据按键类型绘制不同样式的内容：
     * 1. 字母键（letters != null）：上方大数字 + 下方小字母序列
     * 2. 纯数字键（1 / 0）：居中大数字
     * 3. 其他键：默认居中文字（由基类处理）
     */
    override fun drawKeyContent(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val key = keyRect.key
        val letters = key.letters
        val rect = keyRect.rect

        // ── 字母键：数字（大）居中偏上 + 字母序列（小）在下方 ──
        if (letters != null) {
            val cx = rect.centerX()
            val digitY = rect.centerY() - (digitPaint.descent() + digitPaint.ascent()) / 2f - dp2px(5f)
            canvas.drawText(key.label, cx, digitY, digitPaint)
            val letterY = digitY + dp2px(14f)
            canvas.drawText(letters.uppercase(), cx, letterY, letterPaint)
            return
        }

        // ── 纯数字键（1 / 0）：居中大数字，与字母键的数字保持一致的醒目样式 ──
        if (isPlainDigitKey(key)) {
            val cy = rect.centerY() - (digitPaint.descent() + digitPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), cy, digitPaint)
            return
        }

        // ── 其他按键：基类默认渲染 ──
        super.drawKeyContent(canvas, keyRect, isPressed)
    }

    // ===== 按键处理 =====

    override fun handleKeyUp(key: Key) {
        // 字母键：直接发送对应数字键给 Rime，由 T9 方案消歧
        if (key.letters != null) {
            sendKey(key.code, 0)
            return
        }

        when (key.code) {
            // 中英转换：强制英文模式并切到 26 键全键盘（不走 onKeyPress 异步路径）
            KeyCode.KEYCODE_SWITCH_LANGUAGE -> {
                isChineseMode = false
                onSwitchToQwertyEnglish?.invoke()
                invalidate()
            }
            // 中文/数字模式切换：不预翻转 isChineseMode，由 Service 确认 ascii_mode 后回写，
            // 避免引擎繁忙（如词库下载后重新部署）时视图与引擎状态错位
            KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> {
                onKeyPress?.invoke(KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0)
            }
            // 重输：发送 Escape 清除当前编码
            ESCAPE_CODE -> sendKey(KeyCode.XK_Escape, 0)
            // 其他功能键（符号、退格、换行等）
            else -> {
                if (handleCommonKey(key)) return
                sendKey(key.code, 0)
            }
        }
    }

    // ===== 编码显示格式化 =====

    /**
     * 编码区兼容入口（仅兜底）：
     * 九宫格的编码预览由 Service 层统一计算（PinyinHintProvider.buildPreview，
     * 按候选读音+实际击键还原）并经 updateCompositionPreview 推送，
     * 与候选栏编码区同源，避免本视图自行格式化导致两处拼音不一致。
     * 本覆写仅在无预览（如编码已清空）时被调用，降级展示小写 preedit。
     */
    override fun updateComposition(composition: CompositionProto?) {
        if (composition != null && !composition.preedit.isNullOrEmpty()) {
            compositionText = composition.preedit?.lowercase()
            invalidate()
        } else {
            super.updateComposition(composition)
        }
    }

    // ===== 辅助方法 =====

    /** 判断是否为纯数字键（1 / 0，无字母、非标点键） */
    private fun isPlainDigitKey(key: Key): Boolean =
        key.letters == null && key.label.length == 1 && key.label[0].isDigit()
}

/**
 * 九宫格底栏视图（全宽单行）。
 *
 * 底栏包含：全键盘切换、中英文切换、数字 0、空格、回车。
 * 与上方 3×3 网格分离为独立视图，以便：
 * - 侧栏高度只匹配上方三行（底部对齐数字键行）
 * - 底栏横跨屏幕全宽，延伸至屏幕最左侧边缘
 *
 * 通过 [forcedUnitWidth] 强制按键宽度单元值与上方网格一致，
 * 确保底栏按键在视觉上与网格对齐。
 */
class NineGridBottomBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    /** 底栏行：中英转换 + 中数切换 + 数字 0 + 空格 + 回车 */
    private val bottomRow = listOf(
        Key("英", KeyCode.KEYCODE_SWITCH_LANGUAGE, 0.8f, isFunctional = true),
        Key("数", KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0.8f, isFunctional = true),
        Key("0", '0'.code, 0.8f),
        Key("空格", KeyCode.XK_space, 1.4f),
        Key("\u23CE", KeyCode.XK_Return, 0.8f, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(bottomRow)

    override fun getKeyDisplayText(key: Key): String = when (key.code) {
        KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
        KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> if (isChineseMode) "中" else "数"
        else -> key.label
    }

    override fun handleKeyUp(key: Key) {
        when (key.code) {
            // 中英转换：强制英文模式并切到 26 键全键盘（不走 onKeyPress 异步路径）
            KeyCode.KEYCODE_SWITCH_LANGUAGE -> {
                isChineseMode = false
                onSwitchToQwertyEnglish?.invoke()
                invalidate()
            }
            // 中文/数字模式切换：不预翻转 isChineseMode，由 Service 确认 ascii_mode 后回写，
            // 避免引擎繁忙（如词库下载后重新部署）时视图与引擎状态错位
            KeyCode.KEYCODE_SWITCH_NUMBER_MODE -> {
                onKeyPress?.invoke(KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0)
            }
            else -> {
                if (handleCommonKey(key)) return
                sendKey(key.code, 0)
            }
        }
    }
}
