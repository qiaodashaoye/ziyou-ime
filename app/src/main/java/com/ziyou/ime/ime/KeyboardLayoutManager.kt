package com.ziyou.ime.ime

import android.content.Context
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.skin.SkinManager

/**
 * 键盘视图装载器。
 *
 * 从 [ZiYouInputMethodService] 剥离键盘视图的创建与组装逻辑（含九宫格
 * 「侧栏 + 三行网格 + 全宽底栏」的复合布局与底栏按键宽度对齐），使 Service 变薄。
 *
 * 与 Service 的交互通过 [Callbacks] 单向回调完成；[install] 返回构造好的视图引用，
 * 由 Service 持有并管理生命周期。新增键盘类型只需扩展 [createKeyboardView]。
 */
class KeyboardLayoutManager(
    private val context: Context,
    private val callbacks: Callbacks
) {

    /** Service 需实现的回调，键盘视图的交互统一经此上抛。 */
    interface Callbacks {
        fun onKeyPress(keyCode: Int, mask: Int)
        fun onSwitchKeyboard(target: KeyboardType)
        fun onSwitchToQwertyEnglish()
        fun onComposingPreview(preview: String?)
        fun onPinyinSelect(pinyin: String)
        fun onSideSymbolInput(value: String)
        fun onAddSymbol()
    }

    /** 装载结果：构造出的键盘视图与（九宫格特有的）侧栏、底栏引用。 */
    data class Installed(
        val keyboardView: BaseKeyboardView,
        val pinyinSideBar: PinyinSideBarView?,
        val nineGridBottomBar: NineGridBottomBarView?
    )

    /** 根据类型创建键盘视图。新增键盘类型时仅需在此登记。 */
    fun createKeyboardView(type: KeyboardType): BaseKeyboardView = when (type) {
        KeyboardType.QWERTY -> QwertyKeyboardView(context)
        KeyboardType.NINE_GRID -> NineGridKeyboardView(context)
        KeyboardType.SYMBOL -> SymbolKeyboardView(context)
    }

    /**
     * 安装指定类型的键盘到容器，完成回调绑定、皮肤、缩放与布局组装。
     *
     * 停靠形态下九宫格在键盘左侧挂载 [PinyinSideBarView]（`侧栏 : 网格 ≈ 18 : 82` 权重），
     * 下方挂载全宽 [NineGridBottomBarView]，并在布局完成后同步底栏按键宽度与网格一致。
     *
     * 悬浮形态（[floating]=true）下九宫格改用内部完整 4 行布局（含底栏行），
     * 不挂侧栏与独立底栏，保持悬浮面板紧凑；[scale] 为悬浮统一缩放因子。
     */
    fun install(
        container: FrameLayout,
        type: KeyboardType,
        floating: Boolean = false,
        scale: Float = 1f
    ): Installed {
        val skin = SkinManager.getCurrentSkin(context)
        val view = createKeyboardView(type).apply {
            applySkin(skin)
            scaleFactor = scale
            onKeyPress = { keyCode, mask -> callbacks.onKeyPress(keyCode, mask) }
            onSwitchKeyboard = { target -> callbacks.onSwitchKeyboard(target) }
            onSwitchToQwertyEnglish = { callbacks.onSwitchToQwertyEnglish() }
            onComposingPreview = { preview -> callbacks.onComposingPreview(preview) }
        }
        // 符号键盘：符号点击与侧栏符号同源，经 Service 统一 commit 出口直接上屏
        if (view is SymbolKeyboardView) {
            view.onSymbolInput = { value -> callbacks.onSideSymbolInput(value) }
        }
        container.removeAllViews()

        // 悬浮形态的九宫格：内部 4 行完整布局（含底栏行），无侧栏/独立底栏，
        // 拼音消歧仍可经候选栏完成（首版简化，窄版侧栏留待二期）
        if (floating && type == KeyboardType.NINE_GRID) {
            (view as NineGridKeyboardView).setGridRowCount(4)
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(view)
            return Installed(view, null, null)
        }

        if (type != KeyboardType.NINE_GRID) {
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(view)
            return Installed(view, null, null)
        }

        // 九宫格主网格只显示前三行（1-9 数字键 + 右侧功能列）
        val grid = view as NineGridKeyboardView
        grid.setGridRowCount(3)

        // 左侧拼音侧栏（高度与三行网格匹配，底部与数字键行对齐）
        val sideBar = PinyinSideBarView(context).apply {
            applySkin(skin)
            setSideSymbols(SideSymbolRepository.getPinyinSideSymbols(context))
            onPinyinSelect = { pinyin -> callbacks.onPinyinSelect(pinyin) }
            onSymbolInput = { value -> callbacks.onSideSymbolInput(value) }
            onAddSymbol = { callbacks.onAddSymbol() }
        }
        // 横向容器：[侧栏][三行网格]
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        topRow.addView(sideBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.8f))
        grid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 8.2f)
        topRow.addView(grid)

        // 底栏视图：全宽横跨屏幕，延伸至屏幕最左侧边缘
        val bottomBar = NineGridBottomBarView(context).apply {
            applySkin(skin)
            onKeyPress = { keyCode, mask -> callbacks.onKeyPress(keyCode, mask) }
            onSwitchKeyboard = { target -> callbacks.onSwitchKeyboard(target) }
            onSwitchToQwertyEnglish = { callbacks.onSwitchToQwertyEnglish() }
            isChineseMode = view.isChineseMode
        }

        // 纵向容器：[上方：侧栏+三行网格][下方：全宽底栏]
        val verticalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        verticalContainer.addView(topRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        verticalContainer.addView(bottomBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(verticalContainer)

        // 布局完成后同步底栏按键宽度与上方网格保持一致
        grid.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                grid.gridUnitWidth?.let { unitWidth ->
                    bottomBar.forcedUnitWidth = unitWidth
                    bottomBar.requestLayout()
                }
            }
        })

        return Installed(view, sideBar, bottomBar)
    }
}
