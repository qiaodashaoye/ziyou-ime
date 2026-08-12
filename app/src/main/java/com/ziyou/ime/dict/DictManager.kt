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
 * - 动态重新生成 luna_pinyin.dict.yaml（注入已启用的扩展词库）
 * - 维护本地安装记录 ext_dicts.json
 */
object DictManager {

    private const val TAG = "DictManager"

    /** 扩展词库存放子目录名 */
    private const val EXT_DICTS_DIR = "ext_dicts"
    /** 本地安装记录文件名 */
    private const val CONFIG_FILE = "ext_dicts.json"
    /** 主词库文件名 */
    private const val MAIN_DICT_FILE = "luna_pinyin.dict.yaml"

    /**
     * 基础 import_tables（始终包含）。
     *
     * ext / tencent / others 三个大词库自 2026-08 起移出主程序包，改为扩展词库
     * 由用户按需下载（见远程 catalog 的 BASE 分类条目）；下载后走 ext_dicts/
     * 注入通道，与其他扩展词库一致。
     */
    private val BASE_IMPORT_TABLES = listOf(
        "cn_dicts/8105",
        "cn_dicts/base",
        "en_dicts/base"
    )

    /** 建议最大同时启用词库数 */
    const val MAX_ENABLED_DICTS = 5

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
        return File(sharedDir, MAIN_DICT_FILE)
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

    // ===== 主词库重新生成 =====

    /**
     * 重新生成 luna_pinyin.dict.yaml
     * 保留基础 import_tables，动态追加已启用的扩展词库
     *
     * 此方法在以下时机调用：
     * 1. 应用启动时（RimeSession.initialize 中，AssetDeployer 之后）
     * 2. 词库安装/卸载/启用/禁用后
     */
    fun regenerateMainDict(context: Context) {
        try {
            val mainDictFile = getMainDictFile(context)
            if (!mainDictFile.exists()) {
                Log.w(TAG, "主词库文件不存在: ${mainDictFile.absolutePath}")
                return
            }

            val enabledDicts = getInstalledDicts(context).filter { it.enabled }
            val content = buildMainDictContent(enabledDicts)
            mainDictFile.writeText(content)

            Log.i(TAG, "主词库已重新生成，启用 ${enabledDicts.size} 个扩展词库")
        } catch (e: Exception) {
            Log.e(TAG, "重新生成主词库失败: ${e.message}", e)
        }
    }

    /**
     * 构建主词库文件内容
     */
    private fun buildMainDictContent(enabledDicts: List<InstalledDictInfo>): String {
        val sb = StringBuilder()

        // 头部注释
        sb.appendLine("# Rime dictionary")
        sb.appendLine("# encoding: utf-8")
        sb.appendLine("#")
        sb.appendLine("# 朙月拼音·简体词库")
        sb.appendLine("# 基于雾凇拼音 (rime-ice) 长期维护的简体中文词库")
        sb.appendLine("# https://github.com/iDvel/rime-ice")
        sb.appendLine("#")
        sb.appendLine("# 词库组成：")
        sb.appendLine("#   - 《通用规范汉字表》8105 字字表")
        sb.appendLine("#   - 基础词库（华宇野风、清华开源词库、现代汉语常用词表）")
        sb.appendLine("#   - 内置英文词与表情映射表")
        sb.appendLine("#   - 扩展/腾讯词向量/杂项等大词库已改为按需下载（词库管理页安装后自动注入）")
        if (enabledDicts.isNotEmpty()) {
            sb.appendLine("#   - 扩展词库：${enabledDicts.joinToString("、") { it.id }}")
        }
        sb.appendLine("#")
        sb.appendLine()

        // YAML 头部 + import_tables
        sb.appendLine("---")
        sb.appendLine("name: luna_pinyin")
        sb.appendLine("version: \"2026-08-12\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("import_tables:")
        for (table in BASE_IMPORT_TABLES) {
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
