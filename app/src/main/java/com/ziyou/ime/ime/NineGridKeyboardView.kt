package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ViewTreeObserver

/**
 * 九宫格（T9）键盘视图 —— 智能九键
 *
 * 采用标准九宫格 4 行结构（3×3 字母键网格 + 右侧功能列 + 底行）：
 *
 * [分词]  [ABC]  [DEF]   [⌫]
 * [GHI]   [JKL]  [MNO]   [重输]
 * [PQRS]  [TUV]  [WXYZ]  [换行]
 * [123]   [空格]        [中/英] └ 跨第 3~4 行
 *
 * 注：技能面板「技」与悬浮切换「浮」已移至候选区按钮栏 [CandidateToolbarView]；
 * 停靠形态下标点（，。？！）与「符号」入口位于左侧 [PinyinSideBarView]。
 *
 * 其中前三行为标准 T9 键网格，键面只显示字母序列（数字写法由 T9 方案内部消歧），
 * 逐行读作 123 / 456 / 789；右侧功能列的「换行」键纵向跨第 3~4 行。
 * 第 4 行只占据左侧三列宽度，与网格列严格对齐。
 *
 * 输入逻辑（智能九键，非多击）：
 * - 每个字母键单击一次，向 Rime 发送对应「数字键」字符（2-9）。
 * - t9.schema.yaml 的 speller/algebra 已把拼音字母派生出数字写法
 *   （2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz），
 *   因此输入 4-8-6 会命中 guo/gun/huo/hun 等所有可能拼音，由引擎消歧。
 * - 「重输」键发送 Escape，清除当前编码重新开始输入。
 * - 「分词」键发送撇号 '（t9.schema.yaml 的 speller/delimiter 包含 ' ），
 *   手动分隔音节以消歧（如 42' + 6 区分 gan/gam 类粘连）；无编码时为空操作。
 * - 拼音候选由 Service 层依据 Rime 输入状态实时填充，展示于左侧拼音侧栏。
 */
class NineGridKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 字母键文字大小（sp）— 键面居中的字母序列（ABC / PQRS 等） */
        private const val LETTER_TEXT_SIZE_SP = 16f
        /** 分词键文字大小（sp）— 与字母键保持一致 */
        private const val SEPARATOR_TEXT_SIZE_SP = 16f
        /** 底行按键（123 / 空格 / 中英 / 符）文字大小（sp）— 小于字母键，避免汉字显得臃大 */
        private const val BOTTOM_TEXT_SIZE_SP = 14f
        /** Escape 键值（用于重输功能） */
        private const val ESCAPE_CODE = 0xff1b
        /** 九宫格按键高度倍率：4 行均在本视图内渲染（底行已合入网格），
         *  因此只需少量加高便于点击，避免整体键盘过高挤占屏幕 */
        private const val T9_HEIGHT_FACTOR = 1.08f
        /** 右侧功能列相对宽度（与截图版式一致，略窄于字母列） */
        private const val FUNC_COL_WIDTH = 0.8f
    }

    /** 九宫格按键略高于标准键盘（四行布局，高度适度不挤占屏幕） */
    override val keyHeightMultiplier: Float = T9_HEIGHT_FACTOR

    // ===== 布局定义（3×3 字母键网格 + 右侧功能列 + 左侧三列底行）=====
    // 行 1~3 权重总和均为 3.8（3 × 字母列 + 0.8 功能列），确保字母键网格纵向对齐。

    /** 第 1 行：分词键（T9 的 1 键位，发送音节分隔符 '）/ ABC / DEF + 退格 */
    private val row1 = listOf(
        Key("分词", '\''.code, 1f),
        Key("2", '2'.code, 1f, letters = "abc"),
        Key("3", '3'.code, 1f, letters = "def"),
        Key("\u232B", KeyCode.XK_BackSpace, FUNC_COL_WIDTH, isFunctional = true)
    )

    /** 第 2 行：GHI / JKL / MNO + 重输 */
    private val row2 = listOf(
        Key("4", '4'.code, 1f, letters = "ghi"),
        Key("5", '5'.code, 1f, letters = "jkl"),
        Key("6", '6'.code, 1f, letters = "mno"),
        Key("重输", ESCAPE_CODE, FUNC_COL_WIDTH, isFunctional = true)
    )

    /** 第 3 行：PQRS / TUV / WXYZ + 换行（纵向跨第 3~4 行） */
    private val row3 = listOf(
        Key("7", '7'.code, 1f, letters = "pqrs"),
        Key("8", '8'.code, 1f, letters = "tuv"),
        Key("9", '9'.code, 1f, letters = "wxyz"),
        Key("换行", KeyCode.XK_Return, FUNC_COL_WIDTH, isFunctional = true, heightSpan = 2)
    )

    /** 第 4 行（停靠形态）：中数转换（切数字键盘） + 空格 + 中英转换。
     *  只占据左侧三列宽度（右侧由跨行的「换行」键占用），权重合计 3.0；
     *  「符号」入口与标点位于左侧 [PinyinSideBarView]。 */
    private val row4Docked = listOf(
        Key("123", KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0.8f),
        Key("空格", KeyCode.XK_space, 1.5f),
        Key("英", KeyCode.KEYCODE_SWITCH_LANGUAGE, 0.7f)
    )

    /** 第 4 行（悬浮形态）：无侧栏，故在底行内保留「符号」入口。
     *  4 键多一个间距，权重合计取 2.9 以维持与三列区域近似等宽。 */
    private val row4Floating = listOf(
        Key("符", KeyCode.KEYCODE_SYMBOL, 0.55f, isFunctional = true),
        Key("123", KeyCode.KEYCODE_SWITCH_NUMBER_MODE, 0.55f),
        Key("空格", KeyCode.XK_space, 1.2f),
        Key("英", KeyCode.KEYCODE_SWITCH_LANGUAGE, 0.6f)
    )

    private var row4 = row4Docked

    override var rows: List<List<Key>> = listOf(row1, row2, row3, row4)

    /**
     * 选择第 4 行（底行）变体：悬浮形态无左侧栏，底行需自带「符号」入口。
     */
    fun setFloatingLayout(floating: Boolean) {
        row4 = if (floating) row4Floating else row4Docked
        rows = listOf(row1, row2, row3, row4)
        recalculateKeyPositions()
        requestLayout()
        invalidate()
    }

    // ===== 网格尺寸（供左侧栏对齐用） =====

    /**
     * 3×3 字母网格的按键宽度单元值（px），由 recalculateKeyPositions 自动计算。
     */
    var gridUnitWidth: Float? = null
        private set

    /** 单行按键高度（px，含高度倍率），供侧栏底部「符号」键对齐 */
    val gridRowHeight: Float
        get() = keyHeight * keyHeightMultiplier

    /** 网格首行按键顶部的 y 偏移（px），供侧栏列表顶部对齐 */
    val gridTop: Float
        get() = keyboardPadding

    /** 网格行间距（px），供侧栏列表与底部「符号」键之间的间距对齐 */
    val gridRowGap: Float
        get() = keyGap

    /** 第 4 行（底行）顶部相对本视图的 y 偏移（px），供侧栏底部「符号」键对齐 */
    val bottomRowTop: Float
        get() = keyboardPadding + 3 * (gridRowHeight + keyGap)

    override fun recalculateKeyPositions() {
        super.recalculateKeyPositions()
        val availableWidth = width - keyboardPadding * 2
        if (availableWidth > 0f) {
            gridUnitWidth = unitWidthOf(availableWidth)
        }
    }

    /**
     * 底行沿用行 1~3 的宽度单元值，使 [123][空格][中英] 与上方三列严格对齐，
     * 右侧区域留给跨行的「换行」键。
     */
    override fun rowUnitWidth(rowIndex: Int, availableWidth: Float): Float? =
        if (rowIndex == 3 && availableWidth > 0f) unitWidthOf(availableWidth) else null

    /** 以行 1（4 键 / 权重 3.8）为基准计算宽度单元值 */
    private fun unitWidthOf(availableWidth: Float): Float {
        val totalWeight = row1.sumOf { it.width.toDouble() }.toFloat()
        val totalGapWidth = (row1.size - 1) * keyGap
        return (availableWidth - totalGapWidth) / totalWeight
    }

    // ===== 画笔 =====

    /** 字母键的字母序列画笔（键面主文字，居中） */
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(LETTER_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = skin.keyTextColor
    }

    /** 分词键画笔 */
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(SEPARATOR_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = skin.keyTextColor
    }

    /** 底行按键文字画笔（123 / 空格 / 中英 / 符，字号小于字母键） */
    private val bottomTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(BOTTOM_TEXT_SIZE_SP)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = skin.keyTextColor
    }

    override fun applySkin(newSkin: com.ziyou.ime.skin.SkinTheme) {
        super.applySkin(newSkin)
        letterPaint.apply { color = skin.keyTextColor; typeface = skin.keyTypeface }
        separatorPaint.apply { color = skin.keyTextColor; typeface = skin.keyTypeface }
        bottomTextPaint.apply { color = skin.keyTextColor; typeface = skin.textTypeface }
    }

    /** 悬浮缩放时同步九宫格自有画笔（字母/分词/底行）的文字大小 */
    override fun onScaleChanged() {
        letterPaint.textSize = sp2px(LETTER_TEXT_SIZE_SP)
        separatorPaint.textSize = sp2px(SEPARATOR_TEXT_SIZE_SP)
        bottomTextPaint.textSize = sp2px(BOTTOM_TEXT_SIZE_SP)
    }

    /** 底行按键用小号字（汉字居多），其余沿用基类选择 */
    override fun textPaintFor(key: Key): Paint =
        if (row4.contains(key)) bottomTextPaint else super.textPaintFor(key)

    // ===== 显示文本 =====

    override fun getKeyDisplayText(key: Key): String = when (key.code) {
        KeyCode.KEYCODE_SWITCH_LANGUAGE -> if (isChineseMode) "中" else "英"
        else -> key.label
    }

    // ===== 按键渲染（三种样式）=====

    /**
     * 根据按键类型绘制不同样式的内容：
     * 1. 字母键（letters != null）：居中大写字母序列（ABC / PQRS）
     * 2. 分词键：居中绘制，与字母键保持一致的字重
     * 3. 其他键：默认居中文字（由基类处理）
     */
    override fun drawKeyContent(canvas: Canvas, keyRect: KeyRect, isPressed: Boolean) {
        val key = keyRect.key
        val letters = key.letters
        val rect = keyRect.rect

        // ── 字母键：居中大写字母序列 ──
        if (letters != null) {
            val cy = rect.centerY() - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText(letters.uppercase(), rect.centerX(), cy, letterPaint)
            return
        }

        // ── 分词键：居中绘制，与字母键字重一致 ──
        if (isSeparatorKey(key)) {
            val cy = rect.centerY() - (separatorPaint.descent() + separatorPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), cy, separatorPaint)
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
            // 中数转换：切换到数字键盘布局（临时面板，由 Service 记录进入前布局，
            // 面板内「返回」键恢复九宫格）
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

    // ===== 辅助方法 =====

    /** 判断是否为分词键（发送音节分隔符 '，即 T9 的 1 键位） */
    private fun isSeparatorKey(key: Key): Boolean =
        key.letters == null && key.code == '\''.code
}
