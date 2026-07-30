package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ziyou.ime.data.SideSymbol
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinTheme

/**
 * 九宫格拼音侧栏视图（键盘左侧竖排）。
 *
 * 这是 ziyou-ime 对 yuyansdk 中 `T9TextContainer` / `CandidatesContainer` 左侧拼音选择栏
 * （`SwipeRecyclerView` + `PrefixAdapter` + `updatePrefixsView()`）的**等价实现**，
 * 但遵循本项目 Canvas 纯绘制的既有风格（见 [BaseKeyboardView] / [SimpleCandidatesView]），
 * 不引入 RecyclerView 依赖。
 *
 * 两种展示模式（对应 yuyansdk `updatePrefixsView()` 的 `isPrefixs` 分支）：
 *
 * 1. **拼音模式**（存在候选拼音）：竖排展示可点击拼音（如 guo / gun / huo / hun），
 *    点击回调 [onPinyinSelect]；由 Service 将编码中对应的 T9 键序列替换为该拼音。
 *    —— 等价 yuyansdk 中 `inputView.selectPrefix(position)`。
 *
 * 2. **符号模式**（无候选拼音）：展示用户自定义侧栏符号 [setSideSymbols]，点击回调
 *    [onSymbolInput] 直接上屏；列表末尾附「＋」页脚，点击回调 [onAddSymbol] 进入自定义管理。
 *    —— 等价 yuyansdk 中 footer(`mLlAddSymbol`) + `responseKeyEvent(SoftKey(label))`。
 */
