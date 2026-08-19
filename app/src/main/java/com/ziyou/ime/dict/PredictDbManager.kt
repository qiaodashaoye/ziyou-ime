package com.ziyou.ime.dict

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * predict.db 联想子库管理器（旁路分发通道，单例）
 *
 * 职责：catalog 中 `kind=predict_db` 条目（如全量诗词增强库）的安装/卸载/
 * 启用切换与跨版本持久化。与 dict.yaml 扩展词库的本质差异：
 * - dict.yaml 注入 import_tables 由 Rime 部署期编译；predict.db 是引擎直读的
 *   只读二进制（predictor 从用户目录解析），**只能整体替换**、无法与基础库合并；
 * - 替换后经 [com.ziyou.ime.sdk.RimeSdk.redeploy] 重启引擎生效；
 * - 安装记录独立于 ext_dicts.json（[CONFIG_FILE]）：DictManager.regenerateMainDict
 *   会把已安装条目写进 import_tables，predict.db 条目混入会污染主词库。
 *
 * 替换与回滚语义：
 * - 首次安装时把**当前基础库**备份为 [BASE_BACKUP_NAME]（仅首次，永不覆盖——
 *   备份必须是未经增强的基础版，否则卸载后回不到原始状态）；
 * - 卸载/禁用 = 恢复备份；再启用 = 重新覆盖已下载的增强库（免重复下载）。
 *
 * 跨版本持久化：应用升版时 AssetDeployer 以 assets 覆盖 predict.db，
 * [reapplyIfInstalled] 作为部署步骤在其后把已安装的增强库重新覆盖回去
 * （组合根装配见 AppContainer.deploySteps）。
 */
object PredictDbManager {

    private const val TAG = "PredictDbManager"

    /** 用户目录中的联想库文件名（predictor 约定名，schema `predictor/db` 同源） */
    const val PREDICT_DB_NAME = "predict.db"

    /** 基础库备份名（首次安装前的原始状态，卸载/禁用时恢复） */
    const val BASE_BACKUP_NAME = "predict.db.base"

    /** 安装记录文件名（独立于 ext_dicts.json，见类注释） */
    private const val CONFIG_FILE = "predict_ext.json"

    // ===== 路径访问 =====

    /** 用户目录（与 AssetDeployer.getUserDataDir 同口径：filesDir/rime_user） */
    private fun getUserDir(context: Context): File = File(context.filesDir, "rime_user")

    /** 下载产物与安装记录的根目录（与 DictManager.ext_dicts 同级：filesDir/rime） */
    private fun getSharedDir(context: Context): File = File(context.filesDir, "rime")

    /** 下载产物文件：ext_dicts/<id>.predict.db（保留文件支持免下载再启用/升版恢复） */
    fun getExtFile(context: Context, id: String): File =
        File(File(getSharedDir(context), "ext_dicts"), "$id.predict.db")

    private fun getConfigFile(context: Context): File = File(getSharedDir(context), CONFIG_FILE)

    // ===== 安装/卸载/启用 =====

