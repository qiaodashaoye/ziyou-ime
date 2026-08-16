package com.ziyou.ime.ime

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.MainThread
import com.ziyou.ime.ai.AiPanelMode
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.ui.PersonaManagerActivity
import com.ziyou.ime.ui.SettingsActivity

/**
 * AI 面板协调器（问答 / 人设润色双入口）。
 *
 * 从 [ZiYouInputMethodService] 剥离「AI 面板生命周期 + 键盘收放布局编排」职责，
 * 与 [SkillPanelCoordinator] 遵循同一拆分纪律。
 * 两个面板由工具栏两个独立按钮分别触发（[openAsk] / [openPolish]），
 * 模式在面板构造时固定，面板内不提供模式切换。
 *
 * 面板始终挂载在内容根容器顶部（编码区上方），两种布局形态：
 * - 提问态：面板紧凑高度（标题栏 + 输入行），下方键盘/候选区完整可用，
 *   键盘上屏文本经 commitTarget 路由注入面板输入框；
 * - 答案态（点击搜索后）：键盘/候选区收回（GONE），面板高度接管三者实测高度之和，
 *   IME 窗口总高不变（与技能面板收缩态同一高度守恒策略），答案区独立滚动。
 *
 * 所有方法均须在主线程调用。
 */
@MainThread
class AiPanelCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    /** 协调器需要 Service 提供的能力：视图容器访问与输入路由切换。 */
    interface Host : BasePanelHost {
        /** 输入路由切换：非空时键盘上屏文本改道注入面板输入框 */
        fun setCommitTarget(target: InputLogicController.CommitTarget?)

        /** 将 AI 答案上屏到当前输入框（绕过面板输入路由，直达宿主编辑器） */
        fun commitAnswerToEditor(text: String)

        /** 将 AI 答案渲染为图片卡片后提交：按最新图片能力路由到
         *  commitContent 直发输入框或保存到系统相册 */
        fun commitAnswerImageToEditor(content: CharSequence)

        /** 当前编辑器是否可直接接收图片（答案操作按钮呈现「发图」或「存图」） */
        fun editorAcceptsImage(): Boolean
    }

    /** AI 面板（仅打开时非空）。 */
    private var panel: AiPanelView? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 打开 AI 问答面板（工具栏「AI」键入口）。 */
    fun openAsk() = open(AiPanelMode.ASK)

    /** 打开人设润色面板（工具栏「润色」键入口）。 */
    fun openPolish() = open(AiPanelMode.POLISH)

    /**
     * 打开指定模式的 AI 面板：挂载到内容根容器顶部（编码区上方），
     * 键盘保持可见供输入，上屏路由切到面板输入框。
     * 已打开时先关旧面板再开新面板（支持两入口直接互切）。
     */
    private fun open(mode: AiPanelMode) {
        if (panel != null) {
            if (panel?.mode == mode) return  // 同模式重复点击：保持现状
            close()
        }
        val content = host.contentLayout() ?: return
        host.onPanelWillOpen()
        val newPanel = AiPanelView(service, SkinManager.getCurrentSkin(service), panelHost, mode)
        // 索引 0 = 整个输入视图最顶部（编码区之上），提问态紧凑高度
        content.addView(newPanel, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel = newPanel
        host.setCommitTarget(newPanel.aiCommitTarget)
    }

    /** 关闭 AI 面板：取消进行中请求、复位上屏路由与键盘可见性，并从容器移除（幂等）。 */
    fun close() {
        val current = panel ?: return
        panel = null
        current.release()
        // 恢复键盘/候选区可见（答案态遗留）与上屏目标
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        host.setCommitTarget(null)
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 面板宿主能力（AiPanelView.Host 实现）=====

    private val panelHost = object : AiPanelView.Host {
        override fun onRequestClose() = close()

        override fun onRequestKeyboardCollapsed(collapsed: Boolean) =
            setKeyboardCollapsed(collapsed)

        override fun onRequestOpenSettings() {
            val intent = Intent(service, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
        }

        override fun onRequestOpenPersonaManager() {
            val intent = Intent(service, PersonaManagerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
        }

        override fun onCommitAnswer(text: String) = host.commitAnswerToEditor(text)

        override fun onSendAnswerAsImage(content: CharSequence) =
            host.commitAnswerImageToEditor(content)

        override fun editorAcceptsImage(): Boolean = host.editorAcceptsImage()

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // ===== 键盘收放布局 =====

    /**
     * 键盘收放（答案态 ↔ 提问态），高度守恒：
     * - collapsed=true：先取键盘/候选区实测高度再隐藏，面板高度接管
     *   「面板当前高 + 键盘高 + 候选区高」，IME 窗口总高严格不变；
     * - collapsed=false：恢复键盘/候选区可见，面板回到紧凑 WRAP_CONTENT。
     * 引擎与键盘状态不受影响（仅 visibility / 高度变化）。
     */
    private fun setKeyboardCollapsed(collapsed: Boolean) {
        val current = panel ?: return
        val keyboard = host.keyboardContainer() ?: return
        val candidates = host.candidatesContainer() ?: return
        val params = current.layoutParams as? LinearLayout.LayoutParams ?: return
        if (collapsed) {
            if (keyboard.visibility == View.GONE) return
            params.height = current.height + keyboard.height + candidates.height
            keyboard.visibility = View.GONE
            candidates.visibility = View.GONE
        } else {
            if (keyboard.visibility == View.VISIBLE) return
            keyboard.visibility = View.VISIBLE
            candidates.visibility = View.VISIBLE
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        current.layoutParams = params
        current.applyAnswerMode(collapsed)
    }
}
