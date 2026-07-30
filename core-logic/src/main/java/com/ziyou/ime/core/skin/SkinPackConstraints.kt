package com.ziyou.ime.core.skin

/**
 * `.zyskin` 皮肤包结构约束（纯字符串/数值逻辑，可独立 JVM 单测）。
 *
 * 皮肤包 = zip(skin.json + preview.png + images/ + fonts/)，**不含任何可执行内容**。
 * 安装流程（app 层 SkinPackLoader）逐条目执行本约束：Zip Slip 防护、
 * 条目数/体积上限、扩展名白名单，任一违规即整包拒绝安装。
 */
object SkinPackConstraints {

    /** 包内皮肤规格文件名（必需）。 */
    const val SKIN_JSON = "skin.json"

    /** 包内预览图文件名（可选，缺失时由预览渲染器现渲）。 */
    const val PREVIEW_FILE = "preview.png"

    /** 包条目数上限。 */
    const val MAX_ENTRIES = 64

    /** 单条目解压后体积上限（字节）。 */
    const val MAX_ENTRY_BYTES: Long = 5L * 1024 * 1024

    /** 全包解压后总体积上限（字节）。 */
    const val MAX_TOTAL_BYTES: Long = 20L * 1024 * 1024

    /** 包体（压缩后）体积上限（字节）。 */
    const val MAX_PACKAGE_BYTES: Long = 20L * 1024 * 1024

    /** 包内条目扩展名白名单（json + 位图 + 字体，无可执行内容）。 */
    val ALLOWED_EXTENSIONS = setOf("json", "png", "jpg", "jpeg", "webp", "ttf", "otf")

    /**
     * 校验包内条目路径是否可接受：安全相对路径（防 Zip Slip）+ 扩展名白名单。
     */
    fun isAllowedEntry(path: String): Boolean =
        SkinSpecValidator.isSafeResourcePath(path, ALLOWED_EXTENSIONS)
}
