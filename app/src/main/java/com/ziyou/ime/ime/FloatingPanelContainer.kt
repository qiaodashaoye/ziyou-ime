package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ziyou.ime.config.DisplayModeManager
import com.ziyou.ime.config.KeyboardTheme
import com.ziyou.ime.core.floating.FloatingPanelGeometry
import com.ziyou.ime.core.floating.PanelPoint

/**
 * 悬浮面板容器（FLOATING 形态的输入视图根）
 *
 * 撑满 IME 窗口可用高度的透明容器，内部持有一个小的悬浮面板：
 *
 * ```
 * FloatingPanelContainer（透明，面板外区域经 onComputeInsets 裁剪后触摸穿透）
 * └── panel: LinearLayout（圆角半透明卡片）
 *     ├── 拖动条（≡ 拖动手柄 + 「▣ 停靠」按钮）
 *     └── content（preedit + 候选 + 键盘容器，由 Service 传入，复用现有视图）
 * ```
 *
 * 职责：
 * - 面板宽度计算与摆放（几何计算委托 [FloatingPanelGeometry]，纯逻辑可单测）
 * - 拖动条拖拽移动，逐帧边界钳制，松手后按屏幕方向持久化位置
 * - 位置采用 translationX/Y 位移实现，拖动过程零 relayout（保持 60fps）；
 *   位移触发的绘制遍历会让系统重新回调 onComputeInsets，触摸区域随面板实时同步
 * - 对外暴露 [getPanelRectInWindow] 供 Service 的 onComputeInsets 裁剪触摸区域
 */
