package com.ziyou.ime.core.skill

/**
 * 包内相对路径安全校验（Zip Slip 防护）。
 *
 * 适用于两处：
 * 1. `.skill` zip 包解压前逐条目校验（Phase 2 安装流水线）；
 * 2. WebView 资源请求映射到技能目录前的路径校验（防 `../` 逃逸读取任意文件）。
 *
 * 纯字符串逻辑，不触碰文件系统，可独立单测。
 */
object ZipEntryValidator {

    /** `.skill` 包大小上限（字节） */
    const val MAX_PACKAGE_BYTES: Long = 5L * 1024 * 1024

    /** `.skill` 包条目数上限 */
    const val MAX_ENTRIES: Int = 200

    /** 单条目路径长度上限 */
    private const val MAX_PATH_LENGTH = 255

    /**
     * 校验相对路径是否安全（限定在技能根目录内）。
     *
     * 拒绝：空路径、绝对路径（`/` 开头）、反斜杠（Windows 分隔符歧义）、
     * 盘符冒号、`..`/`.`/空 路径段、超长路径、NUL 字符。
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > MAX_PATH_LENGTH) return false
        if (path.startsWith("/")) return false
        if (path.contains('\\') || path.contains(':') || path.contains('\u0000')) return false
        val segments = path.split('/')
        return segments.all { it.isNotEmpty() && it != ".." && it != "." }
    }
}
