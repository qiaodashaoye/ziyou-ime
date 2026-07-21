package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet

/**
 * 九宫格（T9）键盘视图 —— 智能九键
 *
 * 继承 [BaseKeyboardView]，实现标准电话键盘布局与「单击数字键」智能输入：
 *
 * 标准 T9 布局（左三列为电话键盘字母区，右列为功能键）：
 * [分词]  [2 ABC] [3 DEF]  [⌫]
 * [4 GHI] [5 JKL] [6 MNO]  [中/英]
 * [7 PQRS][8 TUV] [9 WXYZ] [符]
 * [全]    [   空格   ]      [↵]
 *
 * 输入逻辑（智能九键，非多击）：
 * - 每个字母键单击一次，向 Rime 发送对应「数字键」字符（2-9）。
 * - t9.schema.yaml 的 speller/algebra 已把拼音字母派生出数字写法
 *   （2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz），
 *   因此输入 4-8-6 会命中 guo/gun/huo/hun 等所有可能拼音，由引擎消歧。
 * - 「分词」键发送撇号（'），用于手动切分音节。
 * - 拼音候选区由 Service 层依据 Rime 候选的 spelling_hints（拼音 comment）实时填充，
 *   与 [SimpleCandidatesView] 协同展示，无需键盘侧维护预览状态。
 */
class NineGridKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 字母文字大小（sp） */
        private const val LETTER_TEXT_SIZE_SP = 11f
        /** 数字文字大小（sp） */
        private const val DIGIT_TEXT_SIZE_SP = 20f
        /** 分词键发送的字符：撇号，作为 Rime 音节分隔符 */
        private const val DELIMITER_CODE = '\''.code
    }

    // ===== 标准 T9 布局定义 =====
    private val row1 = listOf(
        Key("分词", DELIMITER_CODE, 1f, isFunctional = true),
        Key("2", '2'.code, 1f, letters = "abc"),
        Key("3", '3'.code, 1f, letters = "def"),
        Key("⌫", KeyCode.XK_BackSpace, 1f, isFunctional = true)
    )

    private val row2 = listOf(
        Key("4", '4'.code, 1f, letters = "ghi"),
        Key("5", '5'.code, 1f, letters = "jkl"),
        Key("6", '6'.code, 1f, letters = "mno"),
        Key("中", KeyCode.KEYCODE_SWITCH_LANGUAGE, 1f, isFunctional = true)
    )

    private val row3 = listOf(
        Key("7", '7'.code, 1f, letters = "pqrs"),
        Key("8", '8'.code, 1f, letters = "tuv"),
        Key("9", '9'.code, 1f, letters = "wxyz"),
        Key("符", KeyCode.KEYCODE_SYMBOL, 1f, isFunctional = true)
    )

    private val row4 = listOf(
        Key("全", KeyCode.KEYCODE_SWITCH_KEYBOARD, 1f, isFunctional = true),
        Key("空格", KeyCode.XK_space, 2f),
        Key("↵", KeyCode.XK_Return, 1f, isFunctional = true)
    )

    override val rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    // ===== 字母键专用画笔（颜色随主题） =====
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(LETTER_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
        alpha = 170
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(DIGIT_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = theme.keyTextColor
    }

    override fun applyTheme(newTheme: com.ziyou.ime.config.KeyboardTheme) {
        super.applyTheme(newTheme)
        letterPaint.color = theme.keyTextColor
        letterPaint.alpha = 170
        digitPaint.color = theme.keyTextColor
    }

    // ===== 文字 =====
    override fun getKeyDisplayText(key: Key): String =
        if (key.code == KeyCode.KEYCODE_SWITCH_LANGUAGE) {
            if (isChineseMode) "中" else "英"
        } else {
            key.label
        }

    /** 字母键渲染为「数字（大） + 字母序列（小）」的组合 */
    override fun drawKeyContent(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val key = keyRect.key
        val letters = key.letters
        if (letters == null) {
            super.drawKeyContent(canvas, keyRect, isPressed)
            return
        }

        val rect = keyRect.rect
        val cx = rect.centerX()
        // 数字：偏上居中
        val digitY = rect.centerY() - (digitPaint.descent() + digitPaint.ascent()) / 2f - dp2px(6f)
        canvas.drawText(key.label, cx, digitY, digitPaint)
        // 字母：数字下方
        val letterY = digitY + dp2px(14f)
        canvas.drawText(letters.uppercase(), cx, letterY, letterPaint)
    }

    // ===== 按键处理（单击即发送） =====
    override fun handleKeyUp(key: Key) {
        // 字母键：直接发送对应数字键给 Rime，由方案消歧
        if (key.letters != null) {
            sendKey(key.code, 0)
            return
        }

        when (key.code) {
            // 切换回全键盘
            KeyCode.KEYCODE_SWITCH_KEYBOARD -> onSwitchKeyboard?.invoke(KeyboardType.QWERTY)
            // 分词键、退格、空格、回车等：直接发送对应键值
            else -> {
                if (handleCommonKey(key)) return
                sendKey(key.code, 0)
            }
        }
    }
}