@SuppressLint("ViewConstructor")
class PinyinSideBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 单元高度（dp） */
        private const val ITEM_HEIGHT_DP = 40f
        /** 单元间距（dp） */
        private const val ITEM_MARGIN_DP = 3f
        /** 单元圆角（dp） */
        private const val ITEM_RADIUS_DP = 6f
        /** 文字大小（sp） */
        private const val TEXT_SIZE_SP = 15f
        /** 「＋」页脚符号 */
        private const val ADD_FOOTER_LABEL = "\uFF0B"
    }

    /** 点击拼音回调（拼音模式）：参数为所选拼音字符串 */
    var onPinyinSelect: ((pinyin: String) -> Unit)? = null

    /** 点击侧栏符号回调（符号模式）：参数为要上屏的内容 */
    var onSymbolInput: ((value: String) -> Unit)? = null

    /** 点击「＋」页脚回调：进入侧栏符号自定义管理 */
    var onAddSymbol: (() -> Unit)? = null

    private enum class Mode { PINYIN, SYMBOL }

    private var mode: Mode = Mode.SYMBOL

    /** 拼音模式下展示的候选拼音 */
    private var pinyins: List<String> = emptyList()

    /** 符号模式下展示的自定义符号 */
    private var sideSymbols: List<SideSymbol> = emptyList()

    private var skin: SkinTheme = SkinManager.getCurrentSkin(context)

    /** 每个可点击单元的矩形（含末尾「＋」页脚），绘制与命中检测共用 */
    private val itemRects = mutableListOf<RectF>()

    /** 当前是否含「＋」页脚（仅符号模式） */
    private var hasFooter = false

    /** 竖向滚动偏移（内容超过可视高度时） */
    private var scrollOffset = 0f

    // ===== 画笔 =====

    private val boardBgPaint = Paint().apply { style = Paint.Style.FILL }
    private val itemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ===== 手势：点击选择 + 竖向滚动 =====

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val y = e.y + scrollOffset
            for (i in itemRects.indices) {
                if (itemRects[i].contains(e.x, y)) {
                    handleClick(i)
                    return true
                }
            }
            return false
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            val maxScroll = (contentHeight() - height).coerceAtLeast(0f)
            scrollOffset = (scrollOffset + distanceY).coerceIn(0f, maxScroll)
            invalidate()
            return true
        }
    })

    init {
        isHapticFeedbackEnabled = true
        rebuildPaints()
        textPaint.textSize = sp2px(TEXT_SIZE_SP)
        addPaint.textSize = sp2px(TEXT_SIZE_SP + 4f)
    }

    // ===== 皮肤 =====

    /** 应用皮肤（由 Service 层在创建 / 切换皮肤时调用），与键盘视觉保持一致 */
    fun applySkin(newSkin: SkinTheme) {
        skin = newSkin
        rebuildPaints()
        invalidate()
    }

    private fun rebuildPaints() {
        boardBgPaint.color = skin.keyboardBackground
        itemBgPaint.color = skin.keyBackground
        textPaint.color = skin.keyTextColor
        textPaint.typeface = skin.textTypeface
        addPaint.color = skin.candidateHighlightColor
    }

    // ===== 数据更新 =====

    /** 设置自定义侧栏符号（符号模式内容）。对应 yuyansdk 的 `mSideSymbolsPinyin`。 */
    fun setSideSymbols(symbols: List<SideSymbol>) {
        sideSymbols = symbols
        if (mode == Mode.SYMBOL) {
            recalculateLayout()
            invalidate()
        }
    }

    /**
     * 更新左侧拼音显示 —— 对应 yuyansdk 的 `updatePrefixsView()`。
     *
     * @param candidates 候选拼音列表；非空 → 拼音模式；为空 / null → 符号模式并显示「＋」页脚。
     */
    fun setPinyinCandidates(candidates: List<String>?) {
        val list = candidates ?: emptyList()
        if (list.isNotEmpty()) {
            mode = Mode.PINYIN
            pinyins = list
        } else {
            mode = Mode.SYMBOL
            pinyins = emptyList()
        }
        scrollOffset = 0f
        recalculateLayout()
        invalidate()
    }

    // ===== 布局与绘制 =====

    private fun currentDisplays(): List<String> = when (mode) {
        Mode.PINYIN -> pinyins
        Mode.SYMBOL -> sideSymbols.map { it.display }
    }

    private fun recalculateLayout() {
        itemRects.clear()
        if (width == 0) return
        val margin = dp2px(ITEM_MARGIN_DP)
        val itemH = dp2px(ITEM_HEIGHT_DP)
        val left = margin
        val right = (width - margin).coerceAtLeast(left)
        var y = margin
        for (i in currentDisplays().indices) {
            itemRects.add(RectF(left, y, right, y + itemH))
            y += itemH + margin
        }
        hasFooter = mode == Mode.SYMBOL
        if (hasFooter) {
            itemRects.add(RectF(left, y, right, y + itemH))
        }
    }

    private fun contentHeight(): Float =
        if (itemRects.isEmpty()) 0f else itemRects.last().bottom + dp2px(ITEM_MARGIN_DP)

    private fun handleClick(index: Int) {
        val displays = currentDisplays()
        if (index < displays.size) {
            when (mode) {
                Mode.PINYIN -> onPinyinSelect?.invoke(pinyins[index])
                Mode.SYMBOL -> onSymbolInput?.invoke(sideSymbols[index].value)
            }
        } else {
            // 末尾「＋」页脚：进入自定义管理
            onAddSymbol?.invoke()
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint)
        if (itemRects.isEmpty()) return

        canvas.save()
        canvas.translate(0f, -scrollOffset)

        val displays = currentDisplays()
        val radius = dp2px(ITEM_RADIUS_DP)
        for (i in itemRects.indices) {
            val rect = itemRects[i]
            canvas.drawRoundRect(rect, radius, radius, itemBgPaint)
            val isFooter = hasFooter && i == itemRects.size - 1
            if (isFooter) {
                drawCenteredText(canvas, ADD_FOOTER_LABEL, rect, addPaint)
            } else {
                drawFittedText(canvas, displays[i], rect)
            }
        }
        canvas.restore()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        val y = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, paint)
    }

    /**
     * 按比例缩小字号以适配单元宽度（等价 yuyansdk `AutoScaleTextView.Mode.Proportional`）。
     * 绘制后恢复基础字号，避免影响后续单元。
     */
    private fun drawFittedText(canvas: Canvas, text: String, rect: RectF) {
        val baseSize = sp2px(TEXT_SIZE_SP)
        val maxWidth = rect.width() - dp2px(4f)
        textPaint.textSize = baseSize
        val measured = textPaint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            textPaint.textSize = baseSize * (maxWidth / measured)
        }
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, textPaint)
        textPaint.textSize = baseSize
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    // ===== 单位转换 =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density
    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
