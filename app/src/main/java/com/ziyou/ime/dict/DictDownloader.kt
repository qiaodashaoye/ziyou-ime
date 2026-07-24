package com.ziyou.ime.dict

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 词库下载器
 * 负责从 GitHub 仓库下载词库索引和词库文件
 */
object DictDownloader {

    private const val TAG = "DictDownloader"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER_SIZE = 8192

    /** Gitee 仓库原始文件基础 URL */
    private const val BASE_URL = "https://gitee.com/qiaodashaoye/ziyou-ime-dicts/raw/main"

    /**
     * 拉取远程词库目录
     * @return 解析后的 DictCatalog，失败返回 null
     */
    suspend fun fetchCatalog(): DictCatalog? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/catalog.json")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "拉取 catalog 失败，HTTP $responseCode")
                return@withContext null
            }

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            parseCatalog(jsonStr)
        } catch (e: IOException) {
            Log.e(TAG, "拉取 catalog 网络异常: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "解析 catalog 异常: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 下载词库文件到指定目录
     * @param info 远程词库信息
     * @param targetDir 目标目录（ext_dicts/）
     * @param onProgress 进度回调 (downloadedBytes, totalBytes)
     * @return 下载后的文件，失败返回 null
     */
    suspend fun downloadDict(
        info: RemoteDictInfo,
        targetDir: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, "${info.id}.dict.yaml")
            val url = URL(info.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载词库 ${info.id} 失败，HTTP $responseCode")
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong.let {
                if (it > 0) it else info.size
            }
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "词库 ${info.id} 下载完成: ${targetFile.absolutePath} ($downloadedBytes bytes)")
            targetFile
        } catch (e: IOException) {
            Log.e(TAG, "下载词库 ${info.id} 网络异常: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "下载词库 ${info.id} 异常: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 获取远程词库预览（下载文件内容并解析前 N 条词条）
     * @param info 远程词库信息
     * @param maxEntries 最大预览词条数
     * @return 预览数据，失败返回 null
     */
    suspend fun fetchDictPreview(info: RemoteDictInfo, maxEntries: Int = 50): DictPreview? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(info.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "预览词库 ${info.id} 失败，HTTP $responseCode")
                return@withContext null
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            val entries = parseDictEntries(content, maxEntries)
            val totalHint = countDictEntries(content)

            DictPreview(
                dictInfo = info,
                entries = entries,
                totalEntriesHint = totalHint
            )
        } catch (e: IOException) {
            Log.e(TAG, "预览词库 ${info.id} 网络异常: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "预览词库 ${info.id} 异常: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析 RIME dict.yaml 内容，提取词条（跳过注释和 YAML 头部）
     * 词条格式：词语\t编码（Tab 分隔）
     */
    private fun parseDictEntries(content: String, maxEntries: Int): List<DictEntry> {
        val entries = mutableListOf<DictEntry>()
        var inBody = false

        for (line in content.lineSequence()) {
            // 检测 YAML 文档体开始标记 "..."
            if (!inBody) {
                if (line.trim() == "...") {
                    inBody = true
                }
                continue
            }

            // 跳过空行和注释
            if (line.isBlank() || line.startsWith("#")) continue

            // 解析 Tab 分隔的词条：词语\t编码
            val parts = line.split("\t")
            if (parts.size >= 2) {
                entries.add(DictEntry(word = parts[0], code = parts[1]))
                if (entries.size >= maxEntries) break
            }
        }
        return entries
    }

    /** 粗略统计词条总数（用于预览提示） */
    private fun countDictEntries(content: String): Int {
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

    /** 解析 catalog.json 内容 */
    private fun parseCatalog(jsonStr: String): DictCatalog? {
        return try {
            val root = JSONObject(jsonStr)
            val version = root.optInt("version", 1)
            val dictArray = root.optJSONArray("dictionaries") ?: JSONArray()

            val dictionaries = mutableListOf<RemoteDictInfo>()
            for (i in 0 until dictArray.length()) {
                val obj = dictArray.getJSONObject(i)
                dictionaries.add(
                    RemoteDictInfo(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.optString("category", "professional"),
                        description = obj.optString("description", ""),
                        version = obj.optString("version", "1.0.0"),
                        url = obj.getString("url"),
                        size = obj.optLong("size", 0),
                        author = obj.optString("author", "")
                    )
                )
            }

            DictCatalog(version = version, dictionaries = dictionaries)
        } catch (e: Exception) {
            Log.e(TAG, "解析 catalog JSON 失败: ${e.message}", e)
            null
        }
    }
}
