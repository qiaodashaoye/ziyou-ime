package com.ziyou.ime.ime

/**
 * 候选区功能栏按钮目录（单一来源）。
 *
 * 每个按钮由稳定的字符串 [id]（持久化标识，勿改）、展示 [label]
 * （设置页定制列表使用；功能栏已改绘矢量图标 [icon]，见 [ToolbarIconDrawer]）、
 * 无障碍描述 [description] 与 [KeyCode] 自定义功能码 [keyCode] 构成。
 * 功能码统一由 Service 的 handleSoftKeyPress 路由，目录本身不含行为逻辑。
 *
 * 新增功能按钮：在此追加枚举项即可被功能栏与设置页定制列表自动识别；
 * 预设模板见 [com.ziyou.ime.data.ToolbarConfigRepository.PRESETS]。
 *
 * 注：收起键盘为功能栏常驻固定按钮（见 [CandidateToolbarView]，
 * 居最右侧），不入本目录、不参与用户自定义；设置入口移至工具面板
 * 标题栏（见 [ToolPanelView]）；中英切换与符号键盘已从功能栏移除
 * （仍由键盘按键提供），切换输入方案与切换主题按钮已下线，
 * 历史配置中的这些 id 由 ToolbarConfigLogic 清洗剔除。
 */
enum class ToolbarItem(
    /** 持久化 id（写入 SharedPreferences，跨版本保持稳定） */
    val id: String,
    /** 功能栏上的展示文本 */
    val label: String,
    /** 无障碍朗读 / 设置页说明文本 */
    val description: String,
    /** 功能栏绘制图标（[ToolbarIconDrawer] 目录） */
    val icon: ToolbarIconDrawer.Icon,
    /** [KeyCode] 自定义功能码 */
    val keyCode: Int
) {
    DOODLE("doodle", "画", "涂鸦画板", ToolbarIconDrawer.Icon.DOODLE, KeyCode.KEYCODE_DOODLE_PANEL),
    SKILL("skill", "技", "技能面板", ToolbarIconDrawer.Icon.SKILL, KeyCode.KEYCODE_SKILL_PANEL),
    AI("ai", "AI", "AI 问答", ToolbarIconDrawer.Icon.AI, KeyCode.KEYCODE_AI_ASSISTANT),
    CLIPBOARD("clipboard", "贴", "粘贴板历史", ToolbarIconDrawer.Icon.CLIPBOARD, KeyCode.KEYCODE_CLIPBOARD_PANEL),
    FLOATING("floating", "浮", "悬浮键盘切换", ToolbarIconDrawer.Icon.FLOATING, KeyCode.KEYCODE_TOGGLE_FLOATING),
    VOICE("voice", "声", "语音输入", ToolbarIconDrawer.Icon.VOICE, KeyCode.KEYCODE_VOICE_PANEL),
    KEYBOARD("keyboard", "键", "键盘切换", ToolbarIconDrawer.Icon.KEYBOARD, KeyCode.KEYCODE_KEYBOARD_PICKER);

    companion object {
        /** 按持久化 id 查找，未知 id 返回 null（配置清洗由 ToolbarConfigLogic 负责） */
        fun fromId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }

        /** 目录内全部合法 id（供配置清洗） */
        val ALL_IDS: List<String> = entries.map { it.id }
    }
}
