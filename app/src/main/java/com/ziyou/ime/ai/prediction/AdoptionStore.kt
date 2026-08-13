package com.ziyou.ime.ai.prediction

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.prediction.AdoptionRecord
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 采纳词对攒批的磁盘持久化（联想优化方案 §4.6 形态 B）。
 *
 * 数据仅用于离线构建期固化（scripts/build_predict_db.py 经 adb pull
 * 取走合并进自建 predict.db 语料），运行时不参与任何排序。
 * 落盘时机由协调器防抖调度（IO 线程），热路径仅内存计数。
 *
 * 隐私口径：仅 1~4 字纯汉字词对计数（[AdoptionRecord.isLearnableWord]
 * 强制过滤），不含语句上下文；受 LLM 预测开关管辖，可经 [delete] 清除。
 */
object AdoptionStore {

    private const val TAG = "AdoptionStore"

    /** 攒批文件名（应用 files 目录私有存储） */
    private const val FILE_NAME = "prediction_adoptions.json"

    /** 单文件体积上限（字节）：500×8 词对正常数十 KB，超限视为损坏弃用 */
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024

    /**
     * 装载攒批数据。
     *
     * @return prev → (next → count)；文件不存在/超限/损坏均返回空 map
     */
    fun load(context: Context): Map<String, Map<String, Int>> {
        val file = file(context)
        if (!file.exists()) return emptyMap()
        return try {
            if (file.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "攒批文件超限，弃用重建")
                file.delete()
                return emptyMap()
            }
            parsePairs(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "攒批装载失败: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    /**
     * 原子落盘全量攒批快照（写临时文件 → 就位替换）。
     *
     * @param data [AdoptionRecord.snapshot] 的输出
     * @return 是否成功（失败仅记日志：攒批丢失只影响个性化迭代节奏）
     */
    fun save(context: Context, data: Map<String, Map<String, Int>>): Boolean = try {
        val file = file(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(formatPairs(data), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "攒批落盘失败: ${e.javaClass.simpleName}")
        false
    }

    /** 删除攒批文件（设置页「清除」入口）；不存在时静默成功 */
    fun delete(context: Context) {
        try {
            file(context).delete()
        } catch (e: SecurityException) {
            Log.w(TAG, "攒批删除失败: ${e.javaClass.simpleName}")
        }
    }

    /** 攒批文件路径（构建脚本经 adb pull 此路径取数据） */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 序列化词对计数为 JSON：{"prev":{"next":count,...},...} */
    private fun formatPairs(data: Map<String, Map<String, Int>>): String {
        val root = JSONObject()
        for ((prev, tails) in data) {
            val tailJson = JSONObject()
            for ((next, count) in tails) tailJson.put(next, count)
            root.put(prev, tailJson)
        }
        return root.toString()
    }

    /** 反序列化 JSON 为词对计数；结构异常抛异常由调用方兜底为空 */
    private fun parsePairs(text: String): Map<String, Map<String, Int>> {
        val root = JSONObject(text)
        val result = LinkedHashMap<String, Map<String, Int>>()
        for (prev in root.keys()) {
            val tails = root.optJSONObject(prev) ?: continue
            val tailMap = LinkedHashMap<String, Int>()
            for (next in tails.keys()) tailMap[next] = tails.optInt(next)
            if (tailMap.isNotEmpty()) result[prev] = tailMap
        }
        return result
    }
}
