package com.ziyou.ime.ime

/**
 * 工具面板条目目录（单一来源，纯逻辑无 Android 依赖）。
 *
 * 候选区按钮栏 Logo 键（[KeyCode.KEYCODE_TOOL_PANEL]）打开的工具面板
 * 展示**全部可用工具**：完整的 [ToolbarItem] 目录（不受用户功能栏
 * 自定义配置影响，面板始终全量展示）。「设置」入口不在网格内，
 * 移至面板标题栏「编辑」按钮左侧（见 [ToolPanelView]）。
 *
 * 面板条目的功能码与功能栏同源，统一由 Service 的 handleSoftKeyPress 路由，
 * 目录本身不含行为逻辑（与 [ToolbarItem] 同一设计纪律）。
 */
object ToolPanelCatalog {

    /** 工具面板单个条目：图标 + 名称（兼无障碍描述）+ 功能码 */
    data class Tool(
        /** 面板图标位展示字符（与功能栏 label 同源；面板已改绘矢量图标 [icon]，
         *  本字段保留作目录数据与兼容用途） */
        val label: String,
        /** 条目名称（图标下方文本，亦作为无障碍朗读描述） */
        val name: String,
        /** 面板图标位绘制图标（[ToolbarIconDrawer] 目录，与功能栏同一视觉规格） */
        val icon: ToolbarIconDrawer.Icon,
        /** [KeyCode] 自定义功能码 */
        val keyCode: Int,
        /** 对应的 [ToolbarItem] 持久化 id（面板编辑模式的增删排序目标） */
        val toolbarId: String? = null
    )

    /** 面板展示的全部工具项（完整功能栏目录，顺序一致） */
    fun allTools(): List<Tool> =
        ToolbarItem.entries.map { Tool(it.label, it.description, it.icon, it.keyCode, it.id) }
}
