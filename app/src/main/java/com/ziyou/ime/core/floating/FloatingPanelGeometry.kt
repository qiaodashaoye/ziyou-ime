package com.ziyou.ime.core.floating

/** 悬浮面板位置（相对容器左上角，px） */
data class PanelPoint(val x: Int, val y: Int)

/**
 * 悬浮模式下 IME 窗口 insets 的计算结果（窗口坐标系，px）。
 *
 * - [contentTopInset]：内容/可见 inset 推到容器底部，宿主应用视键盘高度为 0，不被顶起
 * - 触摸矩形 [touchableLeft]..[touchableBottom]：仅面板区域可触，面板外穿透给下层应用
 */
data class FloatingInsetsSpec(
    val contentTopInset: Int,
    val touchableLeft: Int,
    val touchableTop: Int,
    val touchableRight: Int,
    val touchableBottom: Int
)

/**
 * 悬浮面板几何计算（纯函数，无 Android 依赖）。
 *
 * 供 IME 层的悬浮容器与 onComputeInsets 使用：
 * - 面板宽度计算（按容器宽度比例 + 上下限钳制）
 * - 拖拽 / 初始位置的边界钳制（保证面板完整落在容器内）
 * - 触摸区域 insets 计算（TOUCHABLE_INSETS_REGION 的矩形来源）
 *
 * 单位均为 px，坐标系原点为容器左上角（insets 计算为窗口坐标系）。
 */
object FloatingPanelGeometry {

    /**
     * 计算悬浮面板宽度：容器宽度 × [ratio]，钳制在 [minWidth]..[maxWidth]，
     * 且不超过容器本身宽度（小屏兜底）。
     */
    fun panelWidth(containerWidth: Int, minWidth: Int, maxWidth: Int, ratio: Float): Int {
        if (containerWidth <= 0) return minWidth
        val desired = (containerWidth * ratio).toInt()
        val lower = minOf(minWidth, maxWidth)
        val upper = maxOf(minWidth, maxWidth)
        return desired.coerceIn(lower, upper).coerceAtMost(containerWidth)
    }

    /**
     * 将面板位置钳制到容器内：x ∈ [0, containerW-panelW]，y ∈ [0, containerH-panelH]。
     * 面板大于容器时对应轴取 0（贴容器左/上边缘）。
     */
    fun clampPosition(
        x: Int,
        y: Int,
        panelWidth: Int,
        panelHeight: Int,
        containerWidth: Int,
        containerHeight: Int
    ): PanelPoint {
        val maxX = (containerWidth - panelWidth).coerceAtLeast(0)
        val maxY = (containerHeight - panelHeight).coerceAtLeast(0)
        return PanelPoint(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /**
     * 默认位置：容器右下角内缩 [margin]（右手打字 + 左手摇杆的横屏游戏惯用位），
     * 结果再经 [clampPosition] 保证落在容器内。
     */
    fun defaultPosition(
        panelWidth: Int,
        panelHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        margin: Int
    ): PanelPoint = clampPosition(
        containerWidth - panelWidth - margin,
        containerHeight - panelHeight - margin,
        panelWidth, panelHeight, containerWidth, containerHeight
    )

    /**
     * 拖拽中的面板位置：起始位置 + 手指位移，逐帧钳制到容器内。
     *
     * @param startX/startY 按下时面板位置
     * @param downRawX/downRawY 按下时手指屏幕坐标
     * @param moveRawX/moveRawY 当前手指屏幕坐标
     */
    fun dragPosition(
        startX: Int,
        startY: Int,
        downRawX: Float,
        downRawY: Float,
        moveRawX: Float,
        moveRawY: Float,
        panelWidth: Int,
        panelHeight: Int,
        containerWidth: Int,
        containerHeight: Int
    ): PanelPoint = clampPosition(
        startX + (moveRawX - downRawX).toInt(),
        startY + (moveRawY - downRawY).toInt(),
        panelWidth, panelHeight, containerWidth, containerHeight
    )

    /**
     * 计算悬浮模式的 IME insets（窗口坐标系）：
     * 内容 inset 压到容器底部（宿主不被顶起），触摸矩形即面板在窗口中的矩形。
     */
    fun computeInsets(
        containerTopInWindow: Int,
        containerHeight: Int,
        panelLeftInWindow: Int,
        panelTopInWindow: Int,
        panelWidth: Int,
        panelHeight: Int
    ): FloatingInsetsSpec = FloatingInsetsSpec(
        contentTopInset = containerTopInWindow + containerHeight,
        touchableLeft = panelLeftInWindow,
        touchableTop = panelTopInWindow,
        touchableRight = panelLeftInWindow + panelWidth,
        touchableBottom = panelTopInWindow + panelHeight
    )
}
