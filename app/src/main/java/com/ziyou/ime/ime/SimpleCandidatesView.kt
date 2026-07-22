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

    /** 拼音候选点击回调 */
    var onPinyinSelect: ((String) -> Unit)? = null

    // 候选词数据
    private var candidates: Array<CandidateProto> = emptyArray()
    private var highlightIndex: Int = -1
    private var preeditText: String? = null

    /** 编码区显式显示文本（如九宫格拼音候选），非空时覆盖 Rime 原始 preedit */
    private var previewText: String? = null

    /** 拼音候选列表（九宫格模式下显示） */
    private var pinyinCandidates: List<String> = emptyList()

    /** 拼音候选的点击区域 */
    private val pinyinRects = mutableListOf<RectF>()

    /** 拼音候选画笔 */
    private val pinyinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(11f)
        color = Color.parseColor("#1A73E8")  // Material Blue
        typeface = Typeface.DEFAULT
    }

    /** 拼音候选背景画笔 */
    private val pinyinBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F0FE")  // Light blue background
        style = Paint.Style.FILL
    }

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
            // 优先检查拼音候选点击
            for (i in pinyinRects.indices) {
                if (pinyinRects[i].contains(x, y)) {
                    onPinyinSelect?.invoke(pinyinCandidates[i])
                    return true
                }
            }
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
        highlightedCandidatePaint.color = theme.candidateBackground
        highlightBgPaint.color = theme.candidateHighlightColor
        preeditPaint.color = theme.preeditTextColor
        dividerPaint.color = theme.borderColor
        // 拼音候选主题适配
        pinyinPaint.color = theme.candidateHighlightColor
        pinyinBgPaint.color = Color.argb(30,
            Color.red(theme.candidateHighlightColor),
            Color.green(theme.candidateHighlightColor),
            Color.blue(theme.candidateHighlightColor)
        )
        invalidate()
    }

    /**
     * 设置拼音候选列表（九宫格模式下的结构化拼音数据）
     * 以 pill 形式在编码区和候选词之间展示可点击的拼音选项
     */
    fun setPinyinCandidates(pinyins: List<String>?) {
        val newList = pinyins ?: emptyList()
        if (pinyinCandidates == newList) return
        pinyinCandidates = newList
        recalculateLayout()
        invalidate()
    }

    /**
     * 设置编码区显示文本（预览/拼音候选）。
     * 非空时作为编码区的显示内容，覆盖 Rime 原始 preedit：
     * - 九宫格：Service 依据候选 spelling_hints 传入可能的拼音组合（如 "guo gun hun huo"）
     * - null 时回退到 Rime 原始 preedit（全键盘拼音）
     */
    fun setComposingPreview(preview: String?) {
        val normalized = preview?.takeIf { it.isNotEmpty() }
        if (previewText == normalized) return
        previewText = normalized
        recalculateLayout()
        invalidate()
    }

    /** 实际展示的编码区文本：若有显式显示文本（如九宫格拼音候选）则优先，否则用 Rime preedit */
    private fun displayPreedit(): String? {
        // 当拼音以 pill 形式展示时，编码区只显示 Rime 原始 preedit
        if (pinyinCandidates.isNotEmpty()) {
            return preeditText?.takeIf { it.isNotEmpty() }
        }
        // 无交互式拼音时，使用 previewText（九宫格拼音文本）覆盖
        previewText?.let { return it }
        return preeditText?.takeIf { it.isNotEmpty() }
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
        pinyinRects.clear()
        val paddingH = dp2px(CANDIDATE_PADDING_H_DP.toFloat())
        var x = paddingH

        // 如果有preedit（含多击预览），先预留preedit区域
        val preedit = displayPreedit()
        if (!preedit.isNullOrEmpty()) {
            val preeditWidth = preeditPaint.measureText(preedit) + paddingH * 2
            x = preeditWidth
        }

        // 拼音候选位置计算（pill 样式，紧跟 preedit 之后）
        if (pinyinCandidates.isNotEmpty()) {
            val pinyinPaddingH = dp2px(6f)
            val pinyinGap = dp2px(4f)
            for (pinyin in pinyinCandidates) {
                val textWidth = pinyinPaint.measureText(pinyin)
                val pillWidth = textWidth + pinyinPaddingH * 2
                pinyinRects.add(RectF(x, 2f, x + pillWidth, height.toFloat() - 2f))
                x += pillWidth + pinyinGap
            }
            // 拼音区后添加分隔线间距
            x += dp2px(4f)
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

        val preedit = displayPreedit()
        if (candidates.isEmpty() && preedit.isNullOrEmpty() && pinyinCandidates.isEmpty()) return

        canvas.save()
        canvas.translate(-scrollOffset, 0f)

        val paddingH = dp2px(CANDIDATE_PADDING_H_DP.toFloat())
        val textBaseline = height / 2f + candidatePaint.textSize / 3f

        // 绘制preedit编码区（Rime preedit + 多击预览）
        var startX = 0f
        if (!preedit.isNullOrEmpty()) {
            val preeditBaseline = height / 2f + preeditPaint.textSize / 3f
            canvas.drawText(preedit, paddingH, preeditBaseline, preeditPaint)
            startX = preeditPaint.measureText(preedit) + paddingH * 2
            // 绘制分隔线
            canvas.drawLine(startX, 4f, startX, height - 4f, dividerPaint)
        }

        // 绘制拼音候选 pills
        for (i in pinyinCandidates.indices) {
            if (i >= pinyinRects.size) break
            val rect = pinyinRects[i]
            // 绘制 pill 背景（圆角矩形）
            canvas.drawRoundRect(rect, dp2px(10f), dp2px(10f), pinyinBgPaint)
            // 绘制拼音文字（居中）
            val pinyinTextX = rect.left + dp2px(6f)
            val pinyinTextBaseline = rect.centerY() + pinyinPaint.textSize / 3f
            canvas.drawText(pinyinCandidates[i], pinyinTextX, pinyinTextBaseline, pinyinPaint)
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
        if (candidateRects.isNotEmpty()) return candidateRects.last().right
        if (pinyinRects.isNotEmpty()) return pinyinRects.last().right
        return 0f
    }

    // ===== 单位转换工具 =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
