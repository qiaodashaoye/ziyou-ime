package com.ziyou.ime.ai.prediction

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.prediction.ContextLruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * LLM 候选缓存的磁盘持久化（联想优化方案 §4.3）。
 *
 * 职责单一：把 [ContextLruCache] 快照与 JSON 文件互转 + 原子落盘。
 * 装载时机 = 协调器首次需要缓存时（IO 线程一次性读入内存）；
 * 落盘时机 = 脏变更后由协调器在 IO 线程异步写（热路径零磁盘 IO）。
 *
 * 隐私口径：文件内容仅为「模型生成的续写候选词」与词窗口哈希键语义的
 * 词序列，不含编辑器原文；整体受 LLM 预测开关管辖，可经 [delete] 清除。
 */
object PredictionCacheStore {

    private const val TAG = "PredictionCacheStore"

    /** 缓存文件名（应用 files 目录私有存储，不对外暴露） */
    private const val FILE_NAME = "llm_prediction_cache.json"

    /** 格式版本（不兼容升级时递增，旧文件直接弃用重建） */
    private const val VERSION = 1

    /** 单文件体积上限（字节）：256 条快照正常数十 KB，超限视为损坏弃用 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /**
     * 装载缓存快照。
     *
     * @return 条目列表（最旧在前，可直接喂给 [ContextLruCache.restore]）；
     *         文件不存在/超限/格式损坏均返回空列表（降级为冷启动，不抛异常）
     */
    fun load(context: Context): List<ContextLruCache.Entry> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return try {
            if (file.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "缓存文件超限，弃用重建")
                file.delete()
                return emptyList()
            }
            parseSnapshot(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 任何解析失败都降级为冷启动：缓存是优化项不是正确性依赖
            Log.w(TAG, "缓存装载失败: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * 原子落盘缓存快照：写临时文件 → 就位替换，防写半截被下次装载读到。
     *
     * @param snapshot [ContextLruCache.snapshot] 的输出（最旧在前）
     * @return 是否成功（失败仅记日志：缓存丢失不影响功能正确性）
     */
    fun save(context: Context, snapshot: List<ContextLruCache.Entry>): Boolean = try {
        val file = file(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(formatSnapshot(snapshot), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // rename 失败（跨文件系统几乎不可能，防御）：退化为覆盖写
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "缓存落盘失败: ${e.javaClass.simpleName}")
        false
    }

    /** 删除持久化文件（设置页「清除」入口）；不存在时静默成功 */
    fun delete(context: Context) {
        try {
            file(context).delete()
        } catch (e: SecurityException) {
            Log.w(TAG, "缓存删除失败: ${e.javaClass.simpleName}")
        }
    }

    /** 缓存文件路径（filesDir 下私有文件） */
    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 序列化快照为 JSON（words 数组保持时间序，供 restore 还原访问序） */
    private fun formatSnapshot(snapshot: List<ContextLruCache.Entry>): String {
        val entries = JSONArray()
        for (entry in snapshot) {
            entries.put(
                JSONObject()
                    .put("w", JSONArray(entry.words))
                    .put("c", JSONArray(entry.candidates))
            )
        }
        return JSONObject().put("v", VERSION).put("entries", entries).toString()
    }

    /** 反序列化 JSON 为快照条目；版本不匹配或结构异常时抛异常由调用方兜底 */
    private fun parseSnapshot(text: String): List<ContextLruCache.Entry> {
        val root = JSONObject(text)
        if (root.optInt("v") != VERSION) return emptyList()
        val array = root.optJSONArray("entries") ?: return emptyList()
        val result = ArrayList<ContextLruCache.Entry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val words = item.optJSONArray("w") ?: continue
            val candidates = item.optJSONArray("c") ?: continue
            val wordList = ArrayList<String>(words.length())
            for (j in 0 until words.length()) wordList.add(words.optString(j))
            val candidateList = ArrayList<String>(candidates.length())
            for (j in 0 until candidates.length()) candidateList.add(candidates.optString(j))
            if (wordList.isNotEmpty() && candidateList.isNotEmpty()) {
                result.add(ContextLruCache.Entry(wordList, candidateList))
            }
        }
        return result
    }
}
