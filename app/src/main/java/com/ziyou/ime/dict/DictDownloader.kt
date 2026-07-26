package com.ziyou.ime.dict

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

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

    /** 下载源域名白名单（与 BASE_URL 同源）：catalog 中的 url 必须命中，
     *  防目录被篡改后外链投毒（与技能 fetch 代理的白名单基线对齐） */
    private val ALLOWED_HOSTS = setOf("gitee.com")

    /** catalog.json 响应上限（字节），超出即拒绝（目录必须完整才能解析） */
    private const val MAX_CATALOG_BYTES = 1L * 1024 * 1024

    /** 单个词库文件下载上限（字节），防恶意 catalog 自报小 size 实际超大文件耗尽磁盘 */
    private const val MAX_DICT_BYTES = 20L * 1024 * 1024

    /** 远程预览读取上限（字节），超出即截断（预览只需前 N 条词条） */
    private const val MAX_PREVIEW_BYTES = 1L * 1024 * 1024

    /** 手动跟随重定向的最大跳数（每跳都重新过白名单，防 3xx 逃逸到外域） */
    private const val MAX_REDIRECTS = 3

    /**
     * 拉取远程词库目录
     * @return 解析后的 DictCatalog，失败返回 null
     */
    suspend fun fetchCatalog(): DictCatalog? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = openTrustedConnection("$BASE_URL/catalog.json")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "拉取 catalog 失败，HTTP $responseCode")
                return@withContext null
            }

            val jsonStr = connection.inputStream.use {
                readBoundedText(it, MAX_CATALOG_BYTES, truncate = false)
            }
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
        // 防御性校验：id 会拼接为文件名，非法 id（含路径分隔符 / ".."）可能写到目录外。
        if (!RemoteDictInfo.isValidId(info.id)) {
            Log.e(TAG, "非法词库 id，拒绝下载: ${info.id}")
            return@withContext null
        }
        var connection: HttpURLConnection? = null
        val targetFile = File(targetDir, "${info.id}.dict.yaml")
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // url 来自不可信的 catalog：强制 HTTPS + 域名白名单 + 受控重定向
            connection = openTrustedConnection(info.url)

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
                        downloadedBytes += bytesRead
                        // 边读边卡上限：contentLength/catalog.size 均不可信，以实际字节数为准
                        if (downloadedBytes > MAX_DICT_BYTES) {
                            throw IOException("词库文件超限（上限 ${MAX_DICT_BYTES / (1024 * 1024)}MB）")
                        }
                        output.write(buffer, 0, bytesRead)
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            // 完整性校验：catalog 提供 sha256 时校验下载内容，不匹配则删除并拒绝安装，
            // 防止镜像被投毒 / 传输被篡改的词库注入主词库。为空则跳过（向后兼容旧 catalog）。
            if (info.sha256.isNotEmpty()) {
                val actual = sha256Of(targetFile)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    Log.e(TAG, "词库 ${info.id} SHA-256 校验失败：期望 ${info.sha256}，实际 $actual")
                    targetFile.delete()
                    return@withContext null
                }
                Log.i(TAG, "词库 ${info.id} SHA-256 校验通过")
            } else {
                Log.w(TAG, "词库 ${info.id} 未提供 SHA-256，跳过完整性校验")
            }

            Log.i(TAG, "词库 ${info.id} 下载完成: ${targetFile.absolutePath} ($downloadedBytes bytes)")
            targetFile
        } catch (e: IOException) {
            Log.e(TAG, "下载词库 ${info.id} 网络异常: ${e.message}", e)
            targetFile.delete()  // 清理半成品，避免残留损坏文件被后续误用
            null
        } catch (e: Exception) {
            Log.e(TAG, "下载词库 ${info.id} 异常: ${e.message}", e)
            targetFile.delete()
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
            // 预览同样受白名单与重定向管控；读取超限即截断（只需前 N 条词条）
            connection = openTrustedConnection(info.url)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "预览词库 ${info.id} 失败，HTTP $responseCode")
                return@withContext null
            }

            val content = connection.inputStream.use {
                readBoundedText(it, MAX_PREVIEW_BYTES, truncate = true)
            }
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

    /** 计算文件的 SHA-256（十六进制小写字符串）。 */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ===== 可信连接（HTTPS + 白名单 + 受控重定向）=====

    /** 校验 URL 可信：强制 HTTPS 且域名命中白名单（小写归一）。 */
    private fun requireTrustedUrl(spec: String): URL {
        val url = URL(spec)
        if (url.protocol != "https") throw IOException("仅允许 HTTPS 下载源: $spec")
        val host = url.host?.lowercase(Locale.ROOT)
        if (host !in ALLOWED_HOSTS) throw IOException("下载源域名不在白名单: $host")
        return url
    }

    /**
     * 打开可信连接：禁用自动重定向，手动跟随并对每一跳重新执行白名单校验，
     * 防止白名单域经 3xx 跳转到任意外域。返回已取得最终响应码的连接，
     * 调用方负责检查响应码与 disconnect。
     */
    private fun openTrustedConnection(spec: String): HttpURLConnection {
        var url = requireTrustedUrl(spec)
        repeat(MAX_REDIRECTS + 1) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrEmpty()) throw IOException("重定向缺少 Location 头")
            // 相对重定向按当前 URL 解析，绝对重定向重新过白名单
            url = requireTrustedUrl(URL(url, location).toString())
        }
        throw IOException("重定向次数超限（>$MAX_REDIRECTS）")
    }

    /**
     * 读取流为 UTF-8 文本，超出 [maxBytes] 时：[truncate]=true 截断返回（预览场景），
     * 否则抛 [IOException]（catalog 必须完整才能解析）。
     */
    private fun readBoundedText(input: InputStream, maxBytes: Long, truncate: Boolean): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                if (truncate) {
                    val keep = read - (total - maxBytes).toInt()
                    if (keep > 0) buffer.write(chunk, 0, keep)
                    return buffer.toString(Charsets.UTF_8.name())
                }
                throw IOException("响应超限（上限 $maxBytes 字节）")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
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
                val id = obj.getString("id")
                // 在不可信边界（远程 catalog）直接丢弃非法 id，确保其永不进入安装记录与文件系统。
                if (!RemoteDictInfo.isValidId(id)) {
                    Log.w(TAG, "跳过非法词库 id: $id")
                    continue
                }
                // 同理丢弃不可信下载源（非 HTTPS / 域名不在白名单），不进入展示列表
                val dictUrl = obj.getString("url")
                val trusted = runCatching { requireTrustedUrl(dictUrl) }.isSuccess
                if (!trusted) {
                    Log.w(TAG, "跳过不可信下载源的词库 $id: $dictUrl")
                    continue
                }
                dictionaries.add(
                    RemoteDictInfo(
                        id = id,
                        name = obj.getString("name"),
                        category = obj.optString("category", "professional"),
                        description = obj.optString("description", ""),
                        version = obj.optString("version", "1.0.0"),
                        url = dictUrl,
                        size = obj.optLong("size", 0),
                        author = obj.optString("author", ""),
                        sha256 = obj.optString("sha256", "")
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
