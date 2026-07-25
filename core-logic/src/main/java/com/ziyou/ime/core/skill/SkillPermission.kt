package com.ziyou.ime.core.skill

/**
 * 技能可申请的权限枚举（宿主预定义，manifest 声明、安装/加载时校验）。
 *
 * 首期收敛为 4 项；[SkillPermission.NETWORK] 的实际 fetch 代理在 Phase 2 交付，
 * 但权限模型先行冻结，避免 manifest 格式反复变更。
 */
enum class SkillPermission(val id: String) {
    /** 经宿主代理的网络请求（配合 networkDomains 白名单） */
    NETWORK("network"),
    /** 读剪贴板 */
    CLIPBOARD_READ("clipboard_read"),
    /** 写剪贴板 */
    CLIPBOARD_WRITE("clipboard_write"),
    /** 轻量 KV 持久化（每技能独立空间，限额见运行时） */
    STORAGE("storage");

    companion object {
        /** 按 manifest 中的权限 id 解析；未知 id 返回 null（由校验器报错拒绝）。 */
        fun fromId(id: String?): SkillPermission? = entries.firstOrNull { it.id == id }
    }
}
