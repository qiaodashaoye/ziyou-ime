package com.ziyou.ime.ime

import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.annotation.MainThread
import com.ziyou.ime.config.DisplayModeManager
import com.ziyou.ime.config.KeyboardTheme
import com.ziyou.ime.core.floating.FloatingPanelGeometry

/**
 * 显示形态控制器（停靠 / 悬浮）。
 *
 * 从 [ZiYouInputMethodService] 剥离"形态解析、切换与悬浮窗口 insets"职责，
 * 使 Service 聚焦于 Android 生命周期与视图装配。
 *
 * 形态优先级：手动覆盖（本次服务生命周期内） > 悬浮总开关 > 横屏自动悬浮。
 * 形态切换仅重建视图层，Rime 编码/方案/ascii_mode 等引擎状态不受影响。
 */
@MainThread
class DisplayModeController(
    private val service: ZiYouInputMethodService,
    private val host: Host
) {

    /** 控制器需要 Service 提供的能力：切换前清理与切换后重建/重同步。 */
    interface Host {
        /** 形态切换前：丢弃未提交的多击预览等临时输入状态 */
        fun beforeModeSwitch()

        /** 形态已切换：重建输入视图并重同步引擎状态到新视图 */
        fun onModeSwitched(mode: DisplayMode)
    }

    /** 当前显示形态（停靠 / 悬浮），与键盘布局正交。 */
    var currentMode: DisplayMode = DisplayMode.DOCKED
        private set

    /**
     * 本次服务生命周期内的手动形态覆盖。
     * 用户经「浮」键/停靠按钮手动切换后优先于「横屏自动悬浮」开关，
     * 避免手动停靠后下个输入会话又被自动切回悬浮。
     */
    private var manualModeOverride: DisplayMode? = null

    /** 悬浮形态的根容器（仅 FLOATING 下非空），供 computeInsets 裁剪触摸区域。 */
    private var floatingContainer: FloatingPanelContainer? = null

    /**
     * 解析当前应生效的显示形态：
     * 手动覆盖（本次服务生命周期内） > 悬浮总开关 > 横屏自动悬浮。
     */
    fun resolve(): DisplayMode {
        manualModeOverride?.let { return it }
        val landscape =
            service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val floating = DisplayModeManager.isFloatingEnabled(service) ||
            (landscape && DisplayModeManager.isAutoFloatInLandscape(service))
        return if (floating) DisplayMode.FLOATING else DisplayMode.DOCKED
    }

    /** onCreateInputView 入口：解析并生效当前形态。 */
    fun refresh(): DisplayMode {
        currentMode = resolve()
        return currentMode
    }

    /**
     * onStartInputView 入口：重新解析（横屏自动悬浮 / 设置页开关变更），
     * 形态变化时更新并返回新形态（调用方应重建输入视图），未变化返回 null。
     */
    fun refreshIfChanged(): DisplayMode? {
        val resolved = resolve()
        if (resolved == currentMode) return null
        currentMode = resolved
        return resolved
    }

    /** 悬浮 ↔ 停靠 互切（「浮」键入口）。 */
    fun toggle() {
        switchTo(
            if (currentMode == DisplayMode.FLOATING) DisplayMode.DOCKED else DisplayMode.FLOATING
        )
    }

    /**
     * 切换显示形态：持久化开关并回调 Service 重建输入视图。
     * 仅重建视图层，引擎状态不受影响，重建后由 Service 把状态回写到新视图。
     */
    fun switchTo(target: DisplayMode) {
        if (target == currentMode) return
        host.beforeModeSwitch()
        manualModeOverride = target
        DisplayModeManager.setFloatingEnabled(service, target == DisplayMode.FLOATING)
        currentMode = target
        host.onModeSwitched(target)
    }

    /**
     * 悬浮形态：内容包裹进悬浮面板容器（拖拽/位置持久化/停靠按钮）；
     * 停靠形态：原样返回内容根并清空悬浮容器引用。
     * 由 Service 的 buildInputView 末尾调用。
     */
    fun wrapContent(root: View, mode: DisplayMode, theme: KeyboardTheme): View {
        return if (mode == DisplayMode.FLOATING) {
            FloatingPanelContainer(service, root, theme).also { container ->
                container.onRequestDock = { switchTo(DisplayMode.DOCKED) }
                floatingContainer = container
            }
        } else {
            floatingContainer = null
            root
        }
    }

    /**
     * 悬浮形态的窗口 insets：内容 inset 压到容器底部（宿主应用视键盘高度为 0，
     * 游戏画面不被顶起），触摸区域裁剪为悬浮面板矩形，面板外触摸穿透给下层应用。
     * 拖动中的 translation 位移会触发绘制遍历，系统随之重新回调本方法，
     * 触摸区域与面板位置实时同步。几何计算委托 [FloatingPanelGeometry]（纯逻辑可单测）。
     * 由 Service 的 onComputeInsets 委托调用（须先执行 super 默认计算）。
     */
    fun computeInsets(outInsets: InputMethodService.Insets) {
        if (currentMode != DisplayMode.FLOATING) return
        val container = floatingContainer ?: return
        val panelRect = Rect()
        // 面板尚未完成布局时回退默认 insets，避免空触摸区域吞掉所有触摸
        if (!container.getPanelRectInWindow(panelRect)) return
        val containerLoc = IntArray(2)
        container.getLocationInWindow(containerLoc)
        val spec = FloatingPanelGeometry.computeInsets(
            containerTopInWindow = containerLoc[1],
            containerHeight = container.height,
            panelLeftInWindow = panelRect.left,
            panelTopInWindow = panelRect.top,
            panelWidth = panelRect.width(),
            panelHeight = panelRect.height()
        )
        outInsets.contentTopInsets = spec.contentTopInset
        outInsets.visibleTopInsets = spec.contentTopInset
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            spec.touchableLeft, spec.touchableTop, spec.touchableRight, spec.touchableBottom
        )
    }

    /** 服务销毁：释放悬浮容器引用。 */
    fun release() {
        floatingContainer = null
    }
}
