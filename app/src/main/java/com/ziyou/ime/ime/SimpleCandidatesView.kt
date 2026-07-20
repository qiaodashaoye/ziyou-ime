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
import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto

/**
 * 候选词视图
 *
 * 水平滚动显示候选词列表，支持：
 * - 横向排列候选词
 * - 点击选择候选词
 * - 顶部显示编码区（preedit）
 * - 支持左右滑动翻页
 */
class SimpleCandidatesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 视图高度（dp） */
        private const val VIEW_HEIGHT_DP = 40
        /** 候选词字体大小（sp） */
        private const val CANDIDATE_TEXT_SIZE_SP = 16f
        /** 编码区字体大小（sp） */
        private const val PREEDIT_TEXT_SIZE_SP = 12f
        /** 候选词水平内边距（dp） */
        private const val CANDIDATE_PADDING_H_DP = 12
        /** 高亮圆角半径（dp） */
        private const val HIGHLIGHT_RADIUS_DP = 4f
        /** 分隔线宽度（dp） */
        private const val DIVIDER_WIDTH_DP = 1f
    }

    /** 候选词点击回调 */
    var onCandidateClick: ((index: Int) -> Unit)? = null

    /** 翻页回调：true=下一页, false=上一页 */
    var onPageChange: ((forward: Boolean) -> Unit)? = null

    // 候选词数据
    private var candidates: Array<CandidateProto> = emptyArray()
    private var highlightIndex: Int = -1
    private var preeditText: String? = null

    // 绘制相关的画笔
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
        color = Color.DKGRAY
        typeface = Typeface.DEFAULT
    }

    private val highlightedCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(CANDIDATE_TEXT_SIZE_SP)
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private val preeditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(PREEDIT_TEXT_SIZE_SP)
        color = Color.parseColor("#666666")
        typeface = Typeface.DEFAULT
    }

    private val highlightBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        strokeWidth = dp2px(DIVIDER_WIDTH_DP)
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
     * 更新候选词数据
     * @param context Rime输入上下文
     */
    fun updateCandidates(context: ContextProto?) {
        if (context == null) {
            candidates = emptyArray()
            highlightIndex = -1
            preeditText = null
        } else {
            candidates = context.menu?.candidates ?: emptyArray()
            highlightIndex = context.menu?.highlightedCandidateIndex ?: -1
            preeditText = context.composition?.preedit
        }
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

        // 如果有preedit，先预留preedit区域
        if (!preeditText.isNullOrEmpty()) {
            val preeditWidth = preeditPaint.measureText(preeditText) + paddingH * 2
            x = preeditWidth
        }

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

        if (candidates.isEmpty() && preeditText.isNullOrEmpty()) return

        canvas.save()
        canvas.translate(-scrollOffset, 0f)

        val paddingH = dp2px(CANDIDATE_PADDING_H_DP.toFloat())
        val textBaseline = height / 2f + candidatePaint.textSize / 3f

        // 绘制preedit编码区
        var startX = 0f
        if (!preeditText.isNullOrEmpty()) {
            val preeditBaseline = height / 2f + preeditPaint.textSize / 3f
            canvas.drawText(preeditText!!, paddingH, preeditBaseline, preeditPaint)
            startX = preeditPaint.measureText(preeditText) + paddingH * 2
            // 绘制分隔线
            canvas.drawLine(startX, 4f, startX, height - 4f, dividerPaint)
        }

        // 绘制候选词
        for (i in candidates.indices) {
            if (i >= candidateRects.size) break
            val rect = candidateRects[i]

            // 高亮背景
            if (i == highlightIndex) {
                val highlightRect = RectF(
                    rect.left + 2f,
                    4f,
                    rect.right - 2f,
                    height - 4f
                )
                canvas.drawRoundRect(
                    highlightRect,
                    dp2px(HIGHLIGHT_RADIUS_DP),
                    dp2px(HIGHLIGHT_RADIUS_DP),
                    highlightBgPaint
                )
            }

            // 绘制文字
            val paint = if (i == highlightIndex) highlightedCandidatePaint else candidatePaint
            val textX = rect.left + dp2px(CANDIDATE_PADDING_H_DP.toFloat())
            canvas.drawText(candidates[i].text, textX, textBaseline, paint)

            // 绘制分隔线（非最后一个）
            if (i < candidates.size - 1) {
                canvas.drawLine(rect.right, 8f, rect.right, height - 8f, dividerPaint)
            }
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
        return if (candidateRects.isEmpty()) 0f
        else candidateRects.last().right
    }

    // ===== 单位转换工具 =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