    /**
     * 安装 predict.db 子库：下载（sha256 校验）→ 首次备份基础库 → 替换 → 记录。
     * 引擎重载由调用方（ViewModel）在安装成功后触发 redeploy 完成。
     */
    suspend fun installPredictDb(
        context: Context,
        info: RemoteDictInfo,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val extFile = getExtFile(context, info.id)
        val downloaded = DictDownloader.downloadTo(
            info = info,
            targetFile = extFile,
            maxBytes = DictDownloader.MAX_PREDICT_DB_BYTES,
            onProgress = onProgress
        )
        if (downloaded == null) {
            Log.e(TAG, "下载联想子库 ${info.id} 失败")
            return@withContext false
        }

        try {
            val userDir = getUserDir(context)
            userDir.mkdirs()
            val target = File(userDir, PREDICT_DB_NAME)
            // 首次安装才备份：备份必须是基础版，覆盖会导致卸载后无法回原始状态
            val backup = File(userDir, BASE_BACKUP_NAME)
            if (!backup.exists() && target.exists()) {
                target.copyTo(backup, overwrite = false)
                Log.i(TAG, "基础联想库已备份: ${backup.name}")
            }
            downloaded.copyTo(target, overwrite = true)

            val records = getInstalled(context).toMutableList()
            records.removeAll { it.id == info.id }
            records.add(
                InstalledDictInfo(
                    id = info.id,
                    version = info.version,
                    enabled = true,
                    installedAt = System.currentTimeMillis()
                )
            )
            saveInstalled(context, records)
            Log.i(TAG, "联想子库 ${info.id} 安装成功（${downloaded.length()} bytes）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装联想子库 ${info.id} 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 卸载：恢复基础库备份 + 删除下载产物与记录。
     * 备份缺失（异常状态）时仅清记录，predict.db 保持现状等待升版覆盖。
     */
    suspend fun uninstallPredictDb(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            restoreBase(context)
            getExtFile(context, id).delete()
            saveInstalled(context, getInstalled(context).filterNot { it.id == id })
            Log.i(TAG, "联想子库 $id 已卸载")
            true
        } catch (e: Exception) {
            Log.e(TAG, "卸载联想子库 $id 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 启用/禁用（免重复下载）：启用=下载产物覆盖回 predict.db；禁用=恢复基础备份。
     * 下载产物缺失时启用失败返回 false（调用方引导重新安装）。
     */
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (enabled) {
                    val extFile = getExtFile(context, id)
                    if (!extFile.exists()) {
                        Log.w(TAG, "联想子库 $id 下载产物缺失，无法启用（需重新安装）")
                        return@withContext false
                    }
                    val userDir = getUserDir(context)
                    userDir.mkdirs()
                    extFile.copyTo(File(userDir, PREDICT_DB_NAME), overwrite = true)
                } else {
                    restoreBase(context)
                }
                val records = getInstalled(context).toMutableList()
                val index = records.indexOfFirst { it.id == id }
                if (index < 0) return@withContext false
                records[index] = records[index].copy(enabled = enabled)
                saveInstalled(context, records)
                Log.i(TAG, "联想子库 $id 启用状态: $enabled")
                true
            } catch (e: Exception) {
                Log.e(TAG, "切换联想子库 $id 启用状态失败: ${e.message}", e)
                false
            }
        }

    /**
     * 部署步骤（组合根装配，AssetDeployer 之后执行）：应用升版覆盖 predict.db 后，
     * 把已安装且启用的子库重新覆盖回去，实现跨版本持久化。文件缺失静默跳过。
     */
    fun reapplyIfInstalled(context: Context) {
        val active = getInstalled(context).firstOrNull { it.enabled } ?: return
        val extFile = getExtFile(context, active.id)
        if (!extFile.exists()) {
            Log.w(TAG, "已安装联想子库 ${active.id} 产物缺失，跳过恢复")
            return
        }
        try {
            val userDir = getUserDir(context)
            userDir.mkdirs()
            extFile.copyTo(File(userDir, PREDICT_DB_NAME), overwrite = true)
            Log.i(TAG, "已恢复联想子库 ${active.id} 到 predict.db")
        } catch (e: Exception) {
            Log.e(TAG, "恢复联想子库失败: ${e.message}", e)
        }
    }

    // ===== 内部工具 =====

    /** 恢复基础库备份（备份缺失时不改动现状并告警） */
    private fun restoreBase(context: Context) {
        val userDir = getUserDir(context)
        val backup = File(userDir, BASE_BACKUP_NAME)
        val target = File(userDir, PREDICT_DB_NAME)
        if (backup.exists()) {
            backup.copyTo(target, overwrite = true)
        } else {
            Log.w(TAG, "基础联想库备份缺失，predict.db 保持现状（升版时将重新部署基础版）")
        }
    }

    /** 读取安装记录（损坏降级为空列表） */
    fun getInstalled(context: Context): List<InstalledDictInfo> {
        val file = getConfigFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("installed") ?: JSONArray()
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                InstalledDictInfo(
                    id = obj.getString("id"),
                    version = obj.optString("version", "1.0.0"),
                    enabled = obj.optBoolean("enabled", true),
                    installedAt = obj.optLong("installedAt", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取联想子库安装记录失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveInstalled(context: Context, records: List<InstalledDictInfo>) {
        val file = getConfigFile(context)
        try {
            val array = JSONArray()
            for (r in records) {
                array.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("version", r.version)
                        put("enabled", r.enabled)
                        put("installedAt", r.installedAt)
                    }
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("installed", array).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "保存联想子库安装记录失败: ${e.message}", e)
        }
    }
}
