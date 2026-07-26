package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ziyou.ime.config.KeyboardTheme

/**
 * 涂鸦画板面板：内部结构为「宿主标题栏 + 工具栏 + 画布区」。
 *
 * 挂载在输入视图内容根容器顶部并立即收起键盘/候选区（见 [DoodlePanelCoordinator]），
 * 画布接管全部键盘空间。与 AI/技能面板不同，涂鸦无文字输入需求，
 * **不接管 commitTarget 输入路由**，对输入链路零侵入。
 *
 * 工具栏分两行避免溢出：顶部绘制工具（颜色圆点 + 笔宽循环 + 橡皮），
 * 底部操作栏（撤销 / 复位 / 清空 + 发送）；发送按钮位于画布**下方**操作栏而非
 * 覆盖画布，避免绘画时误触。画布支持**双指拖拽平移**浏览的无限画布
 * （单指绘制、双指平移，见 [DoodleCanvasView]），空画布时发送置灰不可点。
 * 配色全部映射自当前 [KeyboardTheme]，与 [AiPanelView] 同一视觉语言；
 * 画布纸面固定白底（导出图在深色主题下依然清晰）。
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class DoodlePanelView(
    context: Context,
    private val theme: KeyboardTheme,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图并调用 [release]） */
        fun onRequestClose()

        /** 将涂鸦快照导出为图片并发送到当前输入框（commitContent 富媒体提交）。
         *  快照所有权移交宿主，由宿主在导出后 recycle。 */
        fun onSendDoodle(snapshot: Bitmap)

        /** 按键震动反馈 */
        fun performHaptic()
    }

    companion object {
        /** 可选笔色：黑 / 红 / 蓝 + 主题强调色（初始化时动态替换第 4 项） */
        private val BASE_PEN_COLORS = intArrayOf(
            Color.BLACK,
            0xFFD32F2F.toInt(),
            0xFF1565C0.toInt()
        )

        /** 笔宽三档（dp）：细 / 中 / 粗，点击循环切换 */
        private val PEN_WIDTHS_DP = floatArrayOf(2.5f, 4.5f, 8f)
        private val PEN_WIDTH_LABELS = arrayOf("细", "中", "粗")
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 涂鸦画布 */
    private val canvasView: DoodleCanvasView

    /** 发送按钮（位于底部操作栏，空画布置灰） */
    private val sendButton: TextView

    /** 撤销按钮（无笔画时置灰） */
    private val undoButton: TextView

    /** 橡皮按钮（选中态强调色描边） */
    private val eraserButton: TextView

    /** 笔宽循环按钮 */
    private val widthButton: TextView

    /** 颜色圆点（选中项加描边环） */
    private val colorDots = mutableListOf<View>()

    /** 可选笔色（含主题强调色） */
    private val penColors = BASE_PEN_COLORS + theme.candidateHighlightColor

    /** 当前选中的颜色索引 */
    private var selectedColorIndex = 0

    /** 当前笔宽档位索引（默认中） */
    private var widthIndex = 1

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏（与 AI 面板同款结构：左占位 + 居中标题 + 右关闭钮）──
        val titleView = TextView(context).apply {
            text = "涂鸦画板"
            textSize = 15f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            setOnClickListener {
                host.performHaptic()
                host.onRequestClose()
            }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.candidateBackground)
            addView(View(context), LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
            addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(closeButton, LayoutParams(dp(48f), LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))
        addView(View(context).apply { setBackgroundColor(theme.borderColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))

        // ── 画布（先创建，工具栏回调需引用）──
        canvasView = DoodleCanvasView(context, theme).apply {
            penColor = penColors[selectedColorIndex]
            penWidthPx = PEN_WIDTHS_DP[widthIndex] * density
            onContentChanged = { hasContent -> syncButtonStates(hasContent) }
        }

        // ── 顶部工具栏（绘制工具）：颜色圆点 + 笔宽 + 橡皮（仅绘制相关，避免单行溢出）──
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        }
        for (i in penColors.indices) {
            val dot = createColorDot(i)
            colorDots.add(dot)
            toolbar.addView(dot, LayoutParams(dp(26f), dp(26f)).apply {
                marginEnd = dp(10f)
            })
        }
        // 弹性占位：颜色组靠左，笔宽/橡皮靠右
        toolbar.addView(View(context), LayoutParams(0, 0, 1f))

        widthButton = createToolButton(PEN_WIDTH_LABELS[widthIndex]) {
            widthIndex = (widthIndex + 1) % PEN_WIDTHS_DP.size
            widthButton.text = PEN_WIDTH_LABELS[widthIndex]
            canvasView.penWidthPx = PEN_WIDTHS_DP[widthIndex] * density
        }
        eraserButton = createToolButton("橡皮") {
            canvasView.eraserMode = !canvasView.eraserMode
            refreshEraserStyle()
        }
        for (button in listOf(widthButton, eraserButton)) {
            toolbar.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
                marginStart = dp(6f)
            })
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 画布区：weight=1 接管剩余空间（发送按钮移至下方操作栏，不再覆盖画布）──
        val canvasArea = FrameLayout(context).apply {
            setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
            addView(canvasView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(canvasArea, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 底部操作栏：撤销/复位/清空（左）+ 发送（右），位于画布之外避免误触 ──
        undoButton = createToolButton("撤销") { canvasView.undo() }
        val recenterButton = createToolButton("复位") { canvasView.resetView() }
        val clearButton = createToolButton("清空") { canvasView.clear() }
        sendButton = TextView(context).apply {
            text = "发送"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.candidateBackground)
            gravity = Gravity.CENTER
            setPadding(dp(22f), 0, dp(22f), 0)
            background = roundedBg(theme.candidateHighlightColor, 18f, null)
            setOnClickListener {
                if (!canvasView.hasContent()) return@setOnClickListener
                host.performHaptic()
                val snapshot = canvasView.snapshot() ?: return@setOnClickListener
                host.onSendDoodle(snapshot)
            }
        }
        val actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(8f))
            addView(undoButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34f)))
            addView(recenterButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34f)).apply {
                marginStart = dp(6f)
            })
            addView(clearButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34f)).apply {
                marginStart = dp(6f)
            })
            // 弹性占位：操作组靠左，发送靠右
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(sendButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f)).apply {
                marginStart = dp(6f)
            })
        }
        addView(actionBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refreshColorDots()
        refreshEraserStyle()
        syncButtonStates(false)
    }

    // ===== 工具栏构建 =====

    /** 颜色圆点：点击选中该笔色并退出橡皮模式 */
    private fun createColorDot(index: Int): View {
        return View(context).apply {
            setOnClickListener {
                host.performHaptic()
                selectedColorIndex = index
                canvasView.penColor = penColors[index]
                // 选色即回到画笔模式
                canvasView.eraserMode = false
                refreshEraserStyle()
                refreshColorDots()
            }
        }
    }

    /** 工具按钮（笔宽/橡皮/撤销/清空共用样式，与 AI 面板操作钮同一视觉基因） */
    private fun createToolButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            setOnClickListener {
                host.performHaptic()
                onClick()
            }
        }
    }

    // ===== 状态刷新 =====

    /** 刷新颜色圆点：选中项加强调色描边环 */
    private fun refreshColorDots() {
        for (i in colorDots.indices) {
            colorDots[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(penColors[i])
                if (i == selectedColorIndex && !canvasView.eraserMode) {
                    setStroke(dp(3f), theme.candidateHighlightColor)
                } else {
                    setStroke(dp(1f), theme.borderColor)
                }
            }
        }
    }

    /** 刷新橡皮按钮选中态（选中 = 强调色描边） */
    private fun refreshEraserStyle() {
        eraserButton.background = if (canvasView.eraserMode) {
            roundedBg(theme.keyPressedBackground, 8f, theme.candidateHighlightColor)
        } else {
            roundedBg(theme.keyBackground, 8f, theme.borderColor)
        }
        refreshColorDots()
    }

    /** 同步发送/撤销按钮可用态（空画布置灰不可点） */
    private fun syncButtonStates(hasContent: Boolean) {
        sendButton.alpha = if (hasContent) 1f else 0.4f
        undoButton.alpha = if (hasContent) 1f else 0.4f
    }

    /** 圆角背景（可选描边），与键盘主题色一致。 */
    private fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1f), it) }
        }
    }

    /** 面板移除前必须调用：回收画布离屏层。 */
    fun release() {
        canvasView.release()
    }
}
