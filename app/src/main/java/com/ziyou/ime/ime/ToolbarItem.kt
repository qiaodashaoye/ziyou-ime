package com.ziyou.ime.ime

/**
 * 候选区功能栏按钮目录（单一来源）。
 *
 * 每个按钮由稳定的字符串 [id]（持久化标识，勿改）、展示 [label]、
 * 无障碍描述 [description] 与 [KeyCode] 自定义功能码 [keyCode] 构成。
 * 功能码统一由 Service 的 handleSoftKeyPress 路由，目录本身不含行为逻辑。
 *
 * 新增功能按钮：在此追加枚举项即可被功能栏与设置页定制列表自动识别；
 * 预设模板见 [com.ziyou.ime.data.ToolbarConfigRepository.PRESETS]。
 *
 * 注：设置与收起键盘为功能栏常驻固定按钮（见 [CandidateToolbarView]，
 * 分居最左/最右侧），不入本目录、不参与用户自定义；中英切换与符号键盘
 * 已从功能栏移除（仍由键盘按键提供），历史配置中的这些 id 由
 * ToolbarConfigLogic 清洗剔除。
 */
enum class ToolbarItem(
    /** 持久化 id（写入 SharedPreferences，跨版本保持稳定） */
    val id: String,
    /** 功能栏上的展示文本 */
    val label: String,
    /** 无障碍朗读 / 设置页说明文本 */
    val description: String,
    /** [KeyCode] 自定义功能码 */
    val keyCode: Int
) {
    THEME("theme", "肤", "切换主题", KeyCode.KEYCODE_SWITCH_THEME),
    DOODLE("doodle", "画", "涂鸦画板", KeyCode.KEYCODE_DOODLE_PANEL),
    SKILL("skill", "技", "技能面板", KeyCode.KEYCODE_SKILL_PANEL),
    AI("ai", "AI", "AI 问答", KeyCode.KEYCODE_AI_ASSISTANT),
    CLIPBOARD("clipboard", "贴", "粘贴板历史", KeyCode.KEYCODE_CLIPBOARD_PANEL),
    FLOATING("floating", "浮", "悬浮键盘切换", KeyCode.KEYCODE_TOGGLE_FLOATING);

    companion object {
        /** 按持久化 id 查找，未知 id 返回 null（配置清洗由 ToolbarConfigLogic 负责） */
        fun fromId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }

        /** 目录内全部合法 id（供配置清洗） */
        val ALL_IDS: List<String> = entries.map { it.id }
    }
}
