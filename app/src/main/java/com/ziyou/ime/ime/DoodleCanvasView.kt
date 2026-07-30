package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.ziyou.ime.skin.SkinTheme

/**
 * 自由涂鸦画布（画布键盘核心视图）—— 支持双指拖拽平移的无限画布。
 *
 * ## 坐标系
 * 笔画以**世界坐标**存储于 [strokes]；视口用 [offsetX]/[offsetY] 描述其世界坐标左上角，
 * 屏幕坐标 = 世界坐标 − offset。拖拽即平移 offset，因此画布在四个方向上无限延展，
 * 可在可视区域外继续绘制内容。
 *
 * ## 双层渲染（绘制热路径恒为 O(1)）
 * - 底层：离屏 backing Bitmap 作为**当前视口的位图缓存**（缓存当前 offset 下的可见笔迹）；
 * - 顶层：仅当前正在画的一条实时 [Path]，onDraw = 纸面 + 网格 + drawBitmap + 活动笔迹。
 *
 * 单笔绘制期间 offset 不变，收笔仅把该笔以屏幕坐标烙入位图（O(1)，与笔画总数无关）；
 * **平移**时才按笔画栈整体重放位图（受 [MAX_STROKES] 上限约束，与绘制热路径解耦）。
 *
 * ## 手势区分（避免与其它手势冲突）
 * 单指始终绘制（沿用原有行为）；第二根手指落下即切到平移态并回滚试探中的笔画。
 * 画布在面板内独占触摸区域（面板打开时键盘隐藏），与 [BaseKeyboardView] 键盘手势零冲突。
 *
 * ## 编辑与导出
 * 撤销 = 弹出末笔后重放；橡皮 = CLEAR 混合模式增量绘入离屏位图；
 * 导出 [snapshot] 渲染**全部内容包围盒**（非仅视口），带尺寸上限防 OOM。
 * 画布面固定白底 + 主题描边网格，导出图在深色主题下依然清晰可读。
 */
