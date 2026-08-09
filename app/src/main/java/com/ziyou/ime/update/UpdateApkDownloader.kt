package com.ziyou.ime.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新 APK 下载器
 *
 * 安全基线（与 DictDownloader 的可信连接思路一致，按 APK 场景裁剪）：
 * - 强制 HTTPS：初始地址与各跳重定向均必须为 HTTPS（下载地址来自鉴权过的
 *   蒲公英 API 响应，蒲公英 CDN 域名动态分配，故不做固定白名单，只卡协议）；
 * - 受控重定向：关闭自动跟随，手动逐跳跟随并重新校验协议，防 3xx 逃逸到 HTTP；
 * - 体积上限：边读边卡 [UpdateConfig.MAX_APK_BYTES]，contentLength 不可信；
 * - 原子就位：先写 .part 临时文件，完整落盘后 rename，失败清理半成品。
 */
object UpdateApkDownloader {

    private const val TAG = "UpdateApkDownloader"

    /** APK 下载目录（cache/update_apk/，经 FileProvider 暴露给系统安装器） */
    fun apkDir(context: Context): File = File(context.cacheDir, "update_apk")

    /** 已下载完成的 APK 文件（不存在表示未下载） */
    fun downloadedApk(context: Context): File = File(apkDir(context), "ziyou-update.apk")

    /**
     * 下载更新 APK。
     * @param url 下载地址（必须 HTTPS）
     * @param onProgress 进度回调 (已下载字节, 总字节)；总字节未知时为 -1，在 IO 线程回调
     * @return 下载完成的 APK 文件；失败或取消（协程中断）返回 null
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val targetFile = downloadedApk(context)
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        try {
            // 已存在同版本安装包时直接复用（重复点「立即更新」不重复下载）
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "复用已下载的安装包: ${targetFile.absolutePath}")
                return@withContext targetFile
            }

            apkDir(context).mkdirs()
            partFile.delete()

            connection = openHttpsConnection(url)
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载 APK 失败，HTTP $responseCode")
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(UpdateConfig.DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        downloadedBytes += bytesRead
                        if (downloadedBytes > UpdateConfig.MAX_APK_BYTES) {
                            throw IOException("APK 超限（上限 ${UpdateConfig.MAX_APK_BYTES / (1024 * 1024)}MB）")
                        }
                        output.write(buffer, 0, bytesRead)
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            // 原子就位：完整落盘后才替换目标文件
            targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                Log.e(TAG, "APK 就位失败（rename）")
                return@withContext null
            }
            Log.i(TAG, "APK 下载完成: ${targetFile.absolutePath} ($downloadedBytes bytes)")
            targetFile
        } catch (e: IOException) {
            Log.e(TAG, "下载 APK 网络异常: ${e.message}", e)
            partFile.delete()  // 清理半成品，避免残留损坏文件被后续误装
            null
        } catch (e: Exception) {
            Log.e(TAG, "下载 APK 异常: ${e.message}", e)
            partFile.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 打开 HTTPS 连接：禁用自动重定向，手动跟随并对每一跳重新校验 HTTPS，
     * 防止经 3xx 逃逸到明文 HTTP。返回已取得最终响应码的连接，
     * 调用方负责检查响应码与 disconnect。
     */
    private fun openHttpsConnection(spec: String): HttpURLConnection {
        var url = requireHttpsUrl(spec)
        repeat(UpdateConfig.MAX_REDIRECTS + 1) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = UpdateConfig.READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrEmpty()) throw IOException("重定向缺少 Location 头")
            url = requireHttpsUrl(URL(url, location).toString())
        }
        throw IOException("重定向次数超限（>${UpdateConfig.MAX_REDIRECTS}）")
    }

    /** 校验 URL 为 HTTPS，否则抛异常 */
    private fun requireHttpsUrl(spec: String): URL {
        val url = URL(spec)
        if (url.protocol != "https") throw IOException("仅允许 HTTPS 下载源: $spec")
        if (url.host.isNullOrBlank()) throw IOException("下载地址缺少域名: $spec")
        return url
    }
}
