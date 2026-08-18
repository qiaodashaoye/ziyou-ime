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

    /** librime-predict 联想词库文件名（位于 assets 根目录，部署到用户目录供 predictor 加载） */
    private const val PREDICT_DB = "predict.db"

    /**
     * 白霜迁移用户词库迁移（方案 3.3/6.3）：方案改名 luna_pinyin → rime_frost 后
     * userdb 不会自动继承，部署阶段一次性整目录复制（leveldb 目录拷贝即有效，
     * 词键为「编码+词」与方案名无关）。源目录保留不动，兼作 L4 数据级
     * 回滚备份（方案 6.4；如需回滚，复制回即可）。
     */
    private const val LEGACY_USER_DB = "luna_pinyin.userdb"
    private const val FROST_USER_DB = "rime_frost.userdb"

    /**
     * 已移出主程序包的历史内置词库（2026-08 起改为扩展词库按需下载）。
     * 部署只覆盖不删除，升级后需主动清理旧安装残留，释放约 46MB 磁盘；
     * 这些文件不再被 luna_pinyin.dict.yaml 的 import_tables 引用，
     * 用户重新下载时写入 ext_dicts/ 目录，与本清理无冲突。
     *
     * 注意：cn_dicts/others.dict.yaml 曾在此清单，白霜迁移（2026-08）后
     * 该文件名被 frost 杂项补充表复用并重新入包，继续清理会导致部署后
     * 立即被删、rime_frost 编译失败（真机冒烟实证），故移除；
     * 同名旧文件会被 copyAssetsRecursive 覆盖，无残留风险。
     */
    private val LEGACY_BUILTIN_DICTS = listOf(
        "cn_dicts/ext.dict.yaml",
        "cn_dicts/tencent.dict.yaml"
    )

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

    /**
     * 是否需要（重新）部署：应用版本与已部署版本不一致时返回 true。
     * 首次安装（已部署版本为 0）或版本升级（新增/修改方案）均返回 true，
     * 供调用方据此决定是否让 Rime 执行完整维护（fullCheck）以编译新方案。
     */
    fun needsDeploy(context: Context): Boolean {
        return getAppVersionCode(context) != getDeployedVersion(context)
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

            // 清理已移出主包的历史内置大词库残留（仅删目标文件，失败不阻断部署）
            LEGACY_BUILTIN_DICTS.forEach { relativePath ->
                val legacyFile = File(targetDir, relativePath)
                if (legacyFile.exists() && legacyFile.delete()) {
                    Log.i(TAG, "已清理历史内置词库: $relativePath")
                }
            }

            // 部署 librime-predict 联想词库到用户目录（predictor 默认从 user dir 解析 predict.db）
            copyAssetFile(context, PREDICT_DB, File(userDir, PREDICT_DB))

            // 白霜迁移：旧方案用户词库一次性迁移（幂等，失败不阻断部署）
            migrateUserDb(userDir)

            saveDeployedVersion(context, versionCode)
            Log.i(TAG, "资源部署完成，版本=$versionCode")
            true
        } catch (e: IOException) {
            Log.e(TAG, "资源部署失败: ${e.message}", e)
            false
        }
    }

    /**
     * 用户词库一次性迁移：luna_pinyin.userdb → rime_frost.userdb。
     * 幂等守卫：目标已存在则跳过（含旧版已迁移/用户已有 frost 自造词场景，
     * 绝不覆盖）；源不存在则 no-op。采用复制而非移动：luna 回滚方案
     * 仍引用源 userdb，且源目录留存即 L4 数据级备份（方案 6.4）。
     * 失败时清理半成品目标目录，下次升版部署自动重试。
     */
    private fun migrateUserDb(userDir: File) {
        val source = File(userDir, LEGACY_USER_DB)
        val target = File(userDir, FROST_USER_DB)
        // leveldb 用户词库是目录；防御异常形态（同名文件）直接跳过
        if (!source.exists() || !source.isDirectory) return
        if (target.exists()) {
            Log.d(TAG, "用户词库迁移跳过：$FROST_USER_DB 已存在")
            return
        }
        try {
            source.copyRecursively(target)
            Log.i(TAG, "用户词库迁移完成: $LEGACY_USER_DB → $FROST_USER_DB（源目录保留作回滚备份）")
        } catch (e: IOException) {
            Log.w(TAG, "用户词库迁移失败(不阻断部署): ${e.message}")
            // 清理不完整的复制残留，避免下次被 target.exists() 误判为已迁移
            if (target.exists()) {
                target.deleteRecursively()
            }
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
