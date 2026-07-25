package com.ziyou.ime.core.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FloatingPanelGeometry 单元测试
 *
 * 覆盖：面板宽度计算、位置钳制、默认位置、拖拽位移钳制、insets 计算。
 * 对应可行性方案的测试计划：「region 计算为纯几何逻辑，下沉 :core-logic 单测」。
 */
class FloatingPanelGeometryTest {

    // ===== panelWidth =====

    @Test
    fun `panelWidth 按比例计算并落在上下限内`() {
        // 2000 * 0.5 = 1000，处于 [600, 1200] 内
        assertEquals(1000, FloatingPanelGeometry.panelWidth(2000, 600, 1200, 0.5f))
    }

    @Test
    fun `panelWidth 低于下限时钳制到下限`() {
        // 800 * 0.5 = 400 < 600
        assertEquals(600, FloatingPanelGeometry.panelWidth(800, 600, 1200, 0.5f))
    }

    @Test
    fun `panelWidth 高于上限时钳制到上限`() {
        // 4000 * 0.5 = 2000 > 1200
        assertEquals(1200, FloatingPanelGeometry.panelWidth(4000, 600, 1200, 0.5f))
    }

    @Test
    fun `panelWidth 不超过容器宽度_小屏兜底`() {
        // 下限 600 > 容器 500，最终以容器宽度为准
        assertEquals(500, FloatingPanelGeometry.panelWidth(500, 600, 1200, 0.5f))
    }

    @Test
    fun `panelWidth 容器宽度非法时返回下限`() {
        assertEquals(600, FloatingPanelGeometry.panelWidth(0, 600, 1200, 0.5f))
        assertEquals(600, FloatingPanelGeometry.panelWidth(-100, 600, 1200, 0.5f))
    }

    // ===== clampPosition =====

    @Test
    fun `clampPosition 容器内位置原样保留`() {
        val p = FloatingPanelGeometry.clampPosition(100, 200, 400, 300, 2000, 1000)
        assertEquals(PanelPoint(100, 200), p)
    }

    @Test
    fun `clampPosition 负坐标钳制到零`() {
        val p = FloatingPanelGeometry.clampPosition(-50, -80, 400, 300, 2000, 1000)
        assertEquals(PanelPoint(0, 0), p)
    }

    @Test
    fun `clampPosition 越过右下边界钳制到最大位置`() {
        val p = FloatingPanelGeometry.clampPosition(5000, 5000, 400, 300, 2000, 1000)
        assertEquals(PanelPoint(2000 - 400, 1000 - 300), p)
    }

    @Test
    fun `clampPosition 面板大于容器时取零`() {
        val p = FloatingPanelGeometry.clampPosition(100, 100, 3000, 2000, 2000, 1000)
        assertEquals(PanelPoint(0, 0), p)
    }

    // ===== defaultPosition =====

    @Test
    fun `defaultPosition 位于右下角内缩margin`() {
        val p = FloatingPanelGeometry.defaultPosition(400, 300, 2000, 1000, 32)
        assertEquals(PanelPoint(2000 - 400 - 32, 1000 - 300 - 32), p)
    }

    @Test
    fun `defaultPosition 小容器下不越界`() {
        val p = FloatingPanelGeometry.defaultPosition(400, 300, 420, 310, 32)
        assertEquals(PanelPoint(0, 0), p)
    }

    // ===== dragPosition =====

    @Test
    fun `dragPosition 跟随手指位移`() {
        val p = FloatingPanelGeometry.dragPosition(
            startX = 100, startY = 200,
            downRawX = 500f, downRawY = 500f,
            moveRawX = 560f, moveRawY = 450f,
            panelWidth = 400, panelHeight = 300,
            containerWidth = 2000, containerHeight = 1000
        )
        assertEquals(PanelPoint(160, 150), p)
    }

    @Test
    fun `dragPosition 拖出边界时逐帧钳制`() {
        val p = FloatingPanelGeometry.dragPosition(
            startX = 1500, startY = 600,
            downRawX = 0f, downRawY = 0f,
            moveRawX = 900f, moveRawY = 900f,
            panelWidth = 400, panelHeight = 300,
            containerWidth = 2000, containerHeight = 1000
        )
        // 1500+900=2400 → 钳到 1600；600+900=1500 → 钳到 700
        assertEquals(PanelPoint(1600, 700), p)
    }

    @Test
    fun `dragPosition 向负方向拖出钳制到零`() {
        val p = FloatingPanelGeometry.dragPosition(
            startX = 10, startY = 20,
            downRawX = 500f, downRawY = 500f,
            moveRawX = 100f, moveRawY = 100f,
            panelWidth = 400, panelHeight = 300,
            containerWidth = 2000, containerHeight = 1000
        )
        assertEquals(PanelPoint(0, 0), p)
    }

    // ===== computeInsets =====

    @Test
    fun `computeInsets 内容inset压到容器底部_触摸矩形等于面板矩形`() {
        val spec = FloatingPanelGeometry.computeInsets(
            containerTopInWindow = 0,
            containerHeight = 1080,
            panelLeftInWindow = 1200,
            panelTopInWindow = 600,
            panelWidth = 500,
            panelHeight = 400
        )
        // 宿主视键盘高度为 0：content inset = 容器底部
        assertEquals(1080, spec.contentTopInset)
        // 触摸区域 = 面板矩形，面板外穿透
        assertEquals(1200, spec.touchableLeft)
        assertEquals(600, spec.touchableTop)
        assertEquals(1700, spec.touchableRight)
        assertEquals(1000, spec.touchableBottom)
    }

    @Test
    fun `computeInsets 容器非窗口顶部时叠加偏移`() {
        val spec = FloatingPanelGeometry.computeInsets(
            containerTopInWindow = 100,
            containerHeight = 900,
            panelLeftInWindow = 0,
            panelTopInWindow = 100,
            panelWidth = 400,
            panelHeight = 300
        )
        assertEquals(1000, spec.contentTopInset)
        assertEquals(0, spec.touchableLeft)
        assertEquals(100, spec.touchableTop)
        assertEquals(400, spec.touchableRight)
        assertEquals(400, spec.touchableBottom)
    }
}
