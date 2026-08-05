package com.ziyou.ime.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 语音模型文件下载器（安全基线对齐 DictDownloader：
 * 强制 HTTPS + 域名白名单 + 禁用自动重定向逐跳复验 + 大小上限）。
 *
 * 下载源：HuggingFace 官方仓库及其国内镜像 hf-mirror.com。
 * 重定向链实测（2026-08）：resolve 入口 307 → 同域 resolve-cache →
 * 302 → HF 文件 CDN（us.aws.cdn.hf.co）。白名单必须同时包含入口域与
 * CDN 域，否则第一跳即被拒（安全拒绝与网络故障的日志分开记录）。
 *
 * 模型清单硬编码于 [VoiceModelCatalog]（非远程目录），URL 无外部注入面；
 * 白名单仍作为纵深防御保留。
 */
object VoiceModelDownloader {

    private const val TAG = "VoiceModelDownloader"
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_REDIRECTS = 6

    /** 单个模型文件下载上限（标准档 encoder 约 160MB，留足冗余防磁盘耗尽） */
    private const val MAX_FILE_BYTES = 400L * 1024 * 1024

    /** 下载源域名白名单：入口域（镜像/官方）+ LFS/文件 CDN 域 */
    private val ALLOWED_HOSTS = setOf(
        "hf-mirror.com",
        "huggingface.co",
        "cdn-lfs.huggingface.co",
        "cdn-lfs-us-1.huggingface.co",
        "us.aws.cdn.hf.co",
    )

    /** 下载入口基础 URL（HF 国内镜像；官方源直连不稳定，与 fetch 脚本同一决策） */
    private const val BASE_URL = "https://hf-mirror.com"

    /**
     * 下载单个模型文件到目标路径（先写 .part 再重命名，中断不残留半成品）。
     * 边下载边流式计算 sha256，完成后与锚定值比对，不匹配即删除拒绝
     *（防镜像投毒/CDN 脏缓存/服务端提前关流的截断文件）。
     *
     * @param repoPath HF 仓库路径（如 "csukuangfj/xxx"）
     * @param fileName 仓库内文件名
     * @param target   目标文件（父目录需已存在）
     * @param expectedSha256 锚定哈希（小写十六进制）
     * @param onProgress 进度回调 (已下载字节, 总字节；总字节未知时为 -1)
     * @return null 成功；否则失败原因
     */
    suspend fun downloadFile(
        repoPath: String,
        fileName: String,
        target: File,
        expectedSha256: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val spec = "$BASE_URL/$repoPath/resolve/main/$fileName"
        val part = File(target.parentFile, "${target.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = openTrustedConnection(spec)
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载 $fileName 失败，HTTP $responseCode")
                return@withContext "下载失败(HTTP $responseCode)"
            }
            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        // 边读边卡上限：contentLength 不可信，以实际字节数为准
                        if (downloaded > MAX_FILE_BYTES) {
                            throw IOException("文件超限（上限 ${MAX_FILE_BYTES / (1024 * 1024)}MB）")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress?.invoke(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
            // 完整性校验：哈希不匹配即删除拒绝，绝不让脏文件落盘
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "$fileName sha256 不匹配（期望 $expectedSha256，实际 $actual）")
                part.delete()
                return@withContext "文件校验失败（sha256 不匹配），请重试"
            }
            // .part → 目标名：原子化完成标记，避免中断残留被当作完整文件
            if (target.exists() && !target.delete()) {
                return@withContext "旧文件删除失败: ${target.name}"
            }
            if (!part.renameTo(target)) {
                return@withContext "文件落盘失败: ${target.name}"
            }
            null
        } catch (e: IOException) {
            Log.e(TAG, "下载 $fileName 网络异常: ${e.message}", e)
            part.delete()
            "网络异常: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "下载 $fileName 异常: ${e.message}", e)
            part.delete()
            "下载异常: ${e.message}"
        } finally {
            connection?.disconnect()
        }
    }

    /** 流式计算文件 sha256（小写十六进制）；读失败返回 null。 */
    fun sha256Of(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "计算 sha256 失败 ${file.name}: ${e.message}")
            null
        }
    }

    /** 白名单校验：HTTPS 强制 + host 命中；拒绝时日志打印具体 host 便于定位 */
    private fun requireTrustedUrl(spec: String): URL {
        val url = URL(spec)
        if (url.protocol != "https") throw IOException("仅允许 HTTPS 下载源: $spec")
        val host = url.host?.lowercase(Locale.ROOT)
        if (host !in ALLOWED_HOSTS) {
            Log.w(TAG, "下载源域名不在白名单，拒绝: $host")
            throw IOException("下载源域名不在白名单: $host")
        }
        return url
    }

    /**
     * 打开可信连接：禁用自动重定向，手动跟随并对每一跳重新白名单校验，
     * 防止白名单域经 3xx 跳到任意外域（与 DictDownloader 同一套路）。
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
            url = requireTrustedUrl(URL(url, location).toString())
        }
        throw IOException("重定向次数超限（>$MAX_REDIRECTS）")
    }
}
