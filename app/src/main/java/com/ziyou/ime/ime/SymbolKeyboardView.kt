package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Toast
import com.ziyou.ime.skin.SkinTheme
import com.ziyou.ime.data.SymbolCategory
import com.ziyou.ime.data.SymbolRepository

/**
 * 符号键盘视图 —— 对应 [KeyboardType.SYMBOL]。
 *
 * 采用主流中文输入法（搜狗/百度/讯飞）符号面板的事实标准布局，
 * 纯 Canvas 绘制（遵循本项目 [BaseKeyboardView] / [PinyinSideBarView] 的既有风格）：
 *
 * ┌────────┬──────────────────────────┐
 * │ 分类栏  │  符号网格（5 列，竖向滚动）    │
 * │ 常用    │  ， 。 ？ ！ 、            │
 * │ 最近    │  ： ； …… ～ ·            │
 * │ 中文    │  ...                     │
 * │ (可滚动) │                          │
 * ├────────┴──────────────────────────┤
 * │ [返回]     [空格]      [⌫]   [⏎]   │
 * └───────────────────────────────────┘
 *
 * 交互（源自调研结论，见 docs/符号键盘调研与设计方案.md）：
 * - 点击符号：直接上屏（回调 [onSymbolInput]，经 Service 统一 commit 出口），
 *   面板停留支持连续输入；同时自动记入「最近」分类。
 * - 长按符号：加入「常用」分类；在「常用」分类内长按则移除 —— 路径最短的自定义方式。
 * - 点击分类：切换右侧网格内容并回到顶部。
 * - 「返回」键：发送 [KeyCode.KEYCODE_SYMBOL]，由 Service 恢复进入前的键盘布局。
 * - 「⌫」长按连续删除（与主键盘退格一致的重复触发节奏）。
 *
 * 性能：网格只绘制滚动窗口内可见行，命中检测用坐标反算行列（不缓存逐格矩形），
 * 数百符号的分类也无绘制/内存压力；符号数据经 [SymbolRepository] 内存缓存供给。
 *
 * 继承 [BaseKeyboardView] 以复用主题画笔、缩放因子（悬浮形态）、回调与单位换算，
 * 但布局完全自绘：[rows] 返回空列表，测量 / 绘制 / 触摸均在本类实现。
 */
class SymbolKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseKeyboardView(context, attrs, defStyleAttr) {

    companion object {
        /** 符号网格列数 */
        private const val GRID_COLUMNS = 5
        /** 分类栏宽度（dp） */
        private const val CATEGORY_BAR_WIDTH_DP = 60f
        /** 分类项高度（dp） */
        private const val CATEGORY_ITEM_HEIGHT_DP = 42f
        /** 符号单元格高度（dp） */
        private const val CELL_HEIGHT_DP = 50f
        /** 符号文字大小（sp） */
        private const val SYMBOL_TEXT_SIZE_SP = 19f
        /** 分类文字大小（sp） */
        private const val CATEGORY_TEXT_SIZE_SP = 14f
        /** 空态提示文字大小（sp） */
        private const val HINT_TEXT_SIZE_SP = 13f
        /** 与九宫格一致的按键高度倍率，使面板总高与 T9 键盘对齐 */
        private const val T9_HEIGHT_FACTOR = 1.3f
        /** 面板总高度折算的行数（3 行网格区 + 1 行底栏，与 T9 四行等高） */
        private const val PANEL_ROW_COUNT = 4
        /** 退格长按重复：初始延迟 / 触发间隔（ms），与 BaseKeyboardView 一致 */
        private const val REPEAT_START_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
    }

    /** 点击符号回调：参数为要上屏的符号内容 */
    var onSymbolInput: ((symbol: String) -> Unit)? = null

    override val keyHeightMultiplier: Float = T9_HEIGHT_FACTOR

    /** 布局完全自绘，不使用基类的行×宽度布局模型 */
    override val rows: List<List<Key>> = emptyList()

    // ===== 底部功能栏（复用基类 Key 模型，自行排布与触摸处理） =====

    private val bottomRow = listOf(
        Key("返回", KeyCode.KEYCODE_SYMBOL, 1.2f, isFunctional = true),
        Key("空格", KeyCode.XK_space, 1.8f),
        Key("\u232B", KeyCode.XK_BackSpace, 1f, isFunctional = true),
        Key("\u23CE", KeyCode.XK_Return, 1f, isFunctional = true)
    )

    /** 底栏按键矩形（与 bottomRow 一一对应） */
    private val bottomKeyRects = mutableListOf<RectF>()

    // ===== 数据状态 =====

    private val categories: List<SymbolCategory> = SymbolRepository.getCategories()

    /** 当前选中的分类索引 */
    private var selectedCategory = 0

