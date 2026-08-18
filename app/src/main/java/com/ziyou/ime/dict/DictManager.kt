package com.ziyou.ime.dict

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扩展词库管理器（单例）
 *
 * 职责：
 * - 管理扩展词库的安装/卸载/启用/禁用
 * - 动态重新生成主词库 dict.yaml（按 [DictBackend] 选择模板，注入已启用的扩展词库）
 * - 维护本地安装记录 ext_dicts.json（含 catalog v4 legacy id 幂等迁移）
 */
object DictManager {

    private const val TAG = "DictManager"

    /** 扩展词库存放子目录名 */
    private const val EXT_DICTS_DIR = "ext_dicts"
    /** 本地安装记录文件名 */
    private const val CONFIG_FILE = "ext_dicts.json"

    /** 主词库后端偏好 SharedPreferences（L2 开关级回滚，见迁移方案 6.4） */
    private const val PREF_NAME = "ziyou_dict_prefs"
    private const val KEY_BACKEND = "dict_backend"

    /**
     * 主词库后端：决定 [regenerateMainDict] 重写哪个 dict.yaml 及其基础表清单。
     * FROST 为白霜迁移后默认；LUNA 保留作回滚路径（方案 6.4 L2 开关）。
     */
    enum class DictBackend(val mainDictFile: String) {
        FROST("rime_frost.dict.yaml"),
        LUNA("luna_pinyin.dict.yaml")
    }

    /**
     * 各后端的基础 import_tables（始终包含）。
     *
     * FROST：白霜核心四表（8105/base 为白霜重统计字频词频主体，corrections 供
     * corrector.lua）；英文由 melt_eng 方案独立挂载，不入主词库。
     * LUNA：迁移前既有清单；ext/tencent/others 三个大词库自 2026-08 起改为
     * 扩展词库按需下载（catalog legacy_* 条目），下载后走 ext_dicts/ 注入通道。
     */
    private val FROST_BASE_IMPORT_TABLES = listOf(
        "cn_dicts/8105",
        "cn_dicts/base",
        "cn_dicts/others",
        "cn_dicts/corrections"
    )
    private val LUNA_BASE_IMPORT_TABLES = listOf(
        "cn_dicts/8105",
        "cn_dicts/base",
        "en_dicts/base"
    )

    /**
     * catalog v4 改名的旧 id → 新 id 映射（迁移方案 6.1）。
     * 旧 id 与 frost cn_dicts/ 内置表同名，并存会导致词条重复权重叠加，
     * 已安装记录启动时幂等迁移到 legacy_* 名。
     */
    internal val LEGACY_ID_MIGRATION = mapOf(
        "ext" to "legacy_ext",
        "tencent" to "legacy_tencent",
        "others" to "legacy_others"
    )

    /** 建议最大同时启用词库数 */
    const val MAX_ENABLED_DICTS = 5

    // ===== 后端偏好 =====

