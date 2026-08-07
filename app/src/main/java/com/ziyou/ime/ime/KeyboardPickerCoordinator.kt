package com.ziyou.ime.ime

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.MainThread
import com.ziyou.ime.skin.SkinManager

/**
 * 键盘选择面板协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离「键盘选择面板生命周期 + 键盘收放布局编排」
 * 职责，与 [ToolPanelCoordinator] / [ClipboardPanelCoordinator] 等遵循
 * 同一拆分纪律。
 *
 * 与工具面板同款策略：无文字输入需求，打开即收起键盘/候选区，
 * 面板接管二者实测高度之和（IME 窗口总高严格不变，同一高度守恒策略），
 * 不接管输入路由——选中布局经 [Host.switchKeyboard] 回到 Service 的
 * switchKeyboard 统一切换路径（与键盘内切换键同源，先关面板再切换，
 * 关面板恢复键盘可见后由 switchKeyboard 重建键盘视图）。
 *
 * 切换不触碰编辑器文本与光标：面板打开前经 [Host.onPanelWillOpen] 清除
 * 活跃编码（与其他面板一致），switchKeyboard 仅重建键盘视图并同步引擎
 * 方案/模式，不产生任何 commit/删除操作。
 *
 * 悬浮键盘模式下同样开放：面板为纯原生视图，高度取键盘/候选区实测值，
 * 悬浮缩放形态下自然等比适配。
 *
 * 所有方法均须在主线程调用。
 */
@MainThread
class KeyboardPickerCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    companion object {
        /** 键盘/候选区尚未完成测量时的面板兜底高度（dp） */
        private const val FALLBACK_PANEL_HEIGHT_DP = 300f
    }

    /** 协调器需要 Service 提供的能力：视图容器访问与布局切换出口。 */
    interface Host : BasePanelHost {
        /** 当前键盘布局类型（面板高亮标记「当前」项） */
        fun currentKeyboardType(): KeyboardType

        /** 切换到目标布局（Service.switchKeyboard 统一路径，幂等） */
        fun switchKeyboard(type: KeyboardType)
    }

    /** 键盘选择面板（仅打开时非空）。 */
    private var panel: KeyboardPickerPanelView? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 面板开关切换（功能栏「键盘切换」按钮入口）。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 打开键盘选择面板：挂载到内容根容器顶部并立即收起键盘/候选区，
     * 面板高度 = 二者实测高度之和（高度守恒，IME 窗口总高不变）。
     */
    fun open() {
        if (panel != null) return
        val content = host.contentLayout() ?: return
        val keyboard = host.keyboardContainer() ?: return
        val candidates = host.candidatesContainer() ?: return
        host.onPanelWillOpen()

        // 先取实测高度再隐藏；未测量（极端时序）时回退固定高度
        val measured = keyboard.height + candidates.height
        val panelHeight = if (measured > 0) measured
            else (FALLBACK_PANEL_HEIGHT_DP * service.resources.displayMetrics.density).toInt()
        keyboard.visibility = View.GONE
        candidates.visibility = View.GONE

        val newPanel = KeyboardPickerPanelView(
            service, SkinManager.getCurrentSkin(service), host.currentKeyboardType(), panelHost
        )
        // 索引 0 = 整个输入视图最顶部，固定高度接管键盘+候选区空间
        content.addView(newPanel, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        panel = newPanel
    }

    /** 关闭键盘选择面板：恢复键盘/候选区可见，并从容器移除（幂等）。 */
    fun close() {
        val current = panel ?: return
        panel = null
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 面板宿主能力（KeyboardPickerPanelView.Host 实现）=====

    private val panelHost = object : KeyboardPickerPanelView.Host {
        override fun onRequestClose() = close()

        override fun onKeyboardSelected(type: KeyboardType) {
            // 先关面板恢复键盘/候选区，再切换布局：switchKeyboard 对相同
            // 类型幂等短路（选当前项 = 仅关面板），目标不同则重建键盘视图
            // 并经 scheduleEngineSync 对齐方案与中英模式
            close()
            host.switchKeyboard(type)
        }

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
