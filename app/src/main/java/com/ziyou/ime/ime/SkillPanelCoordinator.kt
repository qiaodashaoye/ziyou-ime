package com.ziyou.ime.ime

import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.MainThread
import com.ziyou.ime.config.ThemeManager

/**
 * 技能面板协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离"技能面板生命周期 + 三态布局编排"职责，
 * 使 Service 聚焦于 Android 生命周期与视图装配（与 [InputLogicController] /
 * [KeyboardLayoutManager] 同一拆分纪律）。
 *
 * 三种布局形态（高度守恒：IME 窗口总高在三态间保持不变）：
 * - 键盘叠层（技能列表 / 普通技能）：面板以 FrameLayout 覆盖键盘区，高度锁定为键盘高度；
 * - 提升挂载（needs_input 技能）：面板移至内容根容器顶部（编码区上方），紧凑高度约键盘 60%；
 * - 收缩态（ui.setExpanded(false)）：键盘/编码区/候选区 GONE，面板高度接管三者实测高度之和。
 *
 * 所有方法均须在主线程调用（面板开关与 Bridge 回调均在主线程）。
 */
@MainThread
class SkillPanelCoordinator(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    /** 协调器需要 Service 提供的能力：视图容器访问、上屏出口与输入路由切换。 */
    interface Host {
        /** 输入视图内容根容器（技能面板提升挂载的目标，重建后引用会变化） */
        fun contentLayout(): LinearLayout?

        /** 键盘容器（技能面板键盘叠层挂载的目标） */
        fun keyboardContainer(): FrameLayout?

        /** 候选区容器（编码区 + 候选词列表，收缩态整体隐藏/恢复） */
        fun candidatesContainer(): LinearLayout?

        /** 当前是否悬浮形态（悬浮窄面板下 WebView 不可用，暂不开放技能面板） */
        fun isFloatingMode(): Boolean

        /** 当前编辑器信息（低敏：getContext 仅暴露包名与输入框类型） */
        fun currentEditorInfo(): EditorInfo?

        /** 键盘视图（震动反馈载体） */
        fun keyboardView(): BaseKeyboardView?

        /** 文本上屏（走 InputLogicController 统一出口） */
        fun commitText(text: String)

        /** 输入路由切换：非空时键盘上屏文本改道注入面板（Phase 3） */
        fun setCommitTarget(target: InputLogicController.CommitTarget?)

        /** 面板即将打开：清除活跃编码与候选/编码区展示（键盘状态零丢失） */
        fun onPanelWillOpen()
    }

    /** 技能面板（仅打开时非空；关闭即释放内部 WebView）。 */
    private var panel: SkillPanelContainer? = null

    /** 面板是否已打开。 */
    val isOpen: Boolean get() = panel != null

    /** 面板开关切换（「技」键入口）。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 打开技能面板：初始以键盘叠层展示技能列表（覆盖键盘区域，候选/编码区保持在上方）；
     * 选中 needs_input 技能后经 [setElevated] 提升至编码区上方。
     * 键盘视图不移除——关闭面板即恢复，零状态丢失。
     * 悬浮形态窄面板下 WebView 不可用（方案 §10），暂不开放。
     */
    fun open() {
        if (panel != null) return
        if (host.isFloatingMode()) {
            Toast.makeText(service, "悬浮键盘暂不支持技能面板", Toast.LENGTH_SHORT).show()
            return
        }
        val container = host.keyboardContainer() ?: return
        host.onPanelWillOpen()
        val newPanel = SkillPanelContainer(service, ThemeManager.getCurrentTheme(service), panelHost)
        // 初始挂载：键盘叠层（技能列表阶段覆盖键盘区域）
        container.addView(newPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panel = newPanel
    }

    /** 关闭技能面板：释放 WebView/Bridge、复位上屏路由与键盘可见性，并从容器移除（幂等）。 */
    fun close() {
        val current = panel ?: return
        panel = null
        current.release()
        // release 内部已回调复位，但彼时 panel 已置空导致回调短路，
        // 此处显式兜底：恢复键盘/候选/编码区可见（收缩态遗留）与上屏目标
        host.keyboardContainer()?.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        host.setCommitTarget(null)
        (current.parent as? ViewGroup)?.removeView(current)
    }

    // ===== 面板宿主能力（SkillPanelContainer.Host 实现）=====

    /** 技能面板的宿主能力：上屏走 InputLogicController 统一出口，编辑器信息按需低敏提供。 */
    private val panelHost = object : SkillPanelContainer.Host {
        override fun commitText(text: String) = host.commitText(text)

        override fun onRequestClose() = close()

        override fun editorPackageName(): String? = host.currentEditorInfo()?.packageName

        override fun editorInputType(): String =
            when (host.currentEditorInfo()?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
                InputType.TYPE_CLASS_NUMBER -> "number"
                InputType.TYPE_CLASS_PHONE -> "phone"
                InputType.TYPE_CLASS_DATETIME -> "datetime"
                else -> "text"
            }

        override fun performHaptic() {
            host.keyboardView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onRequestElevatedLayout(active: Boolean) = setElevated(active)

        override fun onRequestImeExpanded(expanded: Boolean) = setImeExpanded(expanded)

        override fun onInputRoutingChanged(active: Boolean) {
            // 上屏目标切换：激活时键盘文本改道注入面板输入框（Phase 3 输入路由）
            host.setCommitTarget(if (active) panel?.skillCommitTarget else null)
        }
    }

    // ===== 三态布局 =====

    /**
     * 技能面板提升高度：基准为键盘实测高度（未布局时回退 250dp）乘以比例。
     */
    private fun panelHeight(ratio: Float): Int {
        val base = host.keyboardContainer()?.height?.takeIf { it > 0 }
            ?: (service.resources.displayMetrics.density * 250).toInt()
        return (base * ratio).toInt()
    }

    /**
     * 技能面板挂载位置切换：
     * - elevated=true（needs_input 技能打开）：面板提升至内容根容器顶部（编码区上方），
     *   紧凑高度（键盘 60%），下方键盘/候选/编码区完整可用供路由打字；
     * - elevated=false（技能列表 / 普通技能）：面板回到键盘叠层（FrameLayout 覆盖，
     *   高度自然锁定为键盘高度），候选/编码区仍在其上方。
     * 两种挂载均不动引擎与键盘状态。
     */
    private fun setElevated(elevated: Boolean) {
        val current = panel ?: return
        val content = host.contentLayout() ?: return
        val container = host.keyboardContainer() ?: return
        // 任何挂载切换前先确保输入法界面完整可见（退出收缩态）
        container.visibility = View.VISIBLE
        host.candidatesContainer()?.visibility = View.VISIBLE
        if (elevated) {
            if (current.parent === content) return
            (current.parent as? ViewGroup)?.removeView(current)
            // 索引 0 = 整个输入视图最顶部（编码区之上）
            content.addView(current, 0, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, panelHeight(0.6f)))
        } else {
            if (current.parent === container) return
            (current.parent as? ViewGroup)?.removeView(current)
            container.addView(current, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * 输入法界面收缩/恢复（needs_input 技能经 ui.setExpanded 触发，expanded 指输入法界面）：
     * - expanded=false：整个输入法界面收缩——键盘、编码区、候选词区一并缩回（GONE），
     *   技能面板高度接管二者实测高度之和，IME 窗口总高严格不变，空间全部让给技能内容；
     * - expanded=true：完整恢复键盘/编码区/候选区与紧凑面板高度
     *   （input.requestFocus 时由面板自动触发，打字需要完整界面）。
     * 仅提升挂载（needs_input）下生效；键盘叠层形态面板本就占满键盘区，无收缩语义。
     * 引擎与键盘状态不受影响（仅 visibility/高度变化）。
     */
    private fun setImeExpanded(expanded: Boolean) {
        val current = panel ?: return
        val content = host.contentLayout() ?: return
        val keyboard = host.keyboardContainer() ?: return
        val candidates = host.candidatesContainer() ?: return
        if (current.parent !== content) return
        val params = current.layoutParams as? LinearLayout.LayoutParams ?: return
        if (!expanded) {
            if (keyboard.visibility == View.GONE) return
            // 先取各区实测高度再隐藏，面板接管全部空间，窗口总高不变
            params.height = panelHeight(0.6f) + keyboard.height + candidates.height
            keyboard.visibility = View.GONE
            candidates.visibility = View.GONE
        } else {
            if (keyboard.visibility == View.VISIBLE) return
            keyboard.visibility = View.VISIBLE
            candidates.visibility = View.VISIBLE
            params.height = panelHeight(0.6f)
        }
        current.layoutParams = params
    }
}