    /** 读取主词库后端（默认 FROST；非法值降级 FROST） */
    fun getBackend(context: Context): DictBackend {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, null) ?: return DictBackend.FROST
        return DictBackend.entries.firstOrNull { it.name == name } ?: DictBackend.FROST
    }

    /**
     * 切换主词库后端（仅持久化偏好；生效需调用方随后触发
     * `RimeSession.redeploy`，其部署步骤会按新后端重写主词库）。
     */
    fun setBackend(context: Context, backend: DictBackend) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND, backend.name)
            .apply()
        Log.i(TAG, "主词库后端切换为 ${backend.name}（redeploy 后生效）")
    }

    // ===== 目录访问 =====

    fun getExtDictsDir(context: Context): File {
        val sharedDir = File(context.filesDir, "rime")
        return File(sharedDir, EXT_DICTS_DIR)
    }

    private fun getConfigFile(context: Context): File {
        val sharedDir = File(context.filesDir, "rime")
        return File(sharedDir, CONFIG_FILE)
    }

    private fun getMainDictFile(context: Context): File {
        val sharedDir = File(context.filesDir, "rime")
        return File(sharedDir, getBackend(context).mainDictFile)
    }

    // ===== 安装记录读写 =====

    fun getInstalledDicts(context: Context): List<InstalledDictInfo> {
        val configFile = getConfigFile(context)
        if (!configFile.exists()) return emptyList()

        return try {
            val jsonStr = configFile.readText()
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("installed") ?: JSONArray()
            val list = mutableListOf<InstalledDictInfo>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    InstalledDictInfo(
                        id = obj.getString("id"),
                        version = obj.optString("version", "1.0.0"),
                        enabled = obj.optBoolean("enabled", true),
                        installedAt = obj.optLong("installedAt", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "读取安装记录失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveInstalledDicts(context: Context, dicts: List<InstalledDictInfo>) {
        val configFile = getConfigFile(context)
        try {
            val array = JSONArray()
            for (dict in dicts) {
                val obj = JSONObject().apply {
                    put("id", dict.id)
                    put("version", dict.version)
                    put("enabled", dict.enabled)
                    put("installedAt", dict.installedAt)
                }
                array.put(obj)
            }
            val root = JSONObject().apply {
                put("installed", array)
            }
            configFile.parentFile?.mkdirs()
            configFile.writeText(root.toString(2))
            Log.i(TAG, "保存安装记录: ${dicts.size} 个词库")
        } catch (e: Exception) {
            Log.e(TAG, "保存安装记录失败: ${e.message}", e)
        }
    }

    // ===== 安装/卸载/启用/禁用 =====

    /**
     * 安装词库：下载 + 记录 + 重新生成主词库
     * 整体在 IO 线程执行：下载后的安装记录读写与主词库重写均为磁盘 IO，
     * 不得回到调用方上下文（通常是主线程的 viewModelScope）执行。
     * @return 是否成功
     */
    suspend fun installDict(
        context: Context,
        info: RemoteDictInfo,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val extDir = getExtDictsDir(context)
        val file = DictDownloader.downloadDict(info, extDir, onProgress)
        if (file == null) {
            Log.e(TAG, "下载词库 ${info.id} 失败")
            return@withContext false
        }

        // 更新安装记录
        val installed = getInstalledDicts(context).toMutableList()
        installed.removeAll { it.id == info.id }
        installed.add(
            InstalledDictInfo(
                id = info.id,
                version = info.version,
                enabled = true,
                installedAt = System.currentTimeMillis()
            )
        )
        saveInstalledDicts(context, installed)

        // 重新生成主词库
        regenerateMainDict(context)

        Log.i(TAG, "词库 ${info.id} 安装成功")
        true
    }

    /**
     * 卸载词库：删除文件 + 更新记录 + 重新生成主词库
     */
    suspend fun uninstallDict(context: Context, dictId: String): Boolean = withContext(Dispatchers.IO) {
        // 删除文件
        val dictFile = File(getExtDictsDir(context), "$dictId.dict.yaml")
        if (dictFile.exists()) {
            dictFile.delete()
        }

        // 更新记录
        val installed = getInstalledDicts(context).toMutableList()
        installed.removeAll { it.id == dictId }
        saveInstalledDicts(context, installed)

        // 重新生成主词库
        regenerateMainDict(context)

        Log.i(TAG, "词库 $dictId 已卸载")
        true
    }

    /**
     * 设置词库启用/禁用状态
     */
    suspend fun setEnabled(context: Context, dictId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val installed = getInstalledDicts(context).toMutableList()
        val index = installed.indexOfFirst { it.id == dictId }
        if (index < 0) {
            Log.w(TAG, "词库 $dictId 未安装")
            return@withContext false
        }

        installed[index] = installed[index].copy(enabled = enabled)
        saveInstalledDicts(context, installed)

        // 重新生成主词库
        regenerateMainDict(context)

        Log.i(TAG, "词库 $dictId 启用状态: $enabled")
        true
    }

    /**
     * 检查已安装词库是否有可用更新
     * @return 需要更新的词库 ID 列表
     */
    fun checkUpdates(context: Context, catalog: DictCatalog): List<String> {
        val installed = getInstalledDicts(context)
        val updatable = mutableListOf<String>()

        for (local in installed) {
            val remote = catalog.dictionaries.firstOrNull { it.id == local.id }
            if (remote != null && remote.version != local.version) {
                updatable.add(local.id)
            }
        }
        return updatable
    }

    // ===== legacy id 迁移 =====

    /**
     * 纯函数：规划安装记录的 legacy id 改名（可单测）。
     * 规则：旧 id 命中 [LEGACY_ID_MIGRATION] 且新 id 未占用 → 替换；
     * 新 id 已存在（历史已迁移/重装）→ 丢弃旧记录避免重复注入。
     * @return 迁移后的记录列表（无变化时返回原列表）
     */
    internal fun planLegacyIdMigration(installed: List<InstalledDictInfo>): List<InstalledDictInfo> {
        val existingIds = installed.mapTo(HashSet()) { it.id }
        var changed = false
        val result = installed.mapNotNull { info ->
            val newId = LEGACY_ID_MIGRATION[info.id] ?: return@mapNotNull info
            if (newId in existingIds) {
                changed = true
                null
            } else {
                changed = true
                info.copy(id = newId)
            }
        }
        return if (changed) result else installed
    }

    /**
     * 幂等迁移已安装词库的 legacy id（改名记录 + 改名落盘文件）。
     * 文件改名失败时该条保留旧 id（旧表与 frost 内置表同源，短期并存
     * 仅权重轻微叠加，无功能错误，见方案 6.1）。在 [regenerateMainDict]
     * 头部执行，对无旧 id 的安装状态为零开销 no-op。
     */
    private fun migrateLegacyDictIds(context: Context) {
        try {
            val installed = getInstalledDicts(context)
            val migrated = planLegacyIdMigration(installed)
            if (migrated === installed) return

            val extDir = getExtDictsDir(context)
            // 改名配对：按 installedAt 精确匹配新旧记录（不能用 zip：
            // 新 id 已占用时旧记录被丢弃，列表长度不等会错位覆盖已有文件）
            val renamePairs = installed.filter { LEGACY_ID_MIGRATION.containsKey(it.id) }
                .mapNotNull { old ->
                    val newId = LEGACY_ID_MIGRATION.getValue(old.id)
                    val new = migrated.firstOrNull { it.id == newId && it.installedAt == old.installedAt }
                    if (new != null) old.id to newId else null
                }
            for ((oldId, newId) in renamePairs) {
                val oldFile = File(extDir, "$oldId.dict.yaml")
                val newFile = File(extDir, "$newId.dict.yaml")
                if (oldFile.exists() && !oldFile.renameTo(newFile)) {
                    Log.w(TAG, "词库文件改名失败 $oldId → $newId，保留旧 id")
                }
            }
            // 改名失败的回退旧 id：以文件实际存在性为准重建记录
            val final = migrated.mapNotNull { info ->
                val sourceOld = installed.firstOrNull { LEGACY_ID_MIGRATION[it.id] == info.id }
                if (sourceOld != null &&
                    !File(extDir, "${info.id}.dict.yaml").exists() &&
                    File(extDir, "${sourceOld.id}.dict.yaml").exists()
                ) sourceOld else info
            }
            saveInstalledDicts(context, final)
            Log.i(TAG, "legacy 词库 id 迁移完成: ${renamePairs.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "legacy id 迁移失败(不阻断启动): ${e.message}", e)
        }
    }

    // ===== 主词库重新生成 =====

    /**
     * 重新生成主词库（按当前 [DictBackend] 选择 rime_frost/luna_pinyin）
     * 保留基础 import_tables，动态追加已启用的扩展词库
     *
     * 此方法在以下时机调用：
     * 1. 应用启动时（RimeSession.initialize 中，AssetDeployer 之后）
     * 2. 词库安装/卸载/启用/禁用后
     * 3. 后端切换后的 redeploy（部署步骤重走本方法）
     *
     * 白霜迁移（Phase 3）：默认后端 FROST，扩展词库同步注入 rime_frost
     * 主词库；头部先做 legacy id 幂等迁移，避免旧 id 与 frost 内置表同名叠加。
     */
    fun regenerateMainDict(context: Context) {
        try {
            migrateLegacyDictIds(context)

            val backend = getBackend(context)
            val mainDictFile = getMainDictFile(context)
            if (!mainDictFile.exists()) {
                Log.w(TAG, "主词库文件不存在(${backend.name}): ${mainDictFile.absolutePath}")
                return
            }

            val enabledDicts = getInstalledDicts(context).filter { it.enabled }
            val content = buildMainDictContent(backend, enabledDicts)
            mainDictFile.writeText(content)

            Log.i(TAG, "主词库已重新生成(${backend.name})，启用 ${enabledDicts.size} 个扩展词库")
        } catch (e: Exception) {
            Log.e(TAG, "重新生成主词库失败: ${e.message}", e)
        }
    }

    /**
     * 构建主词库文件内容（按后端选基础表清单与头注）
     */
    internal fun buildMainDictContent(backend: DictBackend, enabledDicts: List<InstalledDictInfo>): String {
        val sb = StringBuilder()

        // 头部注释
        sb.appendLine("# Rime dictionary")
        sb.appendLine("# encoding: utf-8")
        sb.appendLine("#")
        if (backend == DictBackend.FROST) {
            sb.appendLine("# 白霜拼音主词库（字由输入法）")
            sb.appendLine("# 基于白霜拼音 (rime-frost)，745396750 字语料重统计字频/词频")
            sb.appendLine("# https://github.com/gaboolic/rime-frost")
            sb.appendLine("#")
            sb.appendLine("# 词库组成：")
            sb.appendLine("#   - 《通用规范汉字表》8105 字字表（白霜重统计字频）")
            sb.appendLine("#   - 白霜基础词库（重统计词频）+ 杂项补充 + 错音错字提示")
            sb.appendLine("#   - 英文词由 melt_eng 方案独立挂载，不入本词库")
        } else {
            sb.appendLine("# 朙月拼音·简体词库（回滚路径）")
            sb.appendLine("# 基于雾凇拼音 (rime-ice) 长期维护的简体中文词库")
            sb.appendLine("# https://github.com/iDvel/rime-ice")
            sb.appendLine("#")
            sb.appendLine("# 词库组成：")
            sb.appendLine("#   - 《通用规范汉字表》8105 字字表")
            sb.appendLine("#   - 基础词库（华宇野风、清华开源词库、现代汉语常用词表）")
            sb.appendLine("#   - 内置英文词与表情映射表")
        }
        sb.appendLine("#   - 大词库已改为按需下载（词库管理页安装后自动注入）")
        if (enabledDicts.isNotEmpty()) {
            sb.appendLine("#   - 扩展词库：${enabledDicts.joinToString("、") { it.id }}")
        }
        sb.appendLine("#")
        sb.appendLine("# 注意：本文件由 DictManager.regenerateMainDict 在引擎启动前重写，")
        sb.appendLine("# 头部与 import_tables 须与 DictManager.buildMainDictContent 保持同步。")
        sb.appendLine()

        // YAML 头部 + import_tables
        val baseTables = when (backend) {
            DictBackend.FROST -> FROST_BASE_IMPORT_TABLES
            DictBackend.LUNA -> LUNA_BASE_IMPORT_TABLES
        }
        sb.appendLine("---")
        sb.appendLine("name: ${backend.mainDictFile.removeSuffix(".dict.yaml")}")
        sb.appendLine("version: \"2026-08-18\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("import_tables:")
        for (table in baseTables) {
            sb.appendLine("  - $table")
        }
        // 追加已启用的扩展词库
        for (dict in enabledDicts) {
            sb.appendLine("  - $EXT_DICTS_DIR/${dict.id}")
        }
        sb.appendLine("...")
        sb.appendLine()

        // 附加词条（大写字母 + 数字）
        sb.appendLine("# 大写字母（支持拼音中混入大写字母造词，如 DNA、GDP）")
        for (c in 'A'..'Z') {
            sb.appendLine("$c\t$c")
        }
        sb.appendLine()
        sb.appendLine("# 数字参与造词")
        sb.appendLine("0\tling")
        sb.appendLine("1\tyi")
        sb.appendLine("1\tyao")
        sb.appendLine("2\ter")
        sb.appendLine("3\tsan")
        sb.appendLine("4\tsi")
        sb.appendLine("5\twu")
        sb.appendLine("6\tliu")
        sb.appendLine("7\tqi")
        sb.appendLine("8\tba")
        sb.appendLine("9\tjiu")

        return sb.toString()
    }

    // ===== 本地词库预览 =====

    /**
     * 读取已安装词库的本地预览
     * @param context 上下文
     * @param dictId 词库 ID
     * @param info 远程词库信息（用于填充预览元数据）
     * @param maxEntries 最大预览词条数
     * @return 预览数据，文件不存在或解析失败返回 null
     */
    fun readLocalDictPreview(
        context: Context,
        dictId: String,
        info: RemoteDictInfo,
        maxEntries: Int = 50
    ): DictPreview? {
        val dictFile = File(getExtDictsDir(context), "$dictId.dict.yaml")
        if (!dictFile.exists()) {
            Log.w(TAG, "本地词库文件不存在: ${dictFile.absolutePath}")
            return null
        }

        return try {
            val content = dictFile.readText()
            val entries = parseLocalDictEntries(content, maxEntries)
            val totalHint = countLocalDictEntries(content)
            DictPreview(
                dictInfo = info,
                entries = entries,
                totalEntriesHint = totalHint
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取本地词库预览失败: ${e.message}", e)
            null
        }
    }

    /** 解析本地 dict.yaml 词条 */
    private fun parseLocalDictEntries(content: String, maxEntries: Int): List<DictEntry> {
        val entries = mutableListOf<DictEntry>()
        var inBody = false

        for (line in content.lineSequence()) {
            if (!inBody) {
                if (line.trim() == "...") inBody = true
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size >= 2) {
                entries.add(DictEntry(word = parts[0], code = parts[1]))
                if (entries.size >= maxEntries) break
            }
        }
        return entries
    }

    /** 统计本地词库词条总数 */
    private fun countLocalDictEntries(content: String): Int {
        var inBody = false
        var count = 0
        for (line in content.lineSequence()) {
            if (!inBody) {
                if (line.trim() == "...") inBody = true
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.contains("\t")) count++
        }
        return count
    }
}
