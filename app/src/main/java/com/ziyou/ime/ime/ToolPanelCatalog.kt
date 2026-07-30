package com.ziyou.ime.ime

/**
 * 工具面板条目目录（单一来源，纯逻辑无 Android 依赖）。
 *
 * 候选区按钮栏 Logo 键（[KeyCode.KEYCODE_TOOL_PANEL]）打开的工具面板
 * 展示**全部可用工具**：完整的 [ToolbarItem] 目录（不受用户功能栏
 * 自定义配置影响，面板始终全量展示）+ 面板专属追加项（如「设置」——
 * 原功能栏固定设置按钮被 Logo 取代后，设置入口移入本面板）。
 *
 * 面板条目的功能码与功能栏同源，统一由 Service 的 handleSoftKeyPress 路由，
 * 目录本身不含行为逻辑（与 [ToolbarItem] 同一设计纪律）。
 */
object ToolPanelCatalog {

    /** 工具面板单个条目：图标字符 + 名称（兼无障碍描述）+ 功能码 */
    data class Tool(
        /** 面板图标位展示字符（与功能栏 label 同源） */
        val label: String,
        /** 条目名称（图标下方文本，亦作为无障碍朗读描述） */
        val name: String,
        /** [KeyCode] 自定义功能码 */
        val keyCode: Int
    )

    /** 面板专属追加项：打开设置页（不入 [ToolbarItem] 功能栏目录） */
    private val SETTINGS_TOOL = Tool("设", "设置", KeyCode.KEYCODE_OPEN_SETTINGS)

    /** 面板展示的全部工具项（顺序：完整功能栏目录 → 面板专属项） */
    fun allTools(): List<Tool> =
        ToolbarItem.entries.map { Tool(it.label, it.description, it.keyCode) } + SETTINGS_TOOL
}
