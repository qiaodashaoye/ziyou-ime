package com.ziyou.ime.update

import android.content.Context
import org.json.JSONObject

/**
 * 应用更新偏好持久化（SharedPreferences）
 *
 * 承载三类状态：
 * - 自动检测频控：记录上次自动检测时间，[UpdateConfig.AUTO_CHECK_INTERVAL_MS] 内不重复检测；
 * - 忽略版本：用户在更新弹窗选择「忽略该版本」后，自动检测不再提示该版本
 *   （手动检查仍会展示，尊重用户主动查询的意图）；
 * - 待更新快照：Application 后台检测到新版本时暂存，等有 Activity 前台时再弹窗，
 *   避免键盘输入服务进程中无 UI 可弹。
 */
object UpdateSettings {

    private const val PREFS_NAME = "ziyou_update_prefs"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check_time"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val KEY_PENDING_UPDATE = "pending_update"
    private const val KEY_DOWNLOADED_VERSION = "downloaded_version"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== 自动检测频控 =====

    /** 距离上次自动检测是否已满足最小间隔 */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_AUTO_CHECK, 0L)
        return System.currentTimeMillis() - last >= UpdateConfig.AUTO_CHECK_INTERVAL_MS
    }

    /** 记录本次自动检测时间（发起检测即记录，失败也计入，避免网络异常时每次启动都重试） */
    fun markAutoChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
    }

    // ===== 忽略版本 =====

    fun getIgnoredVersion(context: Context): String =
        prefs(context).getString(KEY_IGNORED_VERSION, "") ?: ""

    fun setIgnoredVersion(context: Context, versionName: String) {
        prefs(context).edit().putString(KEY_IGNORED_VERSION, versionName).apply()
    }

    // ===== 待更新快照 =====

    fun getPendingUpdate(context: Context): AppUpdateInfo? {
        val json = prefs(context).getString(KEY_PENDING_UPDATE, null) ?: return null
        return try {
            val obj = JSONObject(json)
            AppUpdateInfo(
                versionName = obj.getString("versionName"),
                versionNo = obj.optLong("versionNo", 0L),
                buildVersion = obj.optString("buildVersion", ""),
                updateDescription = obj.optString("updateDescription", ""),
                downloadUrl = obj.getString("downloadUrl"),
                appName = obj.optString("appName", ""),
                fileSizeBytes = obj.optLong("fileSizeBytes", 0L)
            )
        } catch (e: Exception) {
            clearPendingUpdate(context)  // 脏数据直接丢弃
            null
        }
    }

    fun savePendingUpdate(context: Context, info: AppUpdateInfo) {
        val json = JSONObject()
            .put("versionName", info.versionName)
            .put("versionNo", info.versionNo)
            .put("buildVersion", info.buildVersion)
            .put("updateDescription", info.updateDescription)
            .put("downloadUrl", info.downloadUrl)
            .put("appName", info.appName)
            .put("fileSizeBytes", info.fileSizeBytes)
        prefs(context).edit().putString(KEY_PENDING_UPDATE, json.toString()).apply()
    }

    fun clearPendingUpdate(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_UPDATE).apply()
    }

    // ===== 已下载安装包的版本标记（复用安装包时校验版本一致性）=====

    fun getDownloadedVersion(context: Context): String =
        prefs(context).getString(KEY_DOWNLOADED_VERSION, "") ?: ""

    fun setDownloadedVersion(context: Context, versionName: String) {
        prefs(context).edit().putString(KEY_DOWNLOADED_VERSION, versionName).apply()
    }

    fun clearDownloadedVersion(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOADED_VERSION).apply()
    }
}
