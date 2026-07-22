package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import com.ziyou.ime.core.CompositionProto
import com.ziyou.ime.util.T9PinYinUtils

/**
 * 九宫格（T9）键盘视图 —— 智能九键
 *
 * 采用标准九宫格 4 行 × 5 列结构（3×3 数字键网格 + 左侧标点列 + 右侧功能列）：
 *
 * [，] [1]      [2 ABC]  [3 DEF]  [⌫]
 * [。] [4 GHI]  [5 JKL]  [6 MNO]  [重输]
 * [！] [7 PQRS] [8 TUV]  [9 WXYZ] [符]
 * [全] [中/英]  [0]      [ 空格 ] [？]
 *
 * 其中中间三列为标准 T9 数字键网格，逐行读作 123 / 456 / 789。
 *
 * 输入逻辑（智能九键，非多击）：
 * - 每个字母键单击一次，向 Rime 发送对应「数字键」字符（2-9）。
 * - t9.schema.yaml 的 speller/algebra 已把拼音字母派生出数字写法
 *   （2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz），
 *   因此输入 4-8-6 会命中 guo/gun/huo/hun 等所有可能拼音，由引擎消歧。
 * - 「重输」键发送 Escape，清除当前编码重新开始输入。
 * - 左侧标点键（，。！）在中文模式下由引擎自动映射为中文标点。
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
        /** 侧栏主字符大小（sp）— 标点符号 */
        private const val SIDE_MAIN_TEXT_SIZE_SP = 18f
        /** 侧栏副标签大小（sp）— 标点键上方的英文对应 */
        private const val SIDE_SUB_TEXT_SIZE_SP = 9f
        /** Escape 键值（用于重输功能） */
        private const val ESCAPE_CODE = 0xff1b
        /** 九宫格按键高度比标准键盘增加 30%，使 4 行布局的按键更大更好点击 */
        private const val T9_HEIGHT_FACTOR = 1.3f
    }

    /** 九宫格使用更高的按键以适应 4 行布局 */
    override val keyHeightMultiplier: Float = T9_HEIGHT_FACTOR

    // ===== 布局定义（标准 T9 九宫格：3×3 数字键网格 + 左标点列 + 右功能列）=====
    // 行 1~3 权重总和均为 4.6，中间三列宽度一致，确保数字键网格纵向对齐。

    /** 第 1 行：标点 ， + 数字键 1 / 2ABC / 3DEF + 退格 */
    private val row1 = listOf(
        Key("，", ','.code, 0.7f, isFunctional = true),
        Key("1", '1'.code, 1f),
        Key("2", '2'.code, 1f, letters = "abc"),
        Key("3", '3'.code, 1f, letters = "def"),
        Key("\u232B", KeyCode.XK_BackSpace, 0.9f, isFunctional = true)
    )

    /** 第 2 行：标点 。 + 数字键 4GHI / 5JKL / 6MNO + 重输 */
    private val row2 = listOf(
        Key("。", '.'.code, 0.7f, isFunctional = true),
        Key("4", '4'.code, 1f, letters = "ghi"),
        Key("5", '5'.code, 1f, letters = "jkl"),
        Key("6", '6'.code, 1f, letters = "mno"),
        Key("重输", ESCAPE_CODE, 0.9f, isFunctional = true)
    )

    /** 第 3 行：标点 ！ + 数字键 7PQRS / 8TUV / 9WXYZ + 符号 */
    private val row3 = listOf(
        Key("！", '!'.code, 0.7f, isFunctional = true),
        Key("7", '7'.code, 1f, letters = "pqrs"),
        Key("8", '8'.code, 1f, letters = "tuv"),
        Key("9", '9'.code, 1f, letters = "wxyz"),
        Key("符", KeyCode.KEYCODE_SYMBOL, 0.9f, isFunctional = true)
    )

    /** 第 4 行：切全键盘 + 中英文 + 数字 0 + 空格 + 标点 ？ */
    private val row4 = listOf(
        Key("全", KeyCode.KEYCODE_SWITCH_KEYBOARD, 0.7f, isFunctional = true),
        Key("中", KeyCode.KEYCODE_SWITCH_LANGUAGE, 0.9f, isFunctional = true),
        Key("0", '0'.code, 0.9f),
        Key("空格", KeyCode.XK_space, 1.2f),
        Key("？", '?'.code, 0.9f, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

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

    /** 侧栏标点键的主字符画笔 */
    private val sideMainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(SIDE_MAIN_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
    }

    /** 侧栏标点键的副标签画笔（英文标点提示，半透明） */
    private val sideSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(SIDE_SUB_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
        alpha = 120
    }

    override fun applyTheme(newTheme: com.ziyou.ime.config.KeyboardTheme) {
        super.applyTheme(newTheme)
        letterPaint.apply { color = theme.keyTextColor; alpha = 160 }
        digitPaint.color = theme.keyTextColor
        sideMainPaint.color = theme.keyTextColor
        sideSubPaint.apply { color = theme.keyTextColor; alpha = 120 }
    }

    // ===== 显示文本 =====

    override fun getKeyDisplayText(key: Key): String = when (key.code) {
        KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
        else -> key.label
    }

    // ===== 按键渲染（三种样式）=====

    /**
     * 根据按键类型绘制不同样式的内容：
     * 1. 字母键（letters != null）：上方大数字 + 下方小字母序列
     * 2. 侧栏标点键（，。！？）：上方英文小标签 + 下方中文标点
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

        // ── 侧栏标点键：英文小标签 + 中文标点主字符 ──
        if (isPunctuationSideKey(key)) {
            val cx = rect.centerX()
            val subLabel = englishPunctLabel(key.label)
            if (subLabel != null) {
                // 上方小标签
                val subY = rect.centerY() - dp2px(6f) - (sideSubPaint.descent() + sideSubPaint.ascent()) / 2f
                canvas.drawText(subLabel, cx, subY, sideSubPaint)
            }
            // 下方主字符
            val mainY = rect.centerY() + dp2px(4f) - (sideMainPaint.descent() + sideMainPaint.ascent()) / 2f
            canvas.drawText(getKeyDisplayText(key), cx, mainY, sideMainPaint)
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
            // 切换到全键盘
            KeyCode.KEYCODE_SWITCH_KEYBOARD -> onSwitchKeyboard?.invoke(KeyboardType.QWERTY)
            // 重输：发送 Escape 清除当前编码
            ESCAPE_CODE -> sendKey(KeyCode.XK_Escape, 0)
            // 其他功能键（中英文、符号、标点、退格等）
            else -> {
                if (handleCommonKey(key)) return
                sendKey(key.code, 0)
            }
        }
    }

    // ===== 编码显示格式化 =====

    /**
     * 覆写编码显示：九宫格模式下将 T9 数字编码格式化为可读拼音。
     * 由于键盘视图层无法获取候选词 comment，采用 lowercase 降级显示。
     * 当编码区移至候选栏后，此处的 compositionText 作为备用数据源保留。
     */
    override fun updateComposition(composition: CompositionProto?) {
        if (composition != null && !composition.preedit.isNullOrEmpty()) {
            val preedit = composition.preedit ?: ""
            // 使用 T9PinYinUtils 格式化；comment 为空时退化为原始编码
            val formatted = T9PinYinUtils.getT9Composition(preedit, "")
            compositionText = formatted.lowercase()
            invalidate()
        } else {
            super.updateComposition(composition)
        }
    }

    // ===== 辅助方法 =====

    /** 判断是否为纯数字键（1 / 0，无字母、非标点键） */
    private fun isPlainDigitKey(key: Key): Boolean =
        key.letters == null && key.label.length == 1 && key.label[0].isDigit()

    /** 判断是否为左侧标点键（中文标点字符） */
    private fun isPunctuationSideKey(key: Key): Boolean =
        key.label in setOf("，", "。", "！")

    /** 中文标点 → 英文标点副标签映射（显示在标点键上方作为提示） */
    private fun englishPunctLabel(cnLabel: String): String? = when (cnLabel) {
        "，" -> ","
        "。" -> "."
        "！" -> "!"
        else -> null
    }
}
