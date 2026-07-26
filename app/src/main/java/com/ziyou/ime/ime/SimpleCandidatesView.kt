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
import com.ziyou.ime.config.KeyboardTheme
import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto

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
        /** 候选词字体大小（sp） */
        private const val CANDIDATE_TEXT_SIZE_SP = 16f
        /** 候选词水平内边距（dp） */
        private const val CANDIDATE_PADDING_H_DP = 12
    }

    /** 候选词点击回调 */
    var onCandidateClick: ((index: Int) -> Unit)? = null

    /** 翻页回调：true=下一页, false=上一页 */
    var onPageChange: ((forward: Boolean) -> Unit)? = null

    /**
     * 全局缩放因子（悬浮模式用）：同步缩小视图高度与候选词字号，
     * 与键盘视图的 scaleFactor 保持一致。默认 1.0，停靠模式零影响。
     */
    var scaleFactor: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                candidatePaint.textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
                highlightedCandidatePaint.textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
                predictionPaint.textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
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

    // 手势检测器
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = e.x + scrollOffset
            val y = e.y
            // 查找点击的候选词
            for (i in candidateRects.indices) {
                if (candidateRects[i].contains(x, y)) {
                    onCandidateClick?.invoke(i)
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
            // 水平滚动
            scrollOffset += distanceX
            // 限制滚动范围
            val maxScroll = calculateTotalWidth() - width
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll.coerceAtLeast(0f))
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // 快速滑动翻页
            if (Math.abs(velocityX) > 500) {
                onPageChange?.invoke(velocityX < 0) // 向左滑 = 下一页
            }
            return true
        }
    })

    init {
        // 设置最小高度
        minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
    }

    /**
     * 应用主题，与键盘视图保持视觉一致（由 Service 层调用）
     */
    fun applyTheme(theme: KeyboardTheme) {
        bgPaint.color = theme.candidateBackground
        candidatePaint.color = theme.candidateTextColor
        // 选中项仅通过高亮字体颜色表示，不再绘制背景框
        highlightedCandidatePaint.color = theme.candidateHighlightColor
        // 预测词与高亮项同色但不加粗，整栏统一强调色即代表预测态
        predictionPaint.color = theme.candidateHighlightColor
        invalidate()
    }

    /**
     * 更新候选词数据
     * @param context Rime输入上下文
     * @param predictionMode 是否为引擎预测态（librime-predict 在 commit 后产生的
     *        prediction 候选）；为 true 时复用联想强调色整栏绘制，与普通候选区分。
     *        点击路由不受影响（预测词仍经 Rime selectCandidate 选词）。
     */
    fun updateCandidates(context: ContextProto?, predictionMode: Boolean = false) {
        if (context == null) {
            candidates = emptyArray()
            highlightIndex = -1
        } else {
            candidates = context.menu?.candidates ?: emptyArray()
            highlightIndex = context.menu?.highlightedCandidateIndex ?: -1
        }
        isPredictionMode = predictionMode && candidates.isNotEmpty()
        scrollOffset = 0f
        recalculateLayout()
        invalidate()
    }

    /**
     * 重新计算候选词布局位置
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
                i == highlightIndex -> highlightedCandidatePaint
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
     * 计算所有候选词的总宽度
     */
    private fun calculateTotalWidth(): Float {
        if (candidateRects.isNotEmpty()) return candidateRects.last().right
        return 0f
    }

    // ===== 单位转换工具（已叠加缩放因子，悬浮模式下尺寸统一缩放） =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density * scaleFactor

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity * scaleFactor
}
