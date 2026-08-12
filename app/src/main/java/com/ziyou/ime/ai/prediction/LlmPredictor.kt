package com.ziyou.ime.ai.prediction

import android.content.Context
import android.util.Log
import com.ziyou.ime.ai.AiConfig
import kotlinx.coroutines.CancellationException
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
 * LLM 续写预测薄 HTTP 客户端。
 *
 * 以 OpenAI 兼容 chat/completions 协议向 [AiConfig] 配置的端点发起
 * 非流式续写请求。安全基线对齐 [com.ziyou.ime.ai.AiChatClient]：
 * 强制 HTTPS、连接/读取超时、响应字节数上限（端点由用户自行配置，
 * 不做域名白名单）；但超时大幅收紧（预测是旁路增强，不可拖慢输入）。
 *
 * 隐私红线：日志中禁止出现用户词内容——本类全部日志仅含状态码/通用描述。
 */
object LlmPredictor {

    private const val TAG = "LlmPredictor"

    /** 预测请求超时（ms）：远小于问答的 15s/60s，过期结果不如没有 */
    private const val CONNECT_TIMEOUT = 3_000
    private const val READ_TIMEOUT = 5_000

    private const val BUFFER_SIZE = 4096

    /** 响应体读取上限（字节）：max_tokens=30 的响应远小于此，纯防御 */
    private const val MAX_RESPONSE_BYTES = 256L * 1024

    /** 单次请求返回的候选条数上限 */
    private const val MAX_CANDIDATES = 5

    /** 单条候选字符数上限（超出截断，防异常输出撑爆候选栏） */
    private const val MAX_CANDIDATE_CHARS = 20

    /** 续写专用 system prompt：约束每条候选单独一行、保留句尾标点（句读完整性） */
    private const val SYSTEM_PROMPT =
        "你是输入法续写助手。根据用户最近上屏的词语，预测接下来最可能输入的内容。" +
            "输出最多5条候选，每条单独一行；每条候选是一个完整的词、短语或句子片段，" +
            "可以自然的句号、问号等句尾标点结尾，但不要以逗号等标点开头；" +
            "多条候选必须是不同的续写内容，禁止输出仅标点符号不同的重复项；" +
            "不要编号、解释或任何其他内容。"

    /** 请求采样参数：收紧 token 上限控费用，temperature 保留少量多样性 */
    private const val MAX_TOKENS = 30
    private const val TEMPERATURE = 0.7

    /**
     * 发起一次续写预测请求。
     *
     * @param contextWords 最近上屏词序列（时间序，唯一数据源；
     *        架构禁令：不得传入编辑器全文，见可行性方案 §4.6）
     * @return 成功返回候选片段列表（≤ [MAX_CANDIDATES] 条，每条 ≤ [MAX_CANDIDATE_CHARS] 字符）；
     *         任何异常（网络/HTTP/解析）均收敛为 Result.failure
     */
    suspend fun predict(context: Context, contextWords: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (contextWords.isEmpty()) {
                return@withContext Result.failure(IOException("上下文词窗口为空"))
            }
            val apiKey = AiConfig.getApiKey(context)
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("未配置 API Key"))
            }
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(AiConfig.getApiUrl(context), apiKey)
                connection.outputStream.use { output ->
                    output.write(buildRequestBody(AiConfig.getModel(context), contextWords))
                    output.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // 日志只记状态码：errorStream 可能回显请求内容（含用户词），不读取不落日志
                    Log.w(TAG, "LLM 预测请求失败 HTTP $responseCode")
                    return@withContext Result.failure(IOException("HTTP $responseCode"))
                }

                val body = connection.inputStream.use { readBoundedText(it) }
                val candidates = parseCandidates(body)
                if (candidates.isEmpty()) {
                    Result.failure(IOException("LLM 预测返回内容为空"))
                } else {
                    Result.success(candidates)
                }
            } catch (e: IOException) {
                Log.w(TAG, "LLM 预测网络异常: ${e.javaClass.simpleName}")
                Result.failure(e)
            } catch (e: CancellationException) {
                // 结构化并发：取消必须透传，不得收敛为业务失败
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LLM 预测异常: ${e.javaClass.simpleName}")
                Result.failure(IOException("LLM 预测请求失败"))
            } finally {
                connection?.disconnect()
            }
        }

    /** 打开可信连接：强制 HTTPS + 收紧超时 + Bearer 鉴权头。 */
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

    /** 组装 OpenAI 兼容的 chat/completions 请求体（非流式，max_tokens 收紧）。 */
    private fun buildRequestBody(model: String, contextWords: List<String>): ByteArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", "最近输入：${contextWords.joinToString("")}"))
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", TEMPERATURE)
            .put("messages", messages)
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** 列表序号前缀（模型未必遵守 prompt 的无编号约束，防御性剥离） */
    private val LIST_MARKER_REGEX = Regex("^(?:[0-9]+|[一二三四五六七八九十]+)[.、)）]\\s*|^[①②③④⑤⑥⑦⑧⑨⑩]\\s*")

    /**
     * 解析响应为候选片段：choices[0].message.content 经 trim 后**仅按换行切分**
     *（prompt 已约束每条候选单独一行；不得再按逗号/顿号切分——那会把
     * 「疑是地上霜，低头思故乡。」拆成无标点的碎片，丢失句读），剥离列表
     * 序号前缀与**前导标点**后取非空行，每条截断到 [MAX_CANDIDATE_CHARS]，
     * 最多 [MAX_CANDIDATES] 条。
     *
     * 前导标点必须剥离：前文与续写词之间的标点由 AutoPunctPolicy 统一判定插入，
     * 候选若自带前导逗号，遇到前文已有标点时会造成「，，」叠加；句尾标点
     *（如「低头思故乡。」）有实义不剥离。
     */
    private fun parseCandidates(body: String): List<String> {
        val content = JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: return emptyList()
        return content.trim()
            .split('\n', '\r')
            .map { LIST_MARKER_REGEX.replace(it.trim(), "").trim() }
            .map { it.dropWhile { c -> !c.isLetterOrDigit() }.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_CANDIDATE_CHARS) }
            .take(MAX_CANDIDATES)
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
                throw IOException("LLM 响应超限（上限 ${MAX_RESPONSE_BYTES / 1024}KB）")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
