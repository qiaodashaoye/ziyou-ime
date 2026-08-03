package com.ziyou.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.skin.SkinColor
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinTheme

/**
 * 候选词视图
 *
 * 水平滚动显示候选词列表，支持：
 * - 横向排列候选词
 * - 点击选择候选词
 * - 支持左右滑动翻页
 * - 预测模式：引擎联想（librime-predict）在 commit 后产生的预测词以强调色区分显示
 *
 * 注意：编码区（preedit）已分离为独立的 [PreeditOverlayView]，
 * 通过垂直 LinearLayout 堆叠在候选词区域上方。
 */
class SimpleCandidatesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 视图高度（dp），紧凑显示以减少垂直空间占用；
         *  对模块内开放供 [CandidateToolbarView] 计算候选区总高 */
        internal const val VIEW_HEIGHT_DP = 32
        /** 候选词字体默认大小（sp，皮肤可覆盖） */
        private const val CANDIDATE_TEXT_SIZE_SP = 16f
        /** 候选词水平内边距（dp） */
        private const val CANDIDATE_PADDING_H_DP = 12

        /**
         * 将累积缓冲中的索引转换为 Rime 当前页的局部索引。
         *
         * @param accumulatedIndex 累积缓冲中的位置
         * @param currentPageNumber 当前引擎页码
         * @param accumulatedPageStart 累积缓冲起始页码
         * @param currentPageSize 每页候选词数
         * @return 页内局部索引（用于 Rime select_candidate_on_current_page）
         */
        internal fun toLocalIndex(
            accumulatedIndex: Int,
            currentPageNumber: Int,
            accumulatedPageStart: Int,
            currentPageSize: Int
        ): Int {
            val offset = (currentPageNumber - accumulatedPageStart) * currentPageSize
            return (accumulatedIndex - offset).coerceAtLeast(0)
        }

        /**
         * 将累积缓冲中的索引转换为 Rime 全局候选索引（跨页选词用）。
         *
         * 全局索引 = 起始页的全局偏移 + 累积缓冲内偏移，供 Rime
         * select_candidate(global) 直接选中任意可见候选（含旧页）。
         *
         * @param accumulatedIndex 累积缓冲中的位置
         * @param accumulatedPageStart 累积缓冲起始页码
         * @param currentPageSize 每页候选词数
         * @return Rime 全局候选索引
         */
        internal fun toGlobalIndex(
            accumulatedIndex: Int,
            accumulatedPageStart: Int,
            currentPageSize: Int
        ): Int = accumulatedPageStart * currentPageSize + accumulatedIndex

        /**
         * 计算候选数据更新后应采用的高亮索引。
         *
         * 翻页（输入未变）必须清除高亮：新页面在用户重新选择前不应有选中项，
         * 否则 Rime 默认的页内高亮（通常是首项）会残留在新页上造成误导；
         * 输入变更（新按键/删除）则沿用引擎页内高亮（新组合的默认选中态）。
         *
         * @param inputChanged 编码串/光标是否变化（true=新按键/删除，false=翻页等）
         * @param engineHighlightIndex Rime 报告的页内高亮索引（-1 表示无）
         * @return 应采用的高亮索引（-1 表示无高亮）
         */
        internal fun resolveHighlight(
            inputChanged: Boolean,
            engineHighlightIndex: Int
        ): Int = if (inputChanged) engineHighlightIndex else -1

        /**
         * 判断当前页码相对于累积缓冲是否为前翻页方向。
         *
         * @param currentPageNumber 当前引擎页码
         * @param accumulatedPageStart 累积缓冲起始页码
         * @param accumulatedSize 累积缓冲当前大小
         * @param pageSize 每页候选词数
         * @return true 表示前翻页，false 表示后翻页
         */
        internal fun isForwardPage(
            currentPageNumber: Int,
            accumulatedPageStart: Int,
            accumulatedSize: Int,
            pageSize: Int
        ): Boolean {
            val pagesLoaded = if (pageSize > 0) accumulatedSize / pageSize else 1
            return currentPageNumber >= accumulatedPageStart + pagesLoaded
        }
    }

    /** 候选词点击回调（携带被点候选本体：跨页分段确认同步需其注音，
     *  引擎当前页 menu 可能查不到旧页候选） */
    var onCandidateClick: ((index: Int, candidate: CandidateProto) -> Unit)? = null

    /** 翻页回调：true=下一页, false=上一页 */
    var onPageChange: ((forward: Boolean) -> Unit)? = null

    /** 当前候选词字号（sp，随皮肤更新） */
    private var textSizeSp = CANDIDATE_TEXT_SIZE_SP

    /**
     * 全局缩放因子（悬浮模式用）：同步缩小视图高度与候选词字号，
     * 与键盘视图的 scaleFactor 保持一致。默认 1.0，停靠模式零影响。
     */
    var scaleFactor: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                applyTextSizes()
                minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
                recalculateLayout()
                requestLayout()
                invalidate()
            }
        }

    // 候选词数据
    private var candidates: Array<CandidateProto> = emptyArray()
    private var highlightIndex: Int = -1

    /**
     * 高亮候选在累积缓冲中的位置（由页内 [highlightIndex] 换算而来）。
     * onDraw 用它在跨页累积数组上正确定位高亮项；-1 表示无高亮。
     */
    private var highlightAccumulatedIndex: Int = -1

    /**
     * 是否处于引擎预测态（librime-predict 在 commit 后产生的 prediction 候选）。
     * 预测态下所有候选以 [predictionPaint] 强调色绘制；点击路由不受影响
     * （预测词仍经 Rime selectCandidate 选词）。
     */
    private var isPredictionMode = false

    // 绘制相关的画笔
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
        color = Color.DKGRAY
        typeface = Typeface.DEFAULT
    }

    private val highlightedCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
        color = Color.parseColor("#4A90D9")
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 预测词画笔：强调色、常规字重，与普通候选词（默认色）及高亮项（加粗）区分 */
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
        color = Color.parseColor("#4A90D9")
        typeface = Typeface.DEFAULT
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    // 每个候选词的位置信息（左x, 右x）
    private val candidateRects = mutableListOf<RectF>()

    // 滑动偏移量
    private var scrollOffset = 0f

    // OverScroller：惯性滚动 + 边缘回弹
    private val scroller = OverScroller(context)

    /** 缓存的候选内容总宽度（recalculateLayout 时更新） */
    private var totalContentWidth = 0f

    // ===== 跨页累积缓冲（前翻页追加候选，输入变更时清空） =====

    /** 累积候选词缓冲 */
    private var accumulatedCandidates = mutableListOf<CandidateProto>()
    /** 上次输入的编码串（用于区分新按键 vs 翻页） */
    private var lastInput = ""
    private var lastCaretPos = 0
    /** 累积缓冲对应的起始页码 */
    private var accumulatedPageStart = 0
    /** 当前页码（来自 MenuProto.pageNumber） */
    internal var currentPageNumber = 0
    /** 当前页大小（来自 MenuProto.pageSize） */
    internal var currentPageSize = 5
    /** 是否为最后一页 */
    private var isLastPage = true
    /** 边缘翻页防重复标记 */
    private var edgeTriggerFired = false
    /** 翻页加载中标记 */
    private var isPageLoading = false

    // 手势检测器
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.x + scrollOffset
            val y = e.y
            // 查找点击的候选词（rects 为内容坐标，点击 x 已补偿 scrollOffset）
            for (i in candidateRects.indices) {
                if (candidateRects[i].contains(x, y)) {
                    // 累积索引 → Rime 全局候选索引（跨页可见候选均可选中）
                    val globalIndex = toGlobalIndex(i, accumulatedPageStart, currentPageSize)
                    onCandidateClick?.invoke(globalIndex, candidates[i])
                    return true
                }
            }
            return false
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 手指跟随滚动（duration=0 即时定位）
            scroller.startScroll(scrollOffset.toInt(), 0, distanceX.toInt(), 0, 0)
            scroller.computeScrollOffset()
            scrollOffset = scroller.currX.toFloat()
            val maxScroll = (totalContentWidth - width).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (Math.abs(velocityX) < 500) return false
            val maxScroll = (totalContentWidth - width).coerceAtLeast(0f).toInt()
            if (maxScroll <= 0) return false
            // OverScroller 物理 fling；边缘翻页由 computeScroll 检测触发
            scroller.fling(
                scrollOffset.toInt(), 0, -velocityX.toInt(), 0,
                0, maxScroll, 0, 0,
                (maxScroll / 4).coerceAtLeast(1), 0
            )
            edgeTriggerFired = false
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        // 设置最小高度
        minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        // 构造期即接当前皮肤（快照命中 O(1)），避免首帧硬编码灰打底；
        // Service 层切皮肤时仍会再次下发 applySkin
        applySkin(SkinManager.getCurrentSkin(context))
    }

    /**
     * 应用皮肤，与键盘视图保持视觉一致（由 Service 层调用）。
     * 配色/字号/字体均取自皮肤；背景色按皮肤整体透明度调制（背景图透出）。
     */
    fun applySkin(skin: SkinTheme) {
        bgPaint.color = SkinColor.scaleAlpha(skin.candidateBackground, skin.backgroundAlpha)
        candidatePaint.color = skin.candidateTextColor
        // 选中项仅通过高亮字体颜色表示，不再绘制背景框
        highlightedCandidatePaint.color = skin.candidateHighlightColor
        // 预测词与高亮项同色但不加粗，整栏统一强调色即代表预测态
        predictionPaint.color = skin.candidateHighlightColor
        candidatePaint.typeface = skin.textTypeface
        highlightedCandidatePaint.typeface = Typeface.create(skin.textTypeface, Typeface.BOLD)
        predictionPaint.typeface = skin.textTypeface
        textSizeSp = skin.candidateTextSizeSp
        applyTextSizes()
        recalculateLayout()
        invalidate()
    }

    /** 按当前字号（sp）与缩放因子同步三支候选画笔的文字大小 */
    private fun applyTextSizes() {
        candidatePaint.textSize = sp2px(textSizeSp)
        highlightedCandidatePaint.textSize = sp2px(textSizeSp)
        predictionPaint.textSize = sp2px(textSizeSp)
    }

    /**
     * 更新候选词数据
     * @param context Rime输入上下文
     * @param predictionMode 是否为引擎预测态（librime-predict 在 commit 后产生的
     *        prediction 候选）；为 true 时复用联想强调色整栏绘制，与普通候选区分。
     *        点击路由不受影响（预测词仍经 Rime selectCandidate 选词）。
     */
    fun updateCandidates(context: ContextProto?, predictionMode: Boolean = false) {
        isPageLoading = false
        if (context == null) {
            accumulatedCandidates.clear()
            candidates = emptyArray()
            highlightIndex = -1
            lastInput = ""
            lastCaretPos = 0
            scrollOffset = 0f
            scroller.abortAnimation()
            isPredictionMode = false
        } else {
            val newInput = context.input
            val newCaretPos = context.caretPos
            val inputChanged = newInput != lastInput || newCaretPos != lastCaretPos

            val menu = context.menu
            val pageCandidates = menu?.candidates ?: emptyArray()
            currentPageNumber = menu?.pageNumber ?: 0
            currentPageSize = menu?.pageSize ?: 5
            isLastPage = menu?.isLastPage ?: true
            // 翻页（输入未变）清除高亮：新页面在用户重新选择前不带选中项；
            // 输入变更时沿用引擎页内高亮
            highlightIndex = resolveHighlight(inputChanged, menu?.highlightedCandidateIndex ?: -1)
            isPredictionMode = predictionMode && pageCandidates.isNotEmpty()

            if (inputChanged) {
                // 新按键/删除：重置一切
                accumulatedCandidates.clear()
                accumulatedCandidates.addAll(pageCandidates)
                accumulatedPageStart = currentPageNumber
                scrollOffset = 0f
                scroller.abortAnimation()
            } else {
                // 翻页：判断方向，前翻页追加候选保持 scrollOffset 连续
                val forward = isForwardPage(currentPageNumber, accumulatedPageStart,
                    accumulatedCandidates.size, currentPageSize)
                if (forward || accumulatedCandidates.isEmpty()) {
                    // 前翻页或首次：追加
                    accumulatedCandidates.addAll(pageCandidates)
                } else {
                    // 后翻页：重置（Rime 不支持随机跳页，无法无缝后翻）
                    accumulatedCandidates.clear()
                    accumulatedCandidates.addAll(pageCandidates)
                    accumulatedPageStart = currentPageNumber
                    scrollOffset = 0f
                    scroller.abortAnimation()
                }
            }

            lastInput = newInput
            lastCaretPos = newCaretPos
            candidates = accumulatedCandidates.toTypedArray()
        }

        // 页内高亮索引换算为累积缓冲位置，确保跨页后高亮不错位
        highlightAccumulatedIndex = if (highlightIndex >= 0) {
            (currentPageNumber - accumulatedPageStart) * currentPageSize + highlightIndex
        } else {
            -1
        }

        recalculateLayout()
        invalidate()
    }

    /**
     * 重新计算候选词布局位置，并缓存内容总宽度供滚动边界使用。
     */
    private fun recalculateLayout() {
        candidateRects.clear()
        val paddingH = dp2px(CANDIDATE_PADDING_H_DP.toFloat())
        var x = paddingH

        for (candidate in candidates) {
            val text = candidate.text
            val textWidth = candidatePaint.measureText(text)
            val itemWidth = textWidth + paddingH * 2
            candidateRects.add(RectF(x, 0f, x + itemWidth, height.toFloat()))
            x += itemWidth
        }
        totalContentWidth = x
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (candidates.isEmpty()) return

        canvas.save()
        canvas.translate(-scrollOffset, 0f)

        val paddingH = dp2px(CANDIDATE_PADDING_H_DP.toFloat())
        val textBaseline = height / 2f + candidatePaint.textSize / 3f

        // 绘制候选词
        for (i in candidates.indices) {
            if (i >= candidateRects.size) break
            val rect = candidateRects[i]

            // 选中项仅改变字体颜色（高亮色 + 加粗），不绘制边框/背景；候选词之间也不再绘制竖线分隔符；
            // 预测态下整栏使用强调色常规字重，与普通候选词区分
            val paint = when {
                isPredictionMode -> predictionPaint
                i == highlightAccumulatedIndex -> highlightedCandidatePaint
                else -> candidatePaint
            }
            val textX = rect.left + dp2px(CANDIDATE_PADDING_H_DP.toFloat())
            canvas.drawText(candidates[i].text, textX, textBaseline, paint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * 返回缓存的候选内容总宽度（recalculateLayout 时更新）。
     */
    private fun calculateTotalWidth(): Float = totalContentWidth

    /**
     * 驱动 OverScroller 动画帧；到达右边缘时自动触发下一页加载。
     */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currX.toFloat()
            // 边缘翻页检测：fling 到达右边缘且还有更多页
            val maxScroll = (totalContentWidth - width).coerceAtLeast(0f)
            if (!edgeTriggerFired && !isPageLoading && !isLastPage
                && maxScroll > 0f && scrollOffset >= maxScroll) {
                edgeTriggerFired = true
                isPageLoading = true
                onPageChange?.invoke(true) // 下一页
            }
            invalidate()
        }
    }

    // ===== 单位转换工具（已叠加缩放因子，悬浮模式下尺寸统一缩放） =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density * scaleFactor

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity * scaleFactor
}