    /** 当前分类的符号列表 */
    private var symbols: List<String> = emptyList()

    // ===== 区域与滚动状态 =====

    /** 分类栏区域 */
    private val categoryArea = RectF()

    /** 符号网格区域 */
    private val gridArea = RectF()

    /** 分类栏 / 网格竖向滚动偏移 */
    private var categoryScroll = 0f
    private var gridScroll = 0f

    /** 按压高亮状态（-1 表示无按压） */
    private var pressedCategory = -1
    private var pressedCell = -1
    private var pressedBottomKey = -1

    /** 本次手势起始区域（决定滚动归属） */
    private enum class Zone { NONE, CATEGORY, GRID, BOTTOM }
    private var touchZone = Zone.NONE

    // ===== 画笔 =====

    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val categoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val categorySelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    init {
        applySymbolPaints()
        reloadSymbols()
    }

    // ===== 主题 / 缩放 =====

    override fun applySkin(newSkin: SkinTheme) {
        super.applySkin(newSkin)
        applySymbolPaints()
    }

    override fun onScaleChanged() {
        applySymbolPaints()
    }

    /** 同步自有画笔的颜色与（缩放后）文字大小 */
    private fun applySymbolPaints() {
        symbolPaint.textSize = sp2px(SYMBOL_TEXT_SIZE_SP)
        symbolPaint.color = skin.keyTextColor
        categoryPaint.textSize = sp2px(CATEGORY_TEXT_SIZE_SP)
        categoryPaint.color = skin.keyTextColor
        categorySelectedPaint.textSize = sp2px(CATEGORY_TEXT_SIZE_SP)
        categorySelectedPaint.color = android.graphics.Color.WHITE
        hintPaint.textSize = sp2px(HINT_TEXT_SIZE_SP)
        hintPaint.color = skin.preeditTextColor
    }

    // ===== 数据 =====

    /** 重新加载当前分类的符号并回到网格顶部 */
    private fun reloadSymbols() {
        symbols = SymbolRepository.getSymbols(context, categories[selectedCategory].id)
        gridScroll = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 常用/最近可能在设置页或上次会话中变更，重新挂载时刷新
        reloadSymbols()
    }

