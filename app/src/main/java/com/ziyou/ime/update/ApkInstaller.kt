package com.ziyou.ime.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装器
 *
 * Android 8.0+ 安装未知来源应用需用户授予「安装未知应用」权限
 * （REQUEST_INSTALL_PACKAGES 声明 + canRequestPackageInstalls 运行时开关）：
 * 未授权时跳转系统授权页，授权完成后由调用方重试 [install]。
 *
 * APK 经独立 FileProvider（`${packageName}.updateprovider`，仅暴露
 * cache/update_apk/）以 content:// URI 授予系统安装器读取权限，
 * 与图片提交用的 imecontent Provider 隔离，避免扩大敏感目录暴露面。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** FileProvider authority 后缀（与 AndroidManifest 声明一致） */
    const val PROVIDER_SUFFIX = ".updateprovider"

    /**
     * 发起安装。
     * @return true 已唤起安装器；false 缺「安装未知应用」权限，已跳转授权页，
     *         调用方应在用户返回后重试
     */
    fun install(activity: Activity, apk: File): Boolean {
        if (!apk.exists()) {
            Log.e(TAG, "安装包不存在: ${apk.absolutePath}")
            return false
        }

        // Android 8.0+ 需「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Log.i(TAG, "缺少安装未知应用权限，跳转系统授权页")
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "无法打开安装权限设置页: ${e.message}", e)
            }
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(
                activity, activity.packageName + PROVIDER_SUFFIX, apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "唤起安装器失败: ${e.message}", e)
            false
        }
    }
}
