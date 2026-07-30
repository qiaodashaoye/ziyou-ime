package com.ziyou.ime.ime

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.MainThread
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.core.clipboard.ClipboardEntry
import com.ziyou.ime.data.ClipboardHistoryRepository

/**
 * 粘贴板历史面板协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离「粘贴板面板生命周期 + 键盘收放布局编排」职责，
 * 与 [AiPanelCoordinator] / [SkillPanelCoordinator] / [DoodlePanelCoordinator]
 * 遵循同一拆分纪律。
 *
 * 与涂鸦面板同款策略：无文字输入需求，打开即收起键盘/候选区，
 * 面板接管二者实测高度之和（IME 窗口总高严格不变，同一高度守恒策略），
 * 且**不接管 commitTarget 输入路由**——点击条目经 [Host.pasteToEditor]
 * 走 commitDirectToEditor 直达宿主输入框，对 Rime 引擎与输入链路零侵入。
 *
 * 与技能面板同一形态约束：悬浮键盘模式下不开放（面板空间受限），Toast 提示。
 *
 * 所有方法均须在主线程调用。
 */
@MainThread
class ClipboardPanelCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    companion object {
        /** 键盘/候选区尚未完成测量时的面板兜底高度（dp） */
        private const val FALLBACK_PANEL_HEIGHT_DP = 300f
    }

    /** 协调器需要 Service 提供的能力：视图容器访问与粘贴出口。 */
    interface Host {
        /** 输入视图内容根容器（面板挂载目标，重建后引用会变化） */
        fun contentLayout(): LinearLayout?

        /** 键盘容器（打开面板时整体收回/关闭时恢复） */
        fun keyboardContainer(): FrameLayout?

        /** 候选区容器（编码区 + 候选词列表，随键盘一并收回/恢复） */
        fun candidatesContainer(): LinearLayout?

        /** 键盘视图（震动反馈载体） */
        fun keyboardView(): BaseKeyboardView?

        /** 当前是否悬浮键盘模式（悬浮下不开放粘贴板面板，与技能面板同一约束） */
        fun isFloatingMode(): Boolean

        /** 将条目文本直接粘贴到宿主输入框（commitDirectToEditor，绕过面板路由） */
        fun pasteToEditor(text: String)

        /** 面板即将打开：清除活跃编码与候选/编码区展示（键盘状态零丢失） */
        fun onPanelWillOpen()
    }

    /** 粘贴板面板（仅打开时非空）。 */
    private var panel: ClipboardPanelView? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 面板开关切换（候选区按钮栏「贴」键入口）。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 打开粘贴板面板：挂载到内容根容器顶部并立即收起键盘/候选区，
     * 面板高度 = 二者实测高度之和（高度守恒，IME 窗口总高不变）。
     */
    fun open() {
        if (panel != null) return
        if (host.isFloatingMode()) {
            Toast.makeText(service, "悬浮键盘暂不支持粘贴板面板", Toast.LENGTH_SHORT).show()
            return
        }
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

        val newPanel = ClipboardPanelView(service, SkinManager.getCurrentSkin(service), panelHost)
        newPanel.submitEntries(ClipboardHistoryRepository.getEntries(service))
        // 索引 0 = 整个输入视图最顶部，固定高度接管键盘+候选区空间
        content.addView(newPanel, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        panel = newPanel
    }

    /** 关闭粘贴板面板：恢复键盘/候选区可见，并从容器移除（幂等）。 */
    fun close() {
        val current = panel ?: return
        panel = null
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 面板宿主能力（ClipboardPanelView.Host 实现）=====

    private val panelHost = object : ClipboardPanelView.Host {
        override fun onRequestClose() = close()

        override fun onPasteEntry(entry: ClipboardEntry) {
            host.pasteToEditor(entry.text)
            // 粘贴后自动关闭，恢复键盘继续输入
            close()
        }

        override fun onDeleteEntry(entry: ClipboardEntry) {
            ClipboardHistoryRepository.removeEntry(service, entry.timestamp)
            panel?.submitEntries(ClipboardHistoryRepository.getEntries(service))
        }

        override fun onClearAll() {
            ClipboardHistoryRepository.clearAll(service)
            panel?.submitEntries(emptyList())
        }

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
