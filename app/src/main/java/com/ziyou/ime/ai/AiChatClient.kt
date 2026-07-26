package com.ziyou.ime.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 问答客户端
 *
 * 以 OpenAI 兼容的 chat/completions 协议向 [AiConfig] 配置的端点发起
 * 非流式问答请求。网络安全基线与 [com.ziyou.ime.dict.DictDownloader] 对齐：
 * 强制 HTTPS、连接/读取超时、响应字节数上限（端点由用户自行配置，不做域名白名单）。
 * 所有网络 IO 均切到 [Dispatchers.IO]，不阻塞 UI 线程。
 */
object AiChatClient {

    private const val TAG = "AiChatClient"
    private const val CONNECT_TIMEOUT = 15_000
    /** 生成长回答耗时较久，读取超时放宽到 60s */
    private const val READ_TIMEOUT = 60_000
    private const val BUFFER_SIZE = 8192

    /** 响应体读取上限（字节），防异常服务端返回超大响应耗尽内存 */
    private const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024

    /** 单次提问长度上限（字符），超出直接拒绝，避免误发超长内容 */
    private const val MAX_QUESTION_CHARS = 2_000

    /** 系统提示词：约束回答风格适配键盘面板小屏展示；限定 Markdown 子集
     *  与 [MarkdownRenderer] 支持范围对齐（表格/图片/HTML 无法渲染，从源头避免） */
    private const val SYSTEM_PROMPT = "你是输入法内置的AI助手，请用简体中文简明扼要地回答问题。" +
        "可使用基础 Markdown 格式（标题、粗体、斜体、列表、行内代码、代码块、引用），" +
        "不要使用表格、图片或 HTML 标签。"

    /**
     * 发起一次问答请求。
     *
     * @param question 用户问题（已 trim 非空）
     * @return 成功返回 AI 回答文本；失败返回携带用户可读消息的异常
     */
    suspend fun ask(context: Context, question: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (question.length > MAX_QUESTION_CHARS) {
                return@withContext Result.failure(
                    IOException("问题过长（上限 $MAX_QUESTION_CHARS 字）")
                )
            }
            val apiKey = AiConfig.getApiKey(context)
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("未配置 API Key，请在设置页完成 AI 服务配置"))
            }
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(AiConfig.getApiUrl(context), apiKey)
                connection.outputStream.use { output ->
                    output.write(buildRequestBody(AiConfig.getModel(context), question))
                    output.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.use { readBoundedText(it) }.orEmpty()
                    Log.e(TAG, "AI 请求失败 HTTP $responseCode: ${errorBody.take(200)}")
                    return@withContext Result.failure(
                        IOException(friendlyHttpError(responseCode))
                    )
                }

                val body = connection.inputStream.use { readBoundedText(it) }
                val answer = parseAnswer(body)
                if (answer.isNullOrBlank()) {
                    Result.failure(IOException("AI 服务返回内容为空"))
                } else {
                    Result.success(answer.trim())
                }
            } catch (e: IOException) {
                Log.e(TAG, "AI 请求网络异常: ${e.message}", e)
                Result.failure(IOException(e.message ?: "网络异常，请检查网络后重试"))
            } catch (e: Exception) {
                Log.e(TAG, "AI 请求异常: ${e.message}", e)
                Result.failure(IOException("请求失败：${e.message ?: "未知错误"}"))
            } finally {
                connection?.disconnect()
            }
        }

    /** 打开可信连接：强制 HTTPS + 超时 + Bearer 鉴权头。 */
    private fun openConnection(spec: String, apiKey: String): HttpURLConnection {
        val url = URL(spec)
        if (url.protocol != "https") throw IOException("仅允许 HTTPS 的 AI 服务地址")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }

    /** 组装 OpenAI 兼容的 chat/completions 请求体（非流式）。 */
    private fun buildRequestBody(model: String, question: String): ByteArray {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", question))
            )
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** 解析响应中的回答文本：choices[0].message.content。 */
    private fun parseAnswer(body: String): String? {
        return try {
            JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
        } catch (e: Exception) {
            Log.e(TAG, "解析 AI 响应失败: ${e.message}")
            null
        }
    }

    /** HTTP 错误码转用户可读提示。 */
    private fun friendlyHttpError(code: Int): String = when (code) {
        401, 403 -> "鉴权失败，请检查 API Key 是否正确"
        429 -> "请求过于频繁或额度不足，请稍后再试"
        in 500..599 -> "AI 服务暂时不可用（HTTP $code），请稍后再试"
        else -> "请求失败（HTTP $code）"
    }

    /** 读取流为 UTF-8 文本，超出 [MAX_RESPONSE_BYTES] 抛异常。 */
    private fun readBoundedText(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw IOException("AI 响应超限（上限 ${MAX_RESPONSE_BYTES / 1024}KB）")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
