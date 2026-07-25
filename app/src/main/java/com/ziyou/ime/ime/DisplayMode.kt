package com.ziyou.ime.ime

/**
 * 键盘显示形态
 *
 * 与 [KeyboardType]（QWERTY / NINE_GRID）正交的维度：任意键盘布局都可以
 * 停靠或悬浮两种形态展示。形态切换只改变视图装配方式，不触碰输入核心
 * （InputLogicController / Rime 引擎状态与形态无关）。
 *
 * - [DOCKED]   停靠形态：键盘停靠屏幕底部、横跨全宽（默认，现状行为）
 * - [FLOATING] 悬浮形态：小面板悬浮于应用之上，可拖拽、面板外触摸穿透给下层应用
 *   （游戏场景专用，基于 IME 窗口 + onComputeInsets 触摸区域裁剪，无需悬浮窗权限）
 */
enum class DisplayMode {
    DOCKED,
    FLOATING
}
