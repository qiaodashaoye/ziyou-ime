package com.ziyou.ime.core.skill

/**
 * 技能包元数据（manifest.json 的解析结果）。
 *
 * 纯数据模型，不依赖任何 Android / JSON 库；解析由 app 层完成（org.json），
 * 字段合法性校验由 [SkillManifestValidator] 承担（纯逻辑可单测）。
 */
data class SkillManifest(
    /** manifest 格式版本，当前仅支持 1 */
    val manifestVersion: Int,
    /** 技能唯一 id（反向域名风格，如 com.ziyou.skill.calculator） */
    val id: String,
    /** 展示名称 */
    val name: String,
    /** 技能版本号（形如 1.0.0） */
    val version: String,
    /** 运行所需的最低宿主 API 版本（与 [SkillManifestValidator.HOST_API_VERSION] 协商） */
    val minHostApi: Int,
    /** 作者（可选） */
    val author: String?,
    /** 描述（可选） */
    val description: String?,
    /** 图标字符（emoji / 单字，避免位图处理），可选 */
    val iconText: String?,
    /** 入口 HTML 文件的包内相对路径 */
    val entry: String,
    /** 面板形态 */
    val panelMode: SkillPanelMode,
    /** 声明的权限集合 */
    val permissions: Set<SkillPermission>,
    /** 网络域名白名单（仅声明 NETWORK 权限时允许非空） */
    val networkDomains: List<String>,
    /**
     * 是否需要面板内文本输入（Phase 3 输入路由）。
     * true 时宿主以分栏布局打开该技能（面板在上、键盘在下保持可用），
     * 配合 input.requestFocus 把上屏文本路由进面板输入框。
     */
    val needsInput: Boolean = false
)

/** 技能面板形态：embed（工具嵌入）/ card（信息卡片，点击上屏后收起）。 */
enum class SkillPanelMode(val id: String) {
    EMBED("embed"),
    CARD("card");

    companion object {
        fun fromId(id: String?): SkillPanelMode? = entries.firstOrNull { it.id == id }
    }
}
