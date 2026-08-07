package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ziyou.ime.core.skin.SkinColor
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
 *
 * 此外底部固定一枚「符号」键（[setGridGeometry] 指定其位置与高度，与九宫格底行对齐），
 * 点击回调 [onSymbolKeyboard] 进入符号键盘；上方列表区域在剩余高度内滚动。
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
        private const val ITEM_MARGIN_DP = 2f
        /** 文字字号相对皮肤功能键字号的增量（sp）：
         *  迁移前基线 12+3=15，与皮肤化前硬编码值一致，零视觉回归 */
        private const val TEXT_SIZE_DELTA_SP = 3f
        /** 「＋」页脚相对正文的字号增量（sp） */
        private const val ADD_SIZE_DELTA_SP = 4f
        /** 「＋」页脚符号 */
        private const val ADD_FOOTER_LABEL = "\uFF0B"
        /** 底部固定「符号」键文案 */
        private const val SYMBOL_KEY_LABEL = "符号"
    }

    /** 点击拼音回调（拼音模式）：参数为所选拼音字符串 */
    var onPinyinSelect: ((pinyin: String) -> Unit)? = null

    /** 点击侧栏符号回调（符号模式）：参数为要上屏的内容 */
    var onSymbolInput: ((value: String) -> Unit)? = null

    /** 点击「＋」页脚回调：进入侧栏符号自定义管理 */
    var onAddSymbol: (() -> Unit)? = null

    /** 点击底部「符号」键回调：切换到符号键盘 */
    var onSymbolKeyboard: (() -> Unit)? = null

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

    /** 底部固定「符号」键矩形；未设置尺寸（高度为 0）时不绘制 */
    private val symbolKeyRect = RectF()

    /** 列表区顶部 y 偏移（px），与九宫格首行按键顶部对齐 */
    private var listTop = 0f

    /** 列表区与底部「符号」键之间的间距（px），与九宫格行间距对齐 */
    private var listGap = 0f

    /** 列表整体圆角裁剪路径（项间紧密排列，圆角只作用于列表外轮廓） */
    private val listClipPath = Path()

    // ===== 画笔 =====

    private val boardBgPaint = Paint().apply { style = Paint.Style.FILL }
    private val itemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** 单元阴影画笔（与键盘按键同源：皮肤阴影色 + 可选弥散模糊） */
    private val itemShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 正文字号（sp，随皮肤更新） */
    private var textSizeSp = 15f

    // ===== 手势：点击选择 + 竖向滚动 =====

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 底部「符号」键固定不随列表滚动，优先命中
            if (symbolKeyRect.height() > 0f && symbolKeyRect.contains(e.x, e.y)) {
                onSymbolKeyboard?.invoke()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }
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
            val maxScroll = (contentBottom() - listBottom()).coerceAtLeast(0f)
            scrollOffset = (scrollOffset + distanceY).coerceIn(0f, maxScroll)
            invalidate()
            return true
        }
    })

    init {
        isHapticFeedbackEnabled = true
        rebuildPaints()
    }

    // ===== 皮肤 =====

    /** 应用皮肤（由 Service 层在创建 / 切换皮肤时调用），与键盘视觉保持一致 */
    fun applySkin(newSkin: SkinTheme) {
        skin = newSkin
        rebuildPaints()
        invalidate()
    }

    private fun rebuildPaints() {
        // 背景类颜色按皮肤整体透明度调制（与键盘视图 skinAlpha 同源规则）
        boardBgPaint.color = SkinColor.scaleAlpha(skin.keyboardBackground, skin.backgroundAlpha)
        // 侧栏单元与功能键同色，与右侧功能列形成对称的灏灰背景
        itemBgPaint.color = SkinColor.scaleAlpha(skin.funcKeyBackground, skin.backgroundAlpha)
        textPaint.color = skin.keyTextColor
        textPaint.typeface = skin.textTypeface
        addPaint.color = skin.candidateHighlightColor
        // 字号随皮肤功能键字号联动（增量映射，迁移前基线零回归）
        textSizeSp = skin.funcTextSizeSp + TEXT_SIZE_DELTA_SP
        textPaint.textSize = sp2px(textSizeSp)
        addPaint.textSize = sp2px(textSizeSp + ADD_SIZE_DELTA_SP)
        // 阴影与键盘按键同源：皮肤阴影色，radiusDp > 0 时弥散模糊
        itemShadowPaint.color = skin.keyShadowColor
        val shadowRadius = skin.keyShadow?.radiusDp ?: 0f
        itemShadowPaint.maskFilter = if (shadowRadius > 0f) {
            BlurMaskFilter(dp2px(shadowRadius), BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
    }

    /** 单元/列表外轮廓圆角（px）：与键盘按键圆角同源，随皮肤变化 */
    private fun itemRadius(): Float = dp2px(skin.keyCornerRadiusDp)

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

    /**
     * 设置九宫格几何对齐信息（相对本视图顶部，px）：
     * 列表区顶部与网格首行按键顶部对齐，底部固定「符号」键与网格底行对齐。
     * 由 [KeyboardLayoutManager] 依九宫格布局完成后传入；
     * bottomKeyHeight 为 0 时不绘制底部键（如尚未完成布局）。
     *
     * @param gridTop 网格首行按键顶部 y 偏移
     * @param rowGap 网格行间距（列表区与底部键之间沿用此间距）
     * @param bottomKeyTop 网格底行顶部 y 偏移
     * @param bottomKeyHeight 网格单行按键高度
     */
    fun setGridGeometry(gridTop: Float, rowGap: Float, bottomKeyTop: Float, bottomKeyHeight: Float) {
        listTop = gridTop
        listGap = rowGap
        val margin = dp2px(ITEM_MARGIN_DP)
        symbolKeyRect.set(
            margin, bottomKeyTop,
            (width - margin).coerceAtLeast(margin), bottomKeyTop + bottomKeyHeight
        )
        recalculateLayout()
        invalidate()
    }

    // ===== 布局与绘制 =====

    private fun currentDisplays(): List<String> = when (mode) {
        Mode.PINYIN -> pinyins
        Mode.SYMBOL -> sideSymbols.map { it.display }
    }

    /** 可滚动列表区域底部 y（总高扣除底部固定「符号」键及间距），与网格第 3 行底部对齐 */
    private fun listBottom(): Float =
        if (symbolKeyRect.height() > 0f) symbolKeyRect.top - listGap else height.toFloat()

    private fun recalculateLayout() {
        itemRects.clear()
        if (width == 0) return
        val margin = dp2px(ITEM_MARGIN_DP)
        val left = margin
        val right = (width - margin).coerceAtLeast(left)
        hasFooter = mode == Mode.SYMBOL
        val count = currentDisplays().size + if (hasFooter) 1 else 0
        if (count == 0) return
        // 内容不足时均分列表区域高度填满（顶/底与右侧三行网格按键齐平），
        // 内容较多时回退到基准单元高度并支持滚动；项与项之间紧密排列、无竖向间隔
        val evenH = (listBottom() - listTop) / count
        val itemH = maxOf(dp2px(ITEM_HEIGHT_DP), evenH)
        var y = listTop
        repeat(count) {
            itemRects.add(RectF(left, y, right, y + itemH))
            y += itemH
        }
    }

    /** 列表内容底部 y（未滚动坐标系） */
    private fun contentBottom(): Float =
        if (itemRects.isEmpty()) listTop else itemRects.last().bottom

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
        if (symbolKeyRect.height() > 0f) {
            // 宽度变化后重新拉伸底部键，保持与列表同宽
            val margin = dp2px(ITEM_MARGIN_DP)
            symbolKeyRect.left = margin
            symbolKeyRect.right = (w - margin).coerceAtLeast(margin)
        }
        recalculateLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint)
        drawBottomSymbolKey(canvas)
        if (itemRects.isEmpty()) return

        canvas.save()
        // 整体圆角裁剪：圆角只作用于列表外轮廓，项间无缝衔接；
        // 同时限制在底部「符号」键之上，避免滚动时覆盖固定键
        val radius = itemRadius()
        val first = itemRects.first()
        val visibleBottom = minOf(contentBottom() - scrollOffset, listBottom())
        // 列表整体投影（皮肤可关闭）：与键盘按键同源的偏移 + 弥散参数，
        // 画在裁剪之前，使投影落在轮廓外
        skin.keyShadow?.let { s ->
            canvas.drawRoundRect(
                first.left + dp2px(s.dxDp), listTop + dp2px(s.dyDp),
                first.right + dp2px(s.dxDp), visibleBottom + dp2px(s.dyDp),
                radius, radius, itemShadowPaint
            )
        }
        listClipPath.rewind()
        listClipPath.addRoundRect(
            first.left, listTop, first.right, visibleBottom, radius, radius, Path.Direction.CW
        )
        canvas.clipPath(listClipPath)
        canvas.translate(0f, -scrollOffset)

        val displays = currentDisplays()
        for (i in itemRects.indices) {
            val rect = itemRects[i]
            canvas.drawRect(rect, itemBgPaint)
            val isFooter = hasFooter && i == itemRects.size - 1
            if (isFooter) {
                drawCenteredText(canvas, ADD_FOOTER_LABEL, rect, addPaint)
            } else {
                drawFittedText(canvas, displays[i], rect)
            }
        }
        canvas.restore()
    }

    /** 绘制底部固定「符号」键（不参与列表滚动；圆角/阴影与键盘按键同源） */
    private fun drawBottomSymbolKey(canvas: Canvas) {
        if (symbolKeyRect.height() <= 0f) return
        val radius = itemRadius()
        skin.keyShadow?.let { s ->
            val shadowRect = RectF(symbolKeyRect)
            shadowRect.offset(dp2px(s.dxDp), dp2px(s.dyDp))
            canvas.drawRoundRect(shadowRect, radius, radius, itemShadowPaint)
        }
        canvas.drawRoundRect(symbolKeyRect, radius, radius, itemBgPaint)
        drawCenteredText(canvas, SYMBOL_KEY_LABEL, symbolKeyRect, textPaint)
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
        val baseSize = sp2px(textSizeSp)
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
    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.density * resources.configuration.fontScale
}
