package com.ziyou.ime.ime

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.MainThread
import com.ziyou.ime.skin.SkinManager

/**
 * 涂鸦画板面板协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离「涂鸦面板生命周期 + 键盘收放布局编排」职责，
 * 与 [AiPanelCoordinator] / [SkillPanelCoordinator] 遵循同一拆分纪律。
 *
 * 与 AI 面板的差异：涂鸦无文字输入需求，打开即收起键盘/候选区，
 * 画布接管二者实测高度之和（IME 窗口总高严格不变，同一高度守恒策略），
 * 且**不接管 commitTarget 输入路由**——对 Rime 引擎与输入链路零侵入。
 *
 * 所有方法均须在主线程调用。
 */
@MainThread
class DoodlePanelCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    companion object {
        /** 键盘/候选区尚未完成测量时的面板兜底高度（dp） */
        private const val FALLBACK_PANEL_HEIGHT_DP = 300f
    }

    /** 协调器需要 Service 提供的能力：视图容器访问与图片发送出口。 */
    interface Host : BasePanelHost {
        /** 将涂鸦快照导出为 PNG 并经 commitContent 发送到当前输入框
         *  （快照所有权移交宿主，导出完成后由宿主 recycle） */
        fun sendDoodleImage(snapshot: Bitmap)

        /** 将涂鸦快照导出为 PNG 并保存到系统相册（编辑器不收图片时的兜底出口，
         *  快照所有权移交宿主，导出完成后由宿主 recycle） */
        fun saveDoodleImage(snapshot: Bitmap)

        /** 当前编辑器是否可直接接收图片（决定面板按钮为「发送」或「保存」） */
        fun imageSupportsSend(): Boolean
    }

    /** 涂鸦面板（仅打开时非空）。 */
    private var panel: DoodlePanelView? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 面板开关切换（候选区按钮栏「画」键入口）。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 打开涂鸦面板：挂载到内容根容器顶部并立即收起键盘/候选区，
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

        val newPanel = DoodlePanelView(service, SkinManager.getCurrentSkin(service), panelHost)
        // 按当前编辑器图片能力初始化按钮态（「发送」/「保存」）
        newPanel.setImageSupport(host.imageSupportsSend())
        // 索引 0 = 整个输入视图最顶部，固定高度接管键盘+候选区空间
        content.addView(newPanel, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        panel = newPanel
    }

    /**
     * 编辑器切换（onStartInputView）时重判图片能力，刷新面板「发送/保存」按钮。
     * 面板未打开时为空操作（应用间切换时面板已在 onFinishInputView 关闭，
     * 本方法主要覆盖同应用内切换输入框的场景）。
     */
    fun refreshImageSupport() {
        panel?.setImageSupport(host.imageSupportsSend())
    }

    /** 关闭涂鸦面板：回收画布资源、恢复键盘/候选区可见，并从容器移除（幂等）。 */
    fun close() {
        val current = panel ?: return
        panel = null
        current.release()
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 面板宿主能力（DoodlePanelView.Host 实现）=====

    private val panelHost = object : DoodlePanelView.Host {
        override fun onRequestClose() = close()

        override fun onSendDoodle(snapshot: Bitmap) = host.sendDoodleImage(snapshot)

        override fun onSaveDoodle(snapshot: Bitmap) = host.saveDoodleImage(snapshot)

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
