package com.ziyou.ime.config

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 资源部署器
 * 负责将assets/rime/目录下的Rime配置文件部署到应用内部存储
 */
object AssetDeployer {
    private const val TAG = "AssetDeployer"

    private const val PREF_NAME = "ziyou_deploy"
    private const val KEY_DEPLOYED_VERSION = "deployed_version"

    private const val ASSETS_RIME_DIR = "rime"

    fun deployIfNeeded(context: Context): Boolean {
        val currentVersion = getAppVersionCode(context)
        val deployedVersion = getDeployedVersion(context)

        if (currentVersion == deployedVersion) {
            Log.i(TAG, "资源已是最新版本($currentVersion)，跳过部署")
            return false
        }

        Log.i(TAG, "检测到版本变化: 已部署=$deployedVersion, 当前=$currentVersion, 开始部署")
        return performDeploy(context, currentVersion)
    }

    fun forceDeploy(context: Context): Boolean {
        val currentVersion = getAppVersionCode(context)
        Log.i(TAG, "强制部署资源文件，版本=$currentVersion")
        return performDeploy(context, currentVersion)
    }

    fun getSharedDataDir(context: Context): String {
        return File(context.filesDir, ASSETS_RIME_DIR).absolutePath
    }

    fun getUserDataDir(context: Context): String {
        return File(context.filesDir, "rime_user").absolutePath
    }

    fun isDeployed(context: Context): Boolean {
        return getDeployedVersion(context) > 0
    }

    private fun performDeploy(context: Context, versionCode: Long): Boolean {
        return try {
            val targetDir = File(getSharedDataDir(context))
            if (!targetDir.exists()) {
                targetDir.mkdirs()
                Log.i(TAG, "创建目标目录: ${targetDir.absolutePath}")
            }

            val userDir = File(getUserDataDir(context))
            if (!userDir.exists()) {
                userDir.mkdirs()
                Log.i(TAG, "创建用户数据目录: ${userDir.absolutePath}")
            }

            copyAssetsRecursive(context, ASSETS_RIME_DIR, targetDir)

            saveDeployedVersion(context, versionCode)
            Log.i(TAG, "资源部署完成，版本=$versionCode")
            true
        } catch (e: IOException) {
            Log.e(TAG, "资源部署失败: ${e.message}", e)
            false
        }
    }

    private fun copyAssetsRecursive(context: Context, assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath)

        if (files.isNullOrEmpty()) {
            copyAssetFile(context, assetPath, File(targetDir, File(assetPath).name))
            return
        }

        for (fileName in files) {
            val childAssetPath = "$assetPath/$fileName"
            val childFiles = assetManager.list(childAssetPath)

            if (childFiles.isNullOrEmpty()) {
                val targetFile = File(targetDir, fileName)
                copyAssetFile(context, childAssetPath, targetFile)
            } else {
                val subDir = File(targetDir, fileName)
                if (!subDir.exists()) {
                    subDir.mkdirs()
                }
                copyAssetsRecursive(context, childAssetPath, subDir)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "已复制: $assetPath → ${targetFile.name}")
        } catch (e: IOException) {
            Log.e(TAG, "复制文件失败: $assetPath → ${targetFile.absolutePath}", e)
            throw e
        }
    }

    private fun getAppVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "获取版本号失败", e)
            1L
        }
    }

    private fun getDeployedVersion(context: Context): Long {
        return getPreferences(context).getLong(KEY_DEPLOYED_VERSION, 0L)
    }

    private fun saveDeployedVersion(context: Context, version: Long) {
        getPreferences(context).edit()
            .putLong(KEY_DEPLOYED_VERSION, version)
            .apply()
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
