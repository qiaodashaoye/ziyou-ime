package com.ziyou.ime.core.skill

/**
 * 技能 manifest 字段合法性校验（纯逻辑）。
 *
 * app 层解析 JSON 得到 [SkillManifest] 后必须经本校验器检查；
 * 返回错误列表（空列表 = 合法），便于安装/加载失败时向用户展示具体原因。
 */
object SkillManifestValidator {

    /** 宿主 Bridge API 版本，随 API 演进递增；manifest.min_host_api 高于此值则拒绝加载。
     *  v1：sendText/getContext/getLocale/haptic/ui/storage
     *  v2：+fetch 代理、clipboard、input 路由（needs_input 分栏布局）、ui.setExpanded
     *  v3：+image 图片输出（image.send 富媒体发送 / image.saveToGallery 存相册）
     *  v4：+ui.setPanelHeight 面板高度自定义（needs_input 提升挂载） */
    const val HOST_API_VERSION = 4

    /** 支持的 manifest 格式版本 */
    const val SUPPORTED_MANIFEST_VERSION = 1

    /** 技能 id：反向域名风格，至少两段，小写字母开头 */
    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    /** 版本号：数字点分（1 / 1.0 / 1.0.0） */
    private val VERSION_PATTERN = Regex("^\\d+(\\.\\d+)*$")

    /** 域名：小写字母数字连字符点分，不含协议/路径/通配符 */
    private val DOMAIN_PATTERN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    private const val MAX_ID_LENGTH = 100
    private const val MAX_NAME_LENGTH = 30
    private const val MAX_DOMAINS = 10

    /**
     * 校验 manifest 全部字段，返回错误描述列表（空 = 合法）。
     */
    fun validate(manifest: SkillManifest): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.manifestVersion != SUPPORTED_MANIFEST_VERSION) {
            errors += "不支持的 manifest_version: ${manifest.manifestVersion}（当前仅支持 $SUPPORTED_MANIFEST_VERSION）"
        }
        if (manifest.id.length > MAX_ID_LENGTH || !ID_PATTERN.matches(manifest.id)) {
            errors += "非法技能 id: ${manifest.id}（需为反向域名风格，如 com.author.skillname）"
        }
        if (manifest.name.isBlank() || manifest.name.length > MAX_NAME_LENGTH) {
            errors += "技能名称为空或超长（上限 $MAX_NAME_LENGTH 字符）"
        }
        if (!VERSION_PATTERN.matches(manifest.version)) {
            errors += "非法版本号: ${manifest.version}（需为数字点分，如 1.0.0）"
        }
        if (manifest.minHostApi < 1) {
            errors += "非法 min_host_api: ${manifest.minHostApi}"
        } else if (manifest.minHostApi > HOST_API_VERSION) {
            errors += "该技能需要更高的宿主 API 版本（需要 ${manifest.minHostApi}，当前 $HOST_API_VERSION），请升级输入法"
        }
        if (!ZipEntryValidator.isSafeRelativePath(manifest.entry)) {
            errors += "非法入口路径: ${manifest.entry}"
        } else if (!manifest.entry.endsWith(".html")) {
            errors += "入口文件必须是 .html: ${manifest.entry}"
        }
        if (manifest.networkDomains.isNotEmpty() &&
            SkillPermission.NETWORK !in manifest.permissions
        ) {
            errors += "声明了 network_domains 但未申请 network 权限"
        }
        if (manifest.networkDomains.size > MAX_DOMAINS) {
            errors += "网络域名白名单超限（上限 $MAX_DOMAINS 个）"
        }
        manifest.networkDomains.forEach { domain ->
            if (!DOMAIN_PATTERN.matches(domain)) {
                errors += "非法域名: $domain"
            }
        }
        return errors
    }
}
