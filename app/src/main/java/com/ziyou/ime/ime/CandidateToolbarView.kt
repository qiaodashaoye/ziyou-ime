package com.ziyou.ime.ime

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.ziyou.ime.R
import com.ziyou.ime.core.skin.SkinColor
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinTheme
import com.ziyou.ime.core.toolbar.ToolbarConfigLogic
import com.ziyou.ime.data.ToolbarConfigRepository

/**
 * 候选区功能按钮栏
 *
 * 与「编码区 [PreeditOverlayView] + 候选词列表 [SimpleCandidatesView]」整体叠放在
 * 同一区域（FrameLayout 覆盖），显隐互斥由 Service 层控制（观察 Rime 上下文变化）：
 * - 无候选词时显示本按钮栏
 * - 有候选词时隐藏，让位给编码区与候选词列表
 *
 * 按钮内容与顺序由用户在设置页自定义（[ToolbarConfigRepository]），
 * 目录见 [ToolbarItem]；本视图注册 SharedPreferences 监听（观察者模式），
 * 设置页保存后无需重启输入法即时刷新。左侧固定按钮为应用 Logo，
 * 点击打开工具面板（全量工具目录见 [ToolPanelCatalog]，设置入口已移入其中）。
 *
 * 遵循本项目「数据-皮肤-绘制」分离与 Canvas 纯绘制的既有风格
 * （见 [BaseKeyboardView] / [SimpleCandidatesView]）：按钮绘制为主题化胶囊，
 * 全部配色取自 [SkinTheme]，无硬编码样式。点击通过 [onButtonClick] 回调
 * 携带 [KeyCode] 自定义功能码向上抛出，由 Service 的 handleSoftKeyPress 统一路由，
 * View 层不持有 Service 引用。
 *
 * 布局：Logo（工具面板入口）与收起键盘为常驻固定按钮，分别钉在视图最左侧与最右侧，
 * 不参与配置与滚动；动态按钮为固定单元宽，在两个固定按钮之间的动态区内
 * 从右往左排列（首个贴收起按钮左缘）；内容总宽超出动态区宽度时支持
 * 水平拖动与 fling 惯性滚动（GestureDetector + OverScroller，
 * 与 [SimpleCandidatesView] 同款交互风格），绘制时仅遍历视口内可见按钮，
 * 动态区绘制被裁剪在两侧固定按钮之间，滚动不会盖住固定按钮。
 *
 * 无障碍：经 [ExploreByTouchHelper] 将每个 Canvas 按钮（含两个固定按钮）暴露为虚拟节点
 * （contentDescription 朗读 + 焦点导航 + 点击动作），动态节点边界随滚动偏移同步。
 */
class CandidateToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 视图高度（dp）：编码区 + 候选词列表的总高，与二者垂直堆叠后的
         *  整体高度一致，显隐切换时无高度跳动（高度常量单一来源于各自视图） */
        private const val VIEW_HEIGHT_DP =
            PreeditOverlayView.VIEW_HEIGHT_DP + SimpleCandidatesView.VIEW_HEIGHT_DP
        /** 按钮文字相对皮肤功能键字号的增量、胶囊混色比与左右留白均已皮肤化
         *  （见 SkinToolbarSpec / SkinDefaults.TOOLBAR_*），本类不再持有对应常量 */
        /** 单个按钮的固定单元宽度（dp）：从右往左排列，超宽时水平滚动 */
        private const val CELL_WIDTH_DP = 52f
        /** 胶囊上下留白（dp）：胶囊高 = 视图高 - 2 * 该值 */
        private const val PILL_V_INSET_DP = 8f
        /** 底部分隔细线高度（dp） */
        private const val DIVIDER_HEIGHT_DP = 0.5f
        /** 常驻固定按钮：收起键盘（不入 [ToolbarItem] 目录，不参与用户自定义） */
        private const val HIDE_LABEL = "\u2304"
        /** 固定收起按钮的无障碍描述 */
        private const val HIDE_DESCRIPTION = "收起键盘"
        /** 固定 Logo 按钮（左侧，应用图标）的无障碍描述：打开工具面板
         *  （原固定设置按钮已替换，设置入口移入工具面板，见 [ToolPanelCatalog]） */
        private const val LOGO_DESCRIPTION = "字由工具面板"
        /** Logo 图标在单元格内的边长占胶囊高的比例（留白后视觉与文字胶囊对齐） */
        private const val LOGO_SIZE_RATIO = 1.15f
    }

    /** 当前展示的动态按钮（配置驱动，经目录清洗后永不为空；不含两个固定按钮） */
    private var items: List<ToolbarItem> = loadItems()

    /**
     * 固定收起按钮（右侧）的虚拟索引（触摸命中/按下高亮/无障碍节点共用）：
     * 紧跟在动态按钮之后，随 items 刷新变化（刷新时 touchHelper 已 invalidateRoot）。
     */
    private val hideIndex: Int get() = items.size

    /** 固定 Logo 按钮（左侧）的虚拟索引：紧跟在固定收起按钮之后 */
    private val logoIndex: Int get() = items.size + 1

    /** 是否为常驻固定按钮的虚拟索引（收起 / Logo） */
    private fun isFixedIndex(index: Int): Boolean =
        index == hideIndex || index == logoIndex

    /** 按钮点击回调，参数为 [KeyCode] 自定义功能码（由 Service 统一处理） */
    var onButtonClick: ((keyCode: Int) -> Unit)? = null

    /** 当前按钮字号（sp，随皮肤更新；缩放时以此为基准重算） */
    private var buttonTextSizeSp = 14f

    /**
     * 全局缩放因子（悬浮模式用）：同步缩小视图高度与按钮字号，
     * 与键盘/候选视图的 scaleFactor 保持一致。默认 1.0，停靠模式零影响。
     */
    var scaleFactor: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                textPaint.textSize = sp2px(buttonTextSizeSp)
                minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
                // 单元宽随缩放变化，滚动位置归零重新右对齐
                abortFling()
                scrollOffset = 0f
                requestLayout()
                invalidate()
            }
        }

    /** 当前按下的按钮索引，-1 表示无按下 */
    private var pressedIndex = -1

    // ===== 滚动状态（内容右对齐，从右往左排列；超宽时可水平滑动） =====

    /** 滚动偏移（px）：0 = 首按钮贴右缘，增大表示内容右移露出左侧隐藏按钮 */
    private var scrollOffset = 0f

    /** fling 惯性滚动驱动器 */
    private val scroller = OverScroller(context)

    /** fling 逐帧推进任务：滚动结束后同步无障碍节点边界 */
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollOffset = scroller.currX.toFloat()
                invalidate()
                postOnAnimation(this)
            } else {
                touchHelper.invalidateRoot()
            }
        }
    }

    /** 应用 Logo 图标（左侧固定按钮，替代原「设」文字胶囊，点击打开工具面板） */
    private val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)

    /** 当前皮肤（画笔颜色与工具栏样式的单一来源，见 [applySkin]） */
    private var skin: SkinTheme = SkinManager.getCurrentSkin(context)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp2px(14f)
        color = Color.DKGRAY
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F5F5")
        style = Paint.Style.FILL
    }

    /** 胶囊常态底色（背景色与边框色的柔和混合） */
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EBEBEB")
        style = Paint.Style.FILL
    }

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.FILL
    }

    /** 按钮投影画笔（皮肤 toolbarButtonShadow 开启时与键盘按键同源绘制） */
    private val pillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 按钮描边画笔（皮肤 toolbarButtonBorderWidthDp > 0 时使用） */
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /** 按下态文字强调色（取主题候选高亮色） */
    private var pressedTextColor = Color.DKGRAY

    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.FILL
    }

    /** 配置变更监听（观察者模式）：持强引用防止 SharedPreferences 弱引用回收 */
    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadConfig()
    }

    /**
     * 手势识别：单击触发按钮、拖动/fling 驱动水平滚动
     * （与 [SimpleCandidatesView] 同款 GestureDetector 风格，点击与滑动自动互斥）。
     */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            abortFling()
            pressedIndex = buttonIndexAt(e.x, e.y)
            if (pressedIndex >= 0) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val index = buttonIndexAt(e.x, e.y)
            if (index >= 0 && index == pressedIndex) {
                onButtonClick?.invoke(keyCodeAt(index))
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 进入拖动即取消按下高亮（不触发点击）
            if (pressedIndex >= 0) {
                pressedIndex = -1
            }
            // 手指右移（distanceX < 0）内容随之右移，露出左侧隐藏按钮
            scrollOffset = (scrollOffset - distanceX).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (maxScroll() <= 0f) return false
            scroller.fling(
                scrollOffset.toInt(), 0,
                velocityX.toInt(), 0,
                0, maxScroll().toInt(),
                0, 0
            )
            postOnAnimation(flingRunnable)
            return true
        }
    })

    /** 无障碍虚拟按钮支持（Canvas 绘制的按钮逐个暴露为可朗读/可聚焦节点） */
    private val touchHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = buttonIndexAt(x, y)
            return if (index >= 0) index else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in items.indices) virtualViewIds.add(i)
            virtualViewIds.add(hideIndex) // 固定收起按钮始终可见
            virtualViewIds.add(logoIndex) // 固定 Logo 按钮始终可见
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            if (!isFixedIndex(virtualViewId) && items.getOrNull(virtualViewId) == null) {
                // 配置刷新与无障碍遍历竞态时的兜底：给出合法空节点
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.className = Button::class.java.name
            node.contentDescription = descriptionAt(virtualViewId)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (!isFixedIndex(virtualViewId)) {
                // 固定按钮永远可见，无需滚动展示动作
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
            }
            val rect = cellRect(virtualViewId)
            node.setBoundsInParent(
                Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    if (isFixedIndex(virtualViewId) || items.getOrNull(virtualViewId) != null) {
                        onButtonClick?.invoke(keyCodeAt(virtualViewId))
                    }
                    invalidateVirtualView(virtualViewId)
                    return true
                }
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id -> {
                    // TalkBack 聚焦视口外动态按钮时请求滚动展示（固定按钮不会进入此分支）
                    ensureCellVisible(virtualViewId)
                    return true
                }
            }
            return false
        }
    }

    init {
        minimumHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        ViewCompat.setAccessibilityDelegate(this, touchHelper)
        // 构造期即接当前皮肤（快照命中 O(1)），避免首帧硬编码灰打底；
        // Service 层切皮肤时仍会再次下发 applySkin
        applySkin(SkinManager.getCurrentSkin(context))
    }

    // ===== 配置（数据层） =====

    /** 读取用户配置并经目录清洗映射为动态按钮列表（未知 id 剔除、空配置回退默认；
     *  固定 Logo/收起按钮不在配置内） */
    private fun loadItems(): List<ToolbarItem> {
        val ids = ToolbarConfigLogic.sanitize(
            ToolbarConfigRepository.getItemIds(context),
            ToolbarItem.ALL_IDS,
            ToolbarConfigRepository.DEFAULT_IDS
        )
        return ids.mapNotNull { ToolbarItem.fromId(it) }
    }

    /** 重新加载配置并刷新（配置监听回调 / 外部主动触发） */
    fun reloadConfig() {
        items = loadItems()
        pressedIndex = -1
        // 按钮增减后内容总宽变化，滚动位置归零重新右对齐
        abortFling()
        scrollOffset = 0f
        touchHelper.invalidateRoot()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ToolbarConfigRepository.registerListener(context, configListener)
        // 挂载时同步一次：视图缓存期间（如输入视图复用）配置可能已变化
        reloadConfig()
    }

    override fun onDetachedFromWindow() {
        abortFling()
        ToolbarConfigRepository.unregisterListener(context, configListener)
        super.onDetachedFromWindow()
    }

    // ===== 主题（样式层） =====

    /**
     * 应用皮肤，与候选词视图和键盘视图保持视觉一致（由 Service 层调用）。
     * 工具栏全部外观（背景/按钮底色/圆角/投影/描边/字号/字重/间距/分隔线）
     * 均由 [SkinTheme] 工具栏字段驱动，缺省值链在解析期已从候选区/键盘配色
     * 派生落定，皮肤切换后整栏自动换肤。
     */
    fun applySkin(skin: SkinTheme) {
        this.skin = skin
        bgPaint.color = SkinColor.scaleAlpha(skin.toolbarBackground, skin.backgroundAlpha)
        textPaint.color = skin.toolbarTextColor
        textPaint.typeface = skin.toolbarTypeface
        buttonTextSizeSp = skin.toolbarTextSizeSp
        textPaint.textSize = sp2px(buttonTextSizeSp)
        pillPaint.color = SkinColor.scaleAlpha(skin.toolbarButtonBackground, skin.backgroundAlpha)
        pressedPaint.color = SkinColor.scaleAlpha(skin.keyPressedBackground, skin.backgroundAlpha)
        pressedTextColor = skin.candidateHighlightColor
        dividerPaint.color = skin.borderColor
        // 按钮投影与键盘按键同源：皮肤阴影色，radiusDp > 0 时弥散模糊
        pillShadowPaint.color = skin.keyShadowColor
        val shadowRadius = skin.keyShadow?.radiusDp ?: 0f
        pillShadowPaint.maskFilter = if (shadowRadius > 0f) {
            BlurMaskFilter(dp2px(shadowRadius), BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        pillBorderPaint.color = skin.borderColor
        pillBorderPaint.strokeWidth = dp2px(skin.toolbarButtonBorderWidthDp)
        invalidate()
    }

    // ===== 测量与绘制 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp2px(VIEW_HEIGHT_DP.toFloat()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 宽度变化（如旋转/悬浮切换）后重新钳制滚动范围，虚拟节点边界需重算
        abortFling()
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll())
        touchHelper.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 背景（与候选词区共享同一背景色，视觉连续为整体）
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (width > 0) {
            val vInset = dp2px(PILL_V_INSET_DP)
            val hInset = dp2px(skin.toolbarButtonSpacingDp)
            val textBaseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

            // 动态按钮：裁剪到两侧固定按钮之间，滚动时不侵入固定区
            if (items.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(dynamicLeftEdge(), 0f, dynamicRightEdge(), height.toFloat())
                for (i in items.indices) {
                    val cell = cellRect(i)
                    // 仅绘制视口内可见按钮（滚动时跳过两侧移出的单元）
                    if (cell.right <= dynamicLeftEdge()) break // 从右往左排列，后续单元更靠左，均不可见
                    if (cell.left >= dynamicRightEdge()) continue
                    drawButton(canvas, i, cell, vInset, hInset, textBaseline)
                }
                canvas.restore()
            }

            // 固定按钮：Logo 始终绘在最左侧、收起始终绘在最右侧，不随滚动移动
            drawButton(canvas, logoIndex, cellRect(logoIndex), vInset, hInset, textBaseline)
            drawButton(canvas, hideIndex, cellRect(hideIndex), vInset, hInset, textBaseline)
        }

        // 底部细线：与下方键盘区形成视觉分隔（一体化皮肤可关闭，
        // 关闭后工具栏背景与键盘底板无缝衔接）
        if (skin.toolbarShowDivider) {
            val dividerHeight = dp2px(DIVIDER_HEIGHT_DP).coerceAtLeast(1f)
            canvas.drawRect(0f, height - dividerHeight, width.toFloat(), height.toFloat(), dividerPaint)
        }
    }

    /** 绘制单个按钮（动态与固定按钮共用：胶囊 + 文字 + 按下态变色；
     *  圆角/投影/描边均由皮肤工具栏参数驱动；
     *  Logo 按钮为图标绘制，常态无胶囊底，按下态保留胶囊高亮反馈） */
    private fun drawButton(
        canvas: Canvas,
        index: Int,
        cell: RectF,
        vInset: Float,
        hInset: Float,
        textBaseline: Float
    ) {
        val pill = RectF(
            cell.left + hInset, vInset, cell.right - hInset, height - vInset
        )
        // 圆角：皮肤声明 dp 值；负值（缺省）= 胶囊全圆角（现行视觉）
        val radius = if (skin.toolbarButtonCornerRadiusDp < 0f) {
            pill.height() / 2f
        } else {
            dp2px(skin.toolbarButtonCornerRadiusDp)
        }
        val pressed = index == pressedIndex
        if (index == logoIndex && logoDrawable != null) {
            // Logo 按钮：按下态先铺胶囊高亮，再居中绘应用图标（图标自带配色，不随皮肤变色）
            if (pressed) canvas.drawRoundRect(pill, radius, radius, pressedPaint)
            val size = pill.height() * LOGO_SIZE_RATIO
            val cx = cell.centerX()
            val cy = height / 2f
            logoDrawable.setBounds(
                (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(), (cy + size / 2f).toInt()
            )
            logoDrawable.draw(canvas)
            return
        }
        // 投影（皮肤开启时与键盘按键同源：偏移 + 弥散；按下态跳过，与按键沉降一致）
        val shadow = skin.keyShadow
        if (skin.toolbarButtonShadow && shadow != null && !pressed) {
            val shadowRect = RectF(pill)
            shadowRect.offset(dp2px(shadow.dxDp), dp2px(shadow.dyDp))
            canvas.drawRoundRect(shadowRect, radius, radius, pillShadowPaint)
        }
        // 胶囊底色：常态为皮肤按钮底色，按下换按键按下色
        canvas.drawRoundRect(pill, radius, radius, if (pressed) pressedPaint else pillPaint)
        if (skin.toolbarButtonBorderWidthDp > 0f) {
            canvas.drawRoundRect(pill, radius, radius, pillBorderPaint)
        }
        // 文字：按下换主题强调色，形成明确的触达反馈
        val normalColor = textPaint.color
        if (pressed) textPaint.color = pressedTextColor
        canvas.drawText(labelAt(index), cell.centerX(), textBaseline, textPaint)
        textPaint.color = normalColor
    }

    // ===== 触摸 / 无障碍事件 =====

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        // 无障碍触摸浏览（hover）事件交由 helper 路由到虚拟按钮
        return touchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 实体键盘 / 遥控器焦点导航
        return touchHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        touchHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 单击 / 拖动 / fling 统一由 gestureDetector 分发（点击与滚动自动互斥）
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 手指移出按下的按钮范围即取消高亮（不触发点击；滚动重绘由 onScroll 驱动）
                if (pressedIndex >= 0 && buttonIndexAt(event.x, event.y) != pressedIndex) {
                    pressedIndex = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                // 拖动结束后同步无障碍节点边界（fling 结束由 flingRunnable 同步）
                touchHelper.invalidateRoot()
            }
        }
        return true
    }

    // ===== 几何工具（固定 Logo 按钮贴左缘 + 固定收起按钮贴右缘 +
    //       动态按钮在二者之间从右往左排列 + 滚动偏移） =====

    /** 单个按钮的固定单元宽（px，已含缩放因子） */
    private fun cellWidthPx(): Float = dp2px(CELL_WIDTH_DP)

    /** 动态区左缘（px）：固定 Logo 按钮单元的右缘 */
    private fun dynamicLeftEdge(): Float = cellWidthPx()

    /** 动态区右缘（px）：固定收起按钮单元的左缘 */
    private fun dynamicRightEdge(): Float = width - cellWidthPx()

    /** 全部动态按钮的内容总宽（px，不含两个固定按钮） */
    private fun contentWidth(): Float = items.size * cellWidthPx()

    /** 最大滚动偏移：动态内容未超出动态区（两固定按钮之间）时为 0（不可滚动） */
    private fun maxScroll(): Float =
        (contentWidth() - (dynamicRightEdge() - dynamicLeftEdge())).coerceAtLeast(0f)

    /** 中止进行中的 fling 惯性滚动 */
    private fun abortFling() {
        scroller.forceFinished(true)
        removeCallbacks(flingRunnable)
    }

    /**
     * 第 [index] 个按钮的单元格矩形（绘制、触摸命中与无障碍节点边界共用）。
     * [logoIndex] 为固定 Logo 按钮：永远贴左缘；[hideIndex] 为固定收起按钮：
     * 永远贴右缘；二者均不随滚动移动。动态按钮从右往左排列：index 0 贴收起
     * 按钮左缘，后续依次向左；[scrollOffset] 增大时动态按钮整体右移。
     */
    private fun cellRect(index: Int): RectF {
        val cell = cellWidthPx()
        if (index == logoIndex) {
            return RectF(0f, 0f, cell, height.toFloat())
        }
        if (index == hideIndex) {
            return RectF(dynamicRightEdge(), 0f, width.toFloat(), height.toFloat())
        }
        val right = dynamicRightEdge() + scrollOffset - index * cell
        return RectF(right - cell, 0f, right, height.toFloat())
    }

    /** 根据视图内触摸坐标查找按钮索引（已计滚动偏移；左/右固定区分别返回
     *  [logoIndex] / [hideIndex]），命中空白区/越界返回 -1 */
    private fun buttonIndexAt(x: Float, y: Float): Int {
        if (y < 0 || y > height || x < 0 || x >= width) return -1
        // 固定 Logo 按钮区：最左侧一个单元宽，不受滚动影响
        if (x < dynamicLeftEdge()) return logoIndex
        // 固定收起按钮区：最右侧一个单元宽，不受滚动影响
        if (x >= dynamicRightEdge()) return hideIndex
        if (items.isEmpty()) return -1
        val distanceFromRight = dynamicRightEdge() + scrollOffset - x
        if (distanceFromRight <= 0f) return -1
        val index = (distanceFromRight / cellWidthPx()).toInt()
        return if (index < items.size) index else -1
    }

    /** 滚动使第 [index] 个动态按钮完整可见（无障碍 ACTION_SHOW_ON_SCREEN；固定按钮无需滚动） */
    private fun ensureCellVisible(index: Int) {
        if (isFixedIndex(index)) return
        val rect = cellRect(index)
        val delta = when {
            // 藏在左侧固定 Logo 按钮下：内容右移
            rect.left < dynamicLeftEdge() -> dynamicLeftEdge() - rect.left
            // 藏在右侧固定收起按钮下：内容左移
            rect.right > dynamicRightEdge() -> dynamicRightEdge() - rect.right
            else -> 0f
        }
        if (delta != 0f) {
            abortFling()
            scrollOffset = (scrollOffset + delta).coerceIn(0f, maxScroll())
            touchHelper.invalidateRoot()
            invalidate()
        }
    }

    // ===== 按钮属性查询（动态按钮取自 [items]，[hideIndex]/[logoIndex] 为固定按钮） =====

    /** 第 [index] 个按钮的展示文本（Logo 按钮为图标绘制，无文本） */
    private fun labelAt(index: Int): String = when (index) {
        hideIndex -> HIDE_LABEL
        logoIndex -> ""
        else -> items[index].label
    }

    /** 第 [index] 个按钮的无障碍描述 */
    private fun descriptionAt(index: Int): String = when (index) {
        hideIndex -> HIDE_DESCRIPTION
        logoIndex -> LOGO_DESCRIPTION
        else -> items[index].description
    }

    /** 第 [index] 个按钮的 [KeyCode] 功能码 */
    private fun keyCodeAt(index: Int): Int = when (index) {
        hideIndex -> KeyCode.KEYCODE_HIDE_KEYBOARD
        logoIndex -> KeyCode.KEYCODE_TOOL_PANEL
        else -> items[index].keyCode
    }

    // ===== 单位转换工具（已叠加缩放因子，悬浮模式下尺寸统一缩放） =====

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density * scaleFactor

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity * scaleFactor
}