@SuppressLint("ViewConstructor")
class FloatingPanelContainer(
    context: Context,
    content: View,
    theme: KeyboardTheme
) : FrameLayout(context) {

    companion object {
        /** 拖动条高度（dp） */
        private const val DRAG_HANDLE_HEIGHT_DP = 26
        /** 面板圆角（dp） */
        private const val PANEL_RADIUS_DP = 12f
        /** 面板背景不透明度（0-255，约 0.93，减弱对游戏画面的遮挡感） */
        private const val PANEL_BG_ALPHA = 237
    }

    /** 用户点击「停靠」按钮，请求退出悬浮模式（由 Service 切回 DOCKED） */
    var onRequestDock: (() -> Unit)? = null

    /** 悬浮面板（圆角卡片） */
    private val panel: LinearLayout

    /** 面板位置是否已初始化（首次 layout 时按持久化位置/默认位置摆放） */
    private var positionInitialized = false

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    init {
        // 容器透明：面板外的像素不绘制任何内容，配合 insets 裁剪实现视觉+触摸双穿透
        setBackgroundColor(Color.TRANSPARENT)

        val panelWidth = computePanelWidth()

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(PANEL_RADIUS_DP)
                setColor(withAlpha(theme.keyboardBackground, PANEL_BG_ALPHA))
                setStroke(dp2px(1f).toInt(), theme.borderColor)
            }
            clipToOutline = true
            addView(createDragHandle(theme))
            addView(content, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        addView(panel, LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // 面板自身尺寸变化（如悬浮中切换键盘布局导致高度变化）后重新钳制位置，
        // 避免面板底部越出容器可视区
        panel.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            val sizeChanged = (r - l) != (or2 - ol) || (b - t) != (ob - ot)
            if (sizeChanged && width > 0 && height > 0) {
                applyPosition(
                    FloatingPanelGeometry.clampPosition(
                        panel.translationX.toInt(), panel.translationY.toInt(),
                        r - l, b - t, width, height
                    )
                )
            }
        }
    }

    // ===== 测量：撑满 IME 窗口可用区域，为面板提供全屏活动范围 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 高度取窗口给定的可用高度（IME 窗口本身全屏，input area 底部对齐 wrap 内容；
        // 这里主动占满可用高度，使面板可被拖到屏幕任意位置）
        val height = MeasureSpec.getSize(heightMeasureSpec)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 首次布局 / 尺寸变化（转屏）后：恢复持久化位置或落到默认右下角，并钳制到容器内
        if (!positionInitialized || changed) {
            positionInitialized = true
            applyPosition(resolveInitialPosition())
        }
    }

    // ===== 对外：面板矩形（窗口坐标系，供 onComputeInsets 使用） =====

    /**
     * 获取面板在 IME 窗口中的矩形。返回 false 表示面板尚未完成布局
     * （此时调用方应回退到默认 insets，避免设置空触摸区域吞掉所有触摸）。
     */
    fun getPanelRectInWindow(outRect: Rect): Boolean {
        if (panel.width == 0 || panel.height == 0) return false
        val loc = IntArray(2)
        panel.getLocationInWindow(loc)
        outRect.set(loc[0], loc[1], loc[0] + panel.width, loc[1] + panel.height)
        return true
    }

    // ===== 拖动条 =====

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragHandle(theme: KeyboardTheme): View {
        val handle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(DRAG_HANDLE_HEIGHT_DP.toFloat()).toInt()
            )
            setBackgroundColor(withAlpha(theme.borderColor, 90))
        }

        // 左侧拖动手柄标识（占满剩余宽度，整条区域可拖）
        handle.addView(TextView(context).apply {
            text = "═"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(theme.keyTextColor)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // 右侧「停靠」按钮：退出悬浮模式
        handle.addView(TextView(context).apply {
            text = "▣ 停靠"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(theme.candidateHighlightColor)
            setPadding(dp2px(12f).toInt(), 0, dp2px(12f).toInt(), 0)
            setOnClickListener { onRequestDock?.invoke() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        // 拖拽：DOWN 记录起点，MOVE 逐帧钳制位移，UP 持久化最终位置
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = panel.translationX.toInt()
                    startY = panel.translationY.toInt()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pos = FloatingPanelGeometry.dragPosition(
                        startX, startY, downRawX, downRawY, event.rawX, event.rawY,
                        panel.width, panel.height, width, height
                    )
                    // translation 位移不触发 relayout；位移引发的绘制遍历会让系统
                    // 重新回调 onComputeInsets，触摸区域随面板同步移动
                    applyPosition(pos)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = kotlin.math.abs(event.rawX - downRawX) + kotlin.math.abs(event.rawY - downRawY)
                    DisplayModeManager.savePanelPosition(context, isLandscape, currentPosition())
                    // 位移极小视作点击（拖动条空白区不响应点击，无副作用）
                    moved > dp2px(4f)
                }
                else -> false
            }
        }
        return handle
    }

    // ===== 位置管理 =====

    /** 恢复持久化位置（钳制到当前容器内）或默认右下角位置 */
    private fun resolveInitialPosition(): PanelPoint {
        val saved = DisplayModeManager.loadPanelPosition(context, isLandscape)
        return if (saved != null) {
            FloatingPanelGeometry.clampPosition(
                saved.x, saved.y, panel.width, panel.height, width, height
            )
        } else {
            FloatingPanelGeometry.defaultPosition(
                panel.width, panel.height, width, height,
                dp2px(DisplayModeManager.PANEL_EDGE_MARGIN_DP.toFloat()).toInt()
            )
        }
    }

    private fun applyPosition(position: PanelPoint) {
        panel.translationX = position.x.toFloat()
        panel.translationY = position.y.toFloat()
    }

    private fun currentPosition(): PanelPoint =
        PanelPoint(panel.translationX.toInt(), panel.translationY.toInt())

    // ===== 工具 =====

    private fun computePanelWidth(): Int {
        val dm = resources.displayMetrics
        return FloatingPanelGeometry.panelWidth(
            dm.widthPixels,
            dp2px(DisplayModeManager.PANEL_MIN_WIDTH_DP.toFloat()).toInt(),
            dp2px(DisplayModeManager.PANEL_MAX_WIDTH_DP.toFloat()).toInt(),
            DisplayModeManager.PANEL_WIDTH_RATIO
        )
    }

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
