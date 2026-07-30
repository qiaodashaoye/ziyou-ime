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
 * 「左侧栏 + 4 行网格」的复合布局与侧栏底部「符号」键对齐），使 Service 变薄。
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

    /** 装载结果：构造出的键盘视图与（九宫格特有的）左侧栏引用。 */
    data class Installed(
        val keyboardView: BaseKeyboardView,
        val pinyinSideBar: PinyinSideBarView?
    )

    /** 根据类型创建键盘视图。新增键盘类型时仅需在此登记。 */
    fun createKeyboardView(type: KeyboardType): BaseKeyboardView = when (type) {
        KeyboardType.QWERTY -> QwertyKeyboardView(context)
        KeyboardType.NINE_GRID -> NineGridKeyboardView(context)
        KeyboardType.SYMBOL -> SymbolKeyboardView(context)
        KeyboardType.NUMBER -> NumberKeyboardView(context)
    }

    /**
     * 安装指定类型的键盘到容器，完成回调绑定、皮肤、缩放与布局组装。
     *
     * 停靠形态下九宫格在键盘左侧挂载 [PinyinSideBarView]（`侧栏 : 网格 ≈ 18 : 82` 权重），
     * 侧栏高度与 4 行网格一致，并在布局完成后把底行几何同步给侧栏，
     * 使侧栏底部的「符号」键与网格底行水平对齐。
     *
     * 悬浮形态（[floating]=true）下不挂侧栏，九宫格底行自带「符」键以保留符号入口；
     * [scale] 为悬浮统一缩放因子。
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
        // 数字键盘：数字/小数点/正负号同样经 Service 统一 commit 出口直接上屏
        if (view is NumberKeyboardView) {
            view.onNumberInput = { value -> callbacks.onSideSymbolInput(value) }
        }
        container.removeAllViews()

        // 悬浮形态的九宫格：无左侧栏，底行自带「符」键保留符号入口，
        // 拼音消歧仍可经候选栏完成（首版简化，窄版侧栏留待二期）
        if (floating && type == KeyboardType.NINE_GRID) {
            (view as NineGridKeyboardView).setFloatingLayout(true)
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(view)
            return Installed(view, null)
        }

        if (type != KeyboardType.NINE_GRID) {
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(view)
            return Installed(view, null)
        }

        // 停靠形态：4 行网格（字母键 + 右侧功能列 + 底行），符号入口位于左侧栏
        val grid = view as NineGridKeyboardView
        grid.setFloatingLayout(false)

        // 左侧拼音侧栏（高度与 4 行网格一致，底部「符号」键对齐网格底行）
        val sideBar = PinyinSideBarView(context).apply {
            applySkin(skin)
            setSideSymbols(SideSymbolRepository.getPinyinSideSymbols(context))
            onPinyinSelect = { pinyin -> callbacks.onPinyinSelect(pinyin) }
            onSymbolInput = { value -> callbacks.onSideSymbolInput(value) }
            onAddSymbol = { callbacks.onAddSymbol() }
            onSymbolKeyboard = { callbacks.onKeyPress(KeyCode.KEYCODE_SYMBOL, 0) }
        }
        // 横向容器：[侧栏][4 行网格]
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(sideBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.8f))
        grid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 8.2f)
        root.addView(grid)
        container.addView(root)

        // 布局完成后把网格底行几何同步给侧栏，使侧栏底部「符号」键与底行对齐
        grid.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sideBar.setGridGeometry(
                    grid.gridTop, grid.gridRowGap, grid.bottomRowTop, grid.gridRowHeight
                )
            }
        })

        return Installed(view, sideBar)
    }
}
