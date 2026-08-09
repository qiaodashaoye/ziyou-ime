package com.ziyou.ime.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 蒲公英更新检测网络层
 *
 * 调用蒲公英 API 2.0「检测更新」接口（POST /apiv2/app/check），
 * 返回最新版本元数据；版本新旧判定由调用方（AppUpdateManager）结合本地版本号完成，
 * 本层只负责「取数据 + 解析」，不做业务判断。
 *
 * 与 DictDownloader 一致使用 HttpURLConnection（项目未引入 OkHttp），全程 IO 线程。
 */
object PgyerUpdateChecker {

    private const val TAG = "PgyerUpdateChecker"

    /**
     * 检测更新。
     * @param currentBuildVersion 当前构建版本号（buildBuildVersion 口径，可选传入辅助服务端判断）
     * @return 远端最新版本信息；解析失败或接口报错返回 null（原因见日志）
     */
    suspend fun checkUpdate(currentBuildVersion: String): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(UpdateConfig.CHECK_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                connection.readTimeout = UpdateConfig.READ_TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                // 蒲公英 API 2.0 以表单方式传参：_api_key 鉴权 + appKey 定位应用
                val body = buildString {
                    append("_api_key=").append(encode(UpdateConfig.PGYER_API_KEY))
                    append("&appKey=").append(encode(UpdateConfig.PGYER_APP_KEY))
                    if (currentBuildVersion.isNotBlank()) {
                        append("&buildBuildVersion=").append(encode(currentBuildVersion))
                    }
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "检测更新失败，HTTP $responseCode")
                    return@withContext null
                }

                val jsonStr = connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(UpdateConfig.DOWNLOAD_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        // 版本元数据不应过大，超限即拒绝（防异常响应）
                        if (total > UpdateConfig.MAX_CHECK_RESPONSE_BYTES) {
                            throw IOException("检查更新响应超限")
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toString(Charsets.UTF_8.name())
                }
                parseResponse(jsonStr)
            } catch (e: IOException) {
                Log.e(TAG, "检测更新网络异常: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "检测更新解析异常: ${e.message}", e)
                null
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * 解析检测更新响应：
     * {"code":0,"message":"","data":{"buildVersion":"1.1.0","buildVersionNo":"2",
     *  "buildBuildVersion":"4","buildUpdateDescription":"...","downloadURL":"...",...}}
     *
     * code != 0（如 1002 API Key 错误 / 1098 限频）或 data 缺失均视为失败。
     */
    private fun parseResponse(jsonStr: String): AppUpdateInfo? {
        return try {
            val root = JSONObject(jsonStr)
            val code = root.optInt("code", -1)
            if (code != 0) {
                Log.e(TAG, "蒲公英接口错误 code=$code, message=${root.optString("message")}")
                return null
            }
            val data = root.optJSONObject("data")
            if (data == null) {
                Log.w(TAG, "蒲公英响应缺少 data（应用可能无已发布版本）")
                return null
            }

            // downloadURL 缺失时回退到短链下载页（https://www.pgyer.com/<shortcutUrl>）
            var downloadUrl = data.optString("downloadURL", "")
            if (downloadUrl.isBlank()) {
                val shortcut = data.optString("buildShortcutUrl", "")
                if (shortcut.isNotBlank()) downloadUrl = "https://www.pgyer.com/$shortcut"
            }
            if (downloadUrl.isBlank() || !downloadUrl.startsWith("https://")) {
                Log.e(TAG, "蒲公英响应缺少可信下载地址: $downloadUrl")
                return null
            }

            AppUpdateInfo(
                versionName = data.optString("buildVersion", ""),
                versionNo = data.optString("buildVersionNo", "").toLongOrNull() ?: 0L,
                buildVersion = data.optString("buildBuildVersion", ""),
                updateDescription = data.optString("buildUpdateDescription", ""),
                downloadUrl = downloadUrl,
                appName = data.optString("buildName", ""),
                fileSizeBytes = data.optLong("buildFileSize", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析蒲公英响应失败: ${e.message}", e)
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
