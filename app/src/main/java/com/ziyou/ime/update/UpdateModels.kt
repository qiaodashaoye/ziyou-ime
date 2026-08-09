package com.ziyou.ime.update

/**
 * 应用内更新的蒲公英（Pgyer）接入配置
 *
 * API 文档：https://www.pgyer.com/doc/view/api
 * - API Key：蒲公英后台「API 信息」页获取，账号级固定值；
 * - App Key：应用管理页获取，同一应用的多个版本共享。
 *
 * 两项均未配置时更新功能整体关闭：自动检测静默跳过，手动检查提示未配置，
 * 避免空参数请求白白消耗蒲公英 API 调用额度（每小时限频，错误码 1098）。
 */
object UpdateConfig {

    /** 蒲公英 API Key（后台「API 信息」页获取）。为空则更新功能关闭 */
    const val PGYER_API_KEY = "f40ac9f63da6d67e7a923d4c9158a8b5"

    /** 蒲公英 App Key（应用管理页获取）。为空则更新功能关闭 */
    const val PGYER_APP_KEY = "d25b7b6d010fe1f33533dc3c0c4de61d"

    /** 蒲公英 API 2.0 检测更新接口 */
    const val CHECK_URL = "https://www.pgyer.com/apiv2/app/check"

    /** 自动检测最小间隔：24 小时内最多一次（手动检查不受限） */
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 网络超时（毫秒） */
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000

    /** 检查接口响应上限（字节）：版本元数据 JSON 不应超过该值 */
    const val MAX_CHECK_RESPONSE_BYTES = 1L * 1024 * 1024

    /** 单个 APK 下载上限（字节）：防异常响应耗尽磁盘 */
    const val MAX_APK_BYTES = 500L * 1024 * 1024

    /** 下载缓冲区大小 */
    const val DOWNLOAD_BUFFER_SIZE = 8192

    /** APK 下载跟随重定向的最大跳数 */
    const val MAX_REDIRECTS = 5

    /** 更新功能是否已配置（API Key 与 App Key 均非空） */
    val isConfigured: Boolean
        get() = PGYER_API_KEY.isNotBlank() && PGYER_APP_KEY.isNotBlank()
}

/**
 * 蒲公英返回的最新版本信息
 */
data class AppUpdateInfo(
    /** 远端版本名（buildVersion），如 "1.1.0" */
    val versionName: String,
    /** 远端数字版本号（buildVersionNo），对应 versionCode；0 表示远端未提供 */
    val versionNo: Long,
    /** 构建版本号（buildBuildVersion），如 "3" */
    val buildVersion: String,
    /** 更新说明（buildUpdateDescription） */
    val updateDescription: String,
    /** APK 下载地址（downloadURL） */
    val downloadUrl: String,
    /** 应用名称（buildName） */
    val appName: String,
    /** 文件大小（字节，buildFileSize）；0 表示远端未提供 */
    val fileSizeBytes: Long
)

/**
 * 更新检查结果
 */
sealed class UpdateCheckResult {

    /** 发现新版本 */
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()

    /** 已是最新版本 */
    object UpToDate : UpdateCheckResult()

    /** 检查失败（网络异常 / 接口错误，message 可直接展示） */
    data class Failed(val message: String) : UpdateCheckResult()

    /** 更新服务未配置（[UpdateConfig.isConfigured] 为 false） */
    object NotConfigured : UpdateCheckResult()
}
