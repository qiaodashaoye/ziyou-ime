package com.ziyou.ime.ai.knowledge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * 知识库持久化仓库
 *
 * 持久化模式与 [com.ziyou.ime.ai.PersonaRepository] 对齐：
 * - SharedPreferences（`ziyou_ai_knowledge`）存开关、Top-K 与条目元数据 JSON 数组；
 * - chunk 正文以 JSON 数组落 `filesDir/knowledge/<itemId>.json`（SP 有体积限制，
 *   正文不入 SP）；内存倒排索引不持久化，由 [KnowledgeSearcher] 从 chunk 懒构建。
 *
 * 容量纪律：单条目 chunk ≤ [MAX_CHUNKS_PER_ITEM]，全库正文 ≤ [MAX_TOTAL_CHARS]
 * 字符，超限由导入侧拒绝（见 [KnowledgeImporter]）。
 */
object KnowledgeRepository {

    private const val TAG = "KnowledgeRepository"
    private const val PREF_NAME = "ziyou_ai_knowledge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOP_K = "top_k"
    private const val KEY_ITEMS = "items"

    /** chunk 文件目录名（filesDir 下） */
    private const val CHUNK_DIR = "knowledge"

    /** 检索 Top-K 默认值 */
    const val DEFAULT_TOP_K = 4

    /** 单条目 chunk 数上限 */
    const val MAX_CHUNKS_PER_ITEM = 2000

    /** 全库正文字符总量上限（约 10MB 文本） */
    const val MAX_TOTAL_CHARS = 10 * 1024 * 1024

    // ===== 开关与配置 =====

    /** 知识库总开关（默认关闭：关闭时检索/索引零开销）。 */
    fun isEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 检索 Top-K（暂无设置 UI，预留配置项）。 */
    fun getTopK(context: Context): Int =
        getPreferences(context).getInt(KEY_TOP_K, DEFAULT_TOP_K)

    // ===== 条目元数据 CRUD =====

    /** 全部知识条目（导入时间倒序）。 */
    fun getItems(context: Context): List<KnowledgeItem> {
        val json = getPreferences(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length())
                .map { KnowledgeItem.fromJson(array.getJSONObject(it)) }
                .sortedByDescending { it.importedAt }
        } catch (e: Exception) {
            Log.e(TAG, "反序列化知识条目失败: ${e.message}", e)
            emptyList()
        }
    }

    /** 是否存在任何知识条目（面板侧快速判断，避免无谓的索引加载）。 */
    fun hasItems(context: Context): Boolean = getItems(context).isNotEmpty()

    /** 全库正文字符总量（导入前容量检查用）。 */
    fun totalChars(context: Context): Int = getItems(context).sumOf { it.totalChars }

    /**
     * 新增条目：先落 chunk 文件、后提交元数据（顺序保证失败无脏元数据）。
     * 同 ID 已存在时覆盖（文件夹增量同步复用）。
     */
    fun addItem(context: Context, item: KnowledgeItem, chunks: List<String>): Boolean {
        return try {
            saveChunks(context, item.id, chunks)
            val items = getItems(context).filter { it.id != item.id } + item
            saveItems(context, items)
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存知识条目失败: ${e.message}", e)
            // 回滚已落盘的 chunk 文件，避免残留
            chunkFile(context, item.id).delete()
            false
        }
    }

    /** 删除条目（元数据与 chunk 文件一并清除）。 */
    fun removeItem(context: Context, itemId: String): Boolean {
        val items = getItems(context)
        if (items.none { it.id == itemId }) return false
        saveItems(context, items.filter { it.id != itemId })
        chunkFile(context, itemId).delete()
        return true
    }

    // ===== chunk 文件读写 =====

    /** 读取条目的 chunk 列表；文件缺失或损坏返回空列表。 */
    fun loadChunks(context: Context, itemId: String): List<String> {
        val file = chunkFile(context, itemId)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "读取 chunk 文件失败 [$itemId]: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveChunks(context: Context, itemId: String, chunks: List<String>) {
        val array = JSONArray()
        chunks.forEach { array.put(it) }
        val file = chunkFile(context, itemId)
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
    }

    // ===== 内部 =====

    private fun saveItems(context: Context, items: List<KnowledgeItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        getPreferences(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun chunkFile(context: Context, itemId: String): File {
        // itemId 由本模块生成（kb_ 前缀 + 时间戳），无路径分隔符，无注入风险
        return File(File(context.filesDir, CHUNK_DIR), "$itemId.json")
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