@SuppressLint("ViewConstructor")
class DoodleCanvasView(
    context: Context,
    private val theme: SkinTheme
) : View(context) {

    companion object {
        /** 笔画总数上限：约束无限画布下的内存占用，超出丢弃最早一笔 */
        private const val MAX_STROKES = 500
        /** 画布圆角（dp） */
        private const val CORNER_RADIUS_DP = 10f
        /** 平移浏览的参考网格间距（dp），随 offset 移动，给无限画布以视觉反馈 */
        private const val GRID_SIZE_DP = 28f
        /** 网格线透明度（叠加在主题描边色上，保持低干扰） */
        private const val GRID_ALPHA = 28
        /** 导出图最大边长（px）：内容包围盒超限时等比降采样，防超大 Bitmap 撑爆内存 */
        private const val MAX_SNAPSHOT_DIM = 2048f
    }

    /** 单笔笔画：**世界坐标**路径 + 画笔参数（撤销重放 / 平移重绘 / 导出的最小单元） */
    private data class Stroke(
        val path: Path,
        val color: Int,
        val widthPx: Float,
        val isEraser: Boolean
    )

    /** 画布内容变化回调（供面板同步撤销/清空/发送按钮的可用态） */
    var onContentChanged: ((hasContent: Boolean) -> Unit)? = null

    /** 当前笔色（橡皮模式下忽略） */
    var penColor: Int = Color.BLACK

    /** 当前笔宽（px） */
    var penWidthPx: Float = dp(4f)

    /** 橡皮模式开关 */
    var eraserMode: Boolean = false

    /** 已固化的笔画栈（世界坐标；撤销/平移重放/导出的唯一真相源） */
    private val strokes = mutableListOf<Stroke>()

    // ===== 视口平移状态 =====
    /** 视口世界坐标左上角（屏幕坐标 = 世界坐标 − offset） */
    private var offsetX = 0f
    private var offsetY = 0f

    /** 平移态：双指拖拽中 */
    private var panning = false

    /** 一旦本次手势出现过多指，抑制绘制直至全部手指抬起（防止收指瞬间误画） */
    private var multiTouchGesture = false

    /** 平移焦点（双指中点）上一帧位置 */
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /** 离屏笔迹层（透明底，缓存当前视口可见笔迹；白色纸面/网格在 onDraw 单独铺） */
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

    /** 正在绘制中的一笔（屏幕坐标；收笔时转世界坐标入栈） */
    private var currentPath: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    /** 活动指针 id（-1 = 无活动笔迹） */
    private var activePointerId = -1

    /** 复用的绘制画笔（每笔按 Stroke 参数重配） */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 纸面（白底圆角卡片）与描边画笔 */
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paperBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.borderColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    /** 平移参考网格画笔（主题描边色 + 低透明度） */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (theme.borderColor and 0x00FFFFFF) or (GRID_ALPHA shl 24)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }

    /** 纸面圆角矩形复用对象 */
    private val paperRectF = RectF()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ===== 尺寸与离屏层 =====

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val old = backingBitmap
        if (old != null && old.width == w && old.height == h) return
        // 重新分配视口缓存并按当前 offset 重放已有笔画
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        backingBitmap = bitmap
        backingCanvas = Canvas(bitmap)
        rebuildViewport()
        old?.recycle()
    }

    /**
     * 清空视口缓存并按笔画栈整体重放到当前 offset（撤销 / 平移 / 尺寸变化时调用）。
     * 复杂度 O(strokes)，受 [MAX_STROKES] 约束；仅在非绘制热路径触发。
     */
    private fun rebuildViewport() {
        val canvas = backingCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (strokes.isEmpty()) return
        canvas.save()
        // 世界坐标 → 屏幕坐标
        canvas.translate(-offsetX, -offsetY)
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, configurePaint(stroke.color, stroke.widthPx, stroke.isEraser))
        }
        canvas.restore()
    }

    /** 按笔画参数配置复用画笔 */
    private fun configurePaint(color: Int, widthPx: Float, isEraser: Boolean): Paint {
        strokePaint.color = if (isEraser) Color.TRANSPARENT else color
        strokePaint.strokeWidth = if (isEraser) widthPx * 3f else widthPx
        strokePaint.xfermode =
            if (isEraser) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        return strokePaint
    }

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(CORNER_RADIUS_DP)
        val inset = paperBorderPaint.strokeWidth / 2f
        paperRectF.set(inset, inset, width - inset, height - inset)
        // 纸面白底
        canvas.drawRoundRect(paperRectF, radius, radius, paperPaint)
        // 网格 + 笔迹裁剪在纸面内，避免越过描边
        canvas.save()
        canvas.clipRect(paperRectF)
        drawGrid(canvas)
        backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // 实时笔迹（屏幕坐标；橡皮走离屏增量绘制，无实时层）
        val path = currentPath
        if (path != null && !eraserMode) {
            canvas.drawPath(path, configurePaint(penColor, penWidthPx, false))
        }
        canvas.restore()
        // 描边最后绘制，压在网格/笔迹之上
        canvas.drawRoundRect(paperRectF, radius, radius, paperBorderPaint)
    }

    /** 绘制随 offset 移动的参考网格（常数条数，O(1)，与笔画数无关）。 */
    private fun drawGrid(canvas: Canvas) {
        val grid = dp(GRID_SIZE_DP)
        if (grid <= 0f) return
        // 世界网格线在屏幕上的首条位置 = -(offset mod grid)
        var x = -(offsetX % grid)
        if (x > 0) x -= grid
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += grid
        }
        var y = -(offsetY % grid)
        if (y > 0) y -= grid
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += grid
        }
    }

    // ===== 触摸采集 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouchGesture = false
                panning = false
                activePointerId = event.getPointerId(0)
                startStroke(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二指落下：回滚试探笔画，进入双指平移
                if (event.pointerCount >= 2) {
                    abortStroke()
                    multiTouchGesture = true
                    panning = true
                    updateFocus(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (panning && event.pointerCount >= 2) {
                    handlePan(event)
                } else if (!multiTouchGesture && currentPath != null) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index < 0) return true
                    // 回补系统合并的历史采样点，高刷屏上笔迹更顺滑
                    for (h in 0 until event.historySize) {
                        extendStroke(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    }
                    extendStroke(event.getX(index), event.getY(index))
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬指后剩余不足两指：退出平移；剩余单指不恢复绘制（等全部抬起）
                if (event.pointerCount <= 2) {
                    panning = false
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!multiTouchGesture) {
                    finishStroke()
                } else {
                    currentPath = null
                    activePointerId = -1
                }
                panning = false
                multiTouchGesture = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 记录双指中点为平移焦点 */
    private fun updateFocus(event: MotionEvent) {
        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
    }

    /** 处理双指平移：焦点位移反向作用于 offset（内容随手指移动），重绘视口。 */
    private fun handlePan(event: MotionEvent) {
        val fx = (event.getX(0) + event.getX(1)) / 2f
        val fy = (event.getY(0) + event.getY(1)) / 2f
        val dx = fx - lastFocusX
        val dy = fy - lastFocusY
        if (dx == 0f && dy == 0f) return
        // 内容跟随手指：手指右移则视口世界坐标左移
        offsetX -= dx
        offsetY -= dy
        lastFocusX = fx
        lastFocusY = fy
        rebuildViewport()
        invalidate()
    }

    private fun startStroke(x: Float, y: Float) {
        currentPath = Path().apply { moveTo(x, y) }
        lastX = x
        lastY = y
        // 点按也可留下墨点：先补一个极小段
        extendStroke(x + 0.1f, y + 0.1f)
    }

    /** 追加一段笔迹：二次贝塞尔中点平滑（onDraw 为 O(1)，直接整视图重绘） */
    private fun extendStroke(x: Float, y: Float) {
        val path = currentPath ?: return
        val midX = (lastX + x) / 2f
        val midY = (lastY + y) / 2f
        path.quadTo(lastX, lastY, midX, midY)

        // 橡皮：增量绘入离屏层（CLEAR 无法在实时层预览）
        if (eraserMode) {
            backingCanvas?.drawPath(path, configurePaint(0, penWidthPx, true))
        }
        invalidate()

        lastX = x
        lastY = y
    }

    /** 回滚试探中的笔画（双指平移开始时）：丢弃 pen 半成品；橡皮已增量擦除则重放还原。 */
    private fun abortStroke() {
        if (currentPath == null) return
        val wasEraser = eraserMode
        currentPath = null
        activePointerId = -1
        if (wasEraser) {
            rebuildViewport()
            invalidate()
        }
    }

    /** 收笔：以世界坐标入栈，pen 同时烙入视口位图（O(1)，offset 本笔内恒定）。 */
    private fun finishStroke() {
        val path = currentPath ?: return
        currentPath = null
        activePointerId = -1
        if (!eraserMode) {
            // 屏幕坐标直接烙入（offset 本笔内不变）
            backingCanvas?.drawPath(path, configurePaint(penColor, penWidthPx, false))
        }
        // 世界坐标副本入栈（供平移重放 / 撤销 / 导出）
        val worldPath = Path(path).apply { offset(offsetX, offsetY) }
        strokes.add(Stroke(worldPath, penColor, penWidthPx, eraserMode))
        // 超出上限丢弃最早一笔以约束内存（无限画布下极少触发）
        if (strokes.size > MAX_STROKES) {
            strokes.removeAt(0)
            rebuildViewport()
        }
        invalidate()
        onContentChanged?.invoke(hasContent())
    }

    // ===== 编辑操作 =====

    /** 是否已有内容（含橡皮笔画：有过任何操作即视为有内容） */
    fun hasContent(): Boolean = strokes.any { !it.isEraser }

    /** 撤销最后一笔（矢量重放，毫秒级） */
    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        rebuildViewport()
        invalidate()
        onContentChanged?.invoke(hasContent())
    }

    /** 清空画布（同时复位视口到原点） */
    fun clear() {
        if (strokes.isEmpty() && currentPath == null && offsetX == 0f && offsetY == 0f) return
        strokes.clear()
        currentPath = null
        activePointerId = -1
        offsetX = 0f
        offsetY = 0f
        backingCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
        onContentChanged?.invoke(false)
    }

    /**
     * 复位视口：有内容时居中到内容包围盒，否则回原点。
     * 供面板「复位」按钮调用，避免用户在无限画布中迷失。
     */
    fun resetView() {
        val bounds = contentBounds()
        if (bounds == null) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX = bounds.centerX() - width / 2f
            offsetY = bounds.centerY() - height / 2f
        }
        rebuildViewport()
        invalidate()
    }

    /** 全部 pen 笔画的世界坐标包围盒（含笔宽外扩），无内容返回 null。 */
    private fun contentBounds(): RectF? {
        val bounds = RectF()
        val tmp = RectF()
        var has = false
        var maxWidth = 0f
        for (stroke in strokes) {
            if (stroke.isEraser) continue
            stroke.path.computeBounds(tmp, true)
            if (!has) {
                bounds.set(tmp)
                has = true
            } else {
                bounds.union(tmp)
            }
            if (stroke.widthPx > maxWidth) maxWidth = stroke.widthPx
        }
        if (!has) return null
        val pad = maxWidth / 2f + dp(8f)
        bounds.inset(-pad, -pad)
        return bounds
    }

    /**
     * 取全部内容的位图快照（透明底，仅笔画；调用方负责 recycle）。
     *
     * 无限画布下渲染**全部内容包围盒**（非仅当前视口），确保视口外内容一并导出；
     * 包围盒超 [MAX_SNAPSHOT_DIM] 时等比降采样防 OOM。在主线程调用，使用独立画笔。
     */
    fun snapshot(): Bitmap? {
        val bounds = contentBounds() ?: return null
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0f || h <= 0f) return null
        val scale = minOf(1f, MAX_SNAPSHOT_DIM / maxOf(w, h))
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // 按落笔顺序重放（pen 绘制 / 橡皮 CLEAR），还原合成结果
        for (stroke in strokes) {
            paint.color = if (stroke.isEraser) Color.TRANSPARENT else stroke.color
            paint.strokeWidth = if (stroke.isEraser) stroke.widthPx * 3f else stroke.widthPx
            paint.xfermode = if (stroke.isEraser) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            canvas.drawPath(stroke.path, paint)
        }
        return bitmap
    }

    /** 面板移除前必须调用：回收离屏层，涂鸦功能零驻留内存 */
    fun release() {
        backingCanvas = null
        backingBitmap?.recycle()
        backingBitmap = null
        strokes.clear()
        currentPath = null
    }
}