    // ===== 测量与布局 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val scaledKeyHeight = keyHeight * keyHeightMultiplier
        // 与九宫格 4 行布局等高：3 行网格区 + 1 行底栏
        val totalHeight = (PANEL_ROW_COUNT * scaledKeyHeight +
            (PANEL_ROW_COUNT - 1) * keyGap + keyboardPadding * 2).toInt()
        setMeasuredDimension(width, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    /** 计算三个区域与底栏按键矩形 */
    private fun computeLayout() {
        if (width == 0 || height == 0) return
        val scaledKeyHeight = keyHeight * keyHeightMultiplier
        val bottomTop = height - keyboardPadding - scaledKeyHeight

        categoryArea.set(
            keyboardPadding,
            keyboardPadding,
            keyboardPadding + dp2px(CATEGORY_BAR_WIDTH_DP),
            bottomTop - keyGap
        )
        gridArea.set(
            categoryArea.right + keyGap,
            keyboardPadding,
            width - keyboardPadding,
            bottomTop - keyGap
        )

        // 底栏按键：按相对宽度均摊全宽（与基类 recalculateKeyPositions 同一算法）
        bottomKeyRects.clear()
        val availableWidth = width - keyboardPadding * 2
        val totalWeight = bottomRow.map { it.width }.sum()
        val totalGapWidth = (bottomRow.size - 1) * keyGap
        val unitWidth = (availableWidth - totalGapWidth) / totalWeight
        var x = keyboardPadding
        for (key in bottomRow) {
            val keyWidth = unitWidth * key.width
            bottomKeyRects.add(RectF(x, bottomTop, x + keyWidth, bottomTop + scaledKeyHeight))
            x += keyWidth + keyGap
        }

        // 区域尺寸变化后夹紧滚动偏移
        categoryScroll = categoryScroll.coerceIn(0f, maxCategoryScroll())
        gridScroll = gridScroll.coerceIn(0f, maxGridScroll())
    }

    private fun categoryItemHeight(): Float = dp2px(CATEGORY_ITEM_HEIGHT_DP)
    private fun cellHeight(): Float = dp2px(CELL_HEIGHT_DP)
    private fun cellWidth(): Float =
        (gridArea.width() - (GRID_COLUMNS - 1) * keyGap) / GRID_COLUMNS

    private fun gridRowCount(): Int = (symbols.size + GRID_COLUMNS - 1) / GRID_COLUMNS

    private fun maxCategoryScroll(): Float =
        (categories.size * (categoryItemHeight() + keyGap) - categoryArea.height())
            .coerceAtLeast(0f)

    private fun maxGridScroll(): Float =
        (gridRowCount() * (cellHeight() + keyGap) - gridArea.height()).coerceAtLeast(0f)

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint)
        drawCategoryBar(canvas)
        drawSymbolGrid(canvas)
        drawBottomBar(canvas)
    }

    private fun drawCategoryBar(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(categoryArea)
        canvas.translate(0f, -categoryScroll)
        val itemH = categoryItemHeight()
        for (i in categories.indices) {
            val top = categoryArea.top + i * (itemH + keyGap)
            // 跳过滚动窗口外的分类项
            if (top + itemH < categoryScroll + categoryArea.top) continue
            if (top > categoryScroll + categoryArea.bottom) break
            val rect = RectF(categoryArea.left, top, categoryArea.right, top + itemH)
            val selected = i == selectedCategory
            val bgPaint = when {
                selected -> accentBgPaint
                i == pressedCategory -> pressedKeyBgPaint
                else -> funcKeyBgPaint
            }
            canvas.drawRoundRect(rect, radiusFor(rect), radiusFor(rect), bgPaint)
            val textPaint = if (selected) categorySelectedPaint else categoryPaint
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(categories[i].label, rect.centerX(), y, textPaint)
        }
        canvas.restore()
    }

    private fun drawSymbolGrid(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(gridArea)

        if (symbols.isEmpty()) {
            // 空态提示（「最近」无记录时）
            val hint = if (categories[selectedCategory].id == SymbolRepository.CATEGORY_RECENT)
                "暂无最近使用的符号" else "长按符号可加入「常用」"
            val y = gridArea.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2f
            canvas.drawText(hint, gridArea.centerX(), y, hintPaint)
            canvas.restore()
            return
        }

        canvas.translate(0f, -gridScroll)
        val cellH = cellHeight()
        val cellW = cellWidth()
        // 只绘制滚动窗口内可见的行
        val rowStride = cellH + keyGap
        val firstRow = (gridScroll / rowStride).toInt().coerceAtLeast(0)
        val lastRow = ((gridScroll + gridArea.height()) / rowStride).toInt()
        for (row in firstRow..minOf(lastRow, gridRowCount() - 1)) {
            for (col in 0 until GRID_COLUMNS) {
                val index = row * GRID_COLUMNS + col
                if (index >= symbols.size) break
                val left = gridArea.left + col * (cellW + keyGap)
                val top = gridArea.top + row * rowStride
                val rect = RectF(left, top, left + cellW, top + cellH)
                val bgPaint = if (index == pressedCell) pressedKeyBgPaint else keyBgPaint
                canvas.drawRoundRect(rect, radiusFor(rect), radiusFor(rect), bgPaint)
                drawFittedSymbol(canvas, symbols[index], rect)
            }
        }
        canvas.restore()
    }

    /** 按比例缩小字号以适配单元宽度（如「单位」分类的 ㎪ ㎾ 等组合字符），绘制后恢复 */
    private fun drawFittedSymbol(canvas: Canvas, text: String, rect: RectF) {
        val baseSize = sp2px(SYMBOL_TEXT_SIZE_SP)
        val maxWidth = rect.width() - dp2px(6f)
        symbolPaint.textSize = baseSize
        val measured = symbolPaint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            symbolPaint.textSize = baseSize * (maxWidth / measured)
        }
        val y = rect.centerY() - (symbolPaint.descent() + symbolPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, symbolPaint)
        symbolPaint.textSize = baseSize
    }

    private fun drawBottomBar(canvas: Canvas) {
        for (i in bottomRow.indices) {
            val key = bottomRow[i]
            val rect = bottomKeyRects.getOrNull(i) ?: continue
            // 复用基类配色选择（回车强调色 > 按下 > 功能键 > 普通键）
            val radius = radiusFor(rect)
            canvas.drawRoundRect(rect, radius, radius, backgroundPaintFor(key, i == pressedBottomKey))
            val textPaint = textPaintFor(key)
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), y, textPaint)
        }
    }

    // ===== 触摸处理 =====

    /** 退格长按连续删除任务 */
    private var backspaceRepeating = false
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!backspaceRepeating) return
            sendKey(KeyCode.XK_BackSpace, 0)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val gestureDetector = GestureDetector(context, object :
        GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            touchZone = when {
                bottomKeyIndexAt(e.x, e.y) >= 0 -> Zone.BOTTOM
                categoryArea.contains(e.x, e.y) -> Zone.CATEGORY
                gridArea.contains(e.x, e.y) -> Zone.GRID
                else -> Zone.NONE
            }
            when (touchZone) {
                Zone.BOTTOM -> pressedBottomKey = bottomKeyIndexAt(e.x, e.y)
                Zone.CATEGORY -> pressedCategory = categoryIndexAt(e.y)
                Zone.GRID -> pressedCell = cellIndexAt(e.x, e.y)
                Zone.NONE -> {}
            }
            if (touchZone != Zone.NONE) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when (touchZone) {
                Zone.BOTTOM -> {
                    bottomRow.getOrNull(bottomKeyIndexAt(e.x, e.y))?.let { handleKeyUp(it) }
                }
                Zone.CATEGORY -> {
                    val index = categoryIndexAt(e.y)
                    if (index in categories.indices && index != selectedCategory) {
                        selectedCategory = index
                        reloadSymbols()
                    }
                }
                Zone.GRID -> {
                    val index = cellIndexAt(e.x, e.y)
                    if (index in symbols.indices) {
                        commitSymbol(symbols[index])
                    }
                }
                Zone.NONE -> {}
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            when (touchZone) {
                Zone.CATEGORY -> {
                    categoryScroll = (categoryScroll + distanceY).coerceIn(0f, maxCategoryScroll())
                    pressedCategory = -1
                    invalidate()
                }
                Zone.GRID -> {
                    gridScroll = (gridScroll + distanceY).coerceIn(0f, maxGridScroll())
                    pressedCell = -1
                    invalidate()
                }
                else -> return false
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            when (touchZone) {
                // 长按符号：加入常用 / 常用内长按移除
                Zone.GRID -> {
                    val index = cellIndexAt(e.x, e.y)
                    if (index in symbols.indices) {
                        toggleFavorite(symbols[index])
                    }
                }
                // 长按退格：连续删除
                Zone.BOTTOM -> {
                    val key = bottomRow.getOrNull(bottomKeyIndexAt(e.x, e.y))
                    if (key?.code == KeyCode.XK_BackSpace) {
                        backspaceRepeating = true
                        backspaceRepeatRunnable.run()
                    }
                }
                else -> {}
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                backspaceRepeating = false
                removeCallbacks(backspaceRepeatRunnable)
                pressedCategory = -1
                pressedCell = -1
                pressedBottomKey = -1
                touchZone = Zone.NONE
                invalidate()
            }
        }
        return true
    }

    // ===== 命中检测（坐标反算，不缓存逐格矩形） =====

    private fun categoryIndexAt(y: Float): Int {
        if (categories.isEmpty()) return -1
        val index = ((y + categoryScroll - categoryArea.top) /
            (categoryItemHeight() + keyGap)).toInt()
        return if (index in categories.indices) index else -1
    }

    private fun cellIndexAt(x: Float, y: Float): Int {
        if (symbols.isEmpty()) return -1
        val col = ((x - gridArea.left) / (cellWidth() + keyGap)).toInt()
        val row = ((y + gridScroll - gridArea.top) / (cellHeight() + keyGap)).toInt()
        if (col !in 0 until GRID_COLUMNS || row < 0) return -1
        val index = row * GRID_COLUMNS + col
        return if (index in symbols.indices) index else -1
    }

    private fun bottomKeyIndexAt(x: Float, y: Float): Int {
        for (i in bottomKeyRects.indices) {
            if (bottomKeyRects[i].contains(x, y)) return i
        }
        return -1
    }

    // ===== 交互动作 =====

    /** 符号上屏：记入「最近」并回调 Service 统一 commit 出口 */
    private fun commitSymbol(symbol: String) {
        SymbolRepository.recordRecent(context, symbol)
        onSymbolInput?.invoke(symbol)
    }

    /** 长按收藏：常用分类内移除，其他分类加入常用 */
    private fun toggleFavorite(symbol: String) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val inFavoriteCategory =
            categories[selectedCategory].id == SymbolRepository.CATEGORY_FAVORITE
        if (inFavoriteCategory) {
            SymbolRepository.removeFavorite(context, symbol)
            Toast.makeText(context, "已从「常用」移除：$symbol", Toast.LENGTH_SHORT).show()
            reloadSymbols()
        } else if (SymbolRepository.isFavorite(context, symbol)) {
            Toast.makeText(context, "「常用」中已有：$symbol", Toast.LENGTH_SHORT).show()
        } else {
            SymbolRepository.addFavorite(context, symbol)
            Toast.makeText(context, "已加入「常用」：$symbol", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 按键处理 =====

    override fun handleKeyUp(key: Key) {
        // 「返回」发送 KEYCODE_SYMBOL，由 Service 切回进入前的键盘布局
        if (handleCommonKey(key)) return
        sendKey(key.code, 0)
    }

    override fun onDetachedFromWindow() {
        backspaceRepeating = false
        removeCallbacks(backspaceRepeatRunnable)
        super.onDetachedFromWindow()
    }
}
