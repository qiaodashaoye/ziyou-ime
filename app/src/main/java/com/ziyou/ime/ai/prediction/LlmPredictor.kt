package com.ziyou.ime.ai.prediction

import android.content.Context
import android.util.Log
import com.ziyou.ime.ai.AiConfig
import com.ziyou.ime.core.prediction.StreamCandidateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM 续写预测薄 HTTP 客户端。
 *
 * 以 OpenAI 兼容 chat/completions 协议向 [AiConfig] 配置的端点发起
 * 续写请求，支持非流式（[predict]）与 SSE 流式（[predictStream]，
 * 联想优化方案 §4.7：首条候选感知延迟从整包返回降至首行到达）。
 * 安全基线对齐 [com.ziyou.ime.ai.AiChatClient]：强制 HTTPS、连接/读取
 * 超时、响应字节数上限（端点由用户自行配置，不做域名白名单）；但超时
 * 大幅收紧（预测是旁路增强，不可拖慢输入）。
 *
 * 隐私红线：日志中禁止出现用户词内容——本类全部日志仅含状态码/通用描述。
 */
object LlmPredictor {

    private const val TAG = "LlmPredictor"

    /** 预测请求超时（ms）：远小于问答的 15s/60s，过期结果不如没有 */
    private const val CONNECT_TIMEOUT = 3_000

    /** 非流式读取超时（ms）：整包等待，超期不如放弃 */
    private const val READ_TIMEOUT = 5_000

    /**
     * 流式读取超时（ms）：作用于相邻数据块之间的等待。
     * 流式首 token 通常数百 ms 内到达，但小模型/高负载端点可能更慢；
     * 总时长另有 epoch 作废与单 Job 取消兑底，不会无限挂住。
     */
    private const val STREAM_READ_TIMEOUT = 10_000

    private const val BUFFER_SIZE = 4096

    /** 响应体读取上限（字节）：候选总量极小，纯防御（流式累计同样适用） */
    private const val MAX_RESPONSE_BYTES = 256L * 1024

    /** 单条候选字符数上限（超出截断，防异常输出撑爆候选栏） */
    private const val MAX_CANDIDATE_CHARS = StreamCandidateText.MAX_CANDIDATE_CHARS

    /** 续写专用 system prompt：约束每条候选单独一行、保留句尾标点（句读完整性） */
    private const val SYSTEM_PROMPT =
        "你是输入法续写助手。根据用户最近上屏的词语，预测接下来最可能输入的内容。" +
            "输出最多5条候选，每条单独一行；每条候选是一个完整的词、短语或句子片段，" +
            "可以自然的句号、问号等句尾标点结尾，但不要以逗号等标点开头；" +
            "多条候选必须是不同的续写内容，禁止输出仅标点符号不同的重复项；" +
            "不要编号、解释或任何其他内容。"

    /**
     * 请求采样参数：max_tokens 覆盖 5 条候选的完整输出（每条 ≤20 字），
     * 流式下超限会截断末条候选（可接受）；temperature 保留少量多样性
     */
    private const val MAX_TOKENS = 80
    private const val TEMPERATURE = 0.7

    /**
     * 发起一次非流式续写预测请求（兼容旧端点/降级路径）。
     *
     * @param contextWords 最近上屏词序列（时间序，唯一数据源；
     *        架构禁令：不得传入编辑器全文，见可行性方案 §4.6）
     * @return 成功返回候选片段列表（≤ [StreamCandidateText.MAX_CANDIDATES] 条，
     *         每条 ≤ [MAX_CANDIDATE_CHARS] 字符）；任何异常（网络/HTTP/解析）
     *         均收敛为 Result.failure
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
                connection = openConnection(AiConfig.getApiUrl(context), apiKey, stream = false)
                connection.outputStream.use { output ->
                    output.write(buildRequestBody(AiConfig.getModel(context), contextWords, stream = false))
                    output.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // 日志只记状态码：errorStream 可能回显请求内容（含用户词），不读取不落日志
                    Log.w(TAG, "LLM 预测请求失败 HTTP $responseCode")
                    return@withContext Result.failure(IOException("HTTP $responseCode"))
                }

                val body = connection.inputStream.use { readBoundedText(it) }
                val candidates = StreamCandidateText.parseWhole(extractMessageContent(body))
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

    /**
     * 发起一次 SSE 流式续写预测请求（联想优化方案 §4.7）。
     *
     * 增量候选逐行到达即经 [onNewCandidates] 交付（调用方在 IO 线程收到，
     * 自行切主线程渲染）；流结束时冲刷残行并返回全量候选供缓存。
     * 部分成功语义：流中途异常但已产出候选时仍返回 success（部分结果
     * 优于没有）；零候选时收敛为 failure。
     *
     * @param contextWords 最近上屏词序列（同 [predict]，唯一数据源）
     * @param onNewCandidates 每批新完成候选的增量回调（IO 线程，按到达顺序）
     * @return 全量候选列表（≤ 5 条）或失败原因
     */
    suspend fun predictStream(
        context: Context,
        contextWords: List<String>,
        onNewCandidates: (List<String>) -> Unit
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (contextWords.isEmpty()) {
                return@withContext Result.failure(IOException("上下文词窗口为空"))
            }
            val apiKey = AiConfig.getApiKey(context)
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("未配置 API Key"))
            }
            val parser = StreamCandidateText()
            val collected = ArrayList<String>(StreamCandidateText.MAX_CANDIDATES)
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(AiConfig.getApiUrl(context), apiKey, stream = true)
                connection.outputStream.use { output ->
                    output.write(buildRequestBody(AiConfig.getModel(context), contextWords, stream = true))
                    output.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "LLM 流式预测请求失败 HTTP $responseCode")
                    return@withContext Result.failure(IOException("HTTP $responseCode"))
                }

                connection.inputStream.use { input ->
                    readSseChunks(input, isActive = { isActive }) { delta ->
                        val fresh = parser.offer(delta)
                        if (fresh.isNotEmpty()) {
                            collected.addAll(fresh)
                            onNewCandidates(fresh)
                        }
                    }
                }
                // 冲刷末行残片（无换行结尾的常态）
                val tail = parser.flush()
                if (tail.isNotEmpty()) {
                    collected.addAll(tail)
                    onNewCandidates(tail)
                }
                if (collected.isEmpty()) {
                    Result.failure(IOException("LLM 流式预测返回内容为空"))
                } else {
                    Result.success(collected)
                }
            } catch (e: IOException) {
                // 部分成功：已产出候选时流中断仍交付部分结果（优于没有）
                if (collected.isNotEmpty()) {
                    Result.success(collected)
                } else {
                    Log.w(TAG, "LLM 流式预测网络异常: ${e.javaClass.simpleName}")
                    Result.failure(e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (collected.isNotEmpty()) {
                    Result.success(collected)
                } else {
                    Log.w(TAG, "LLM 流式预测异常: ${e.javaClass.simpleName}")
                    Result.failure(IOException("LLM 预测请求失败"))
                }
            } finally {
                connection?.disconnect()
            }
        }

    /** 打开可信连接：强制 HTTPS + 收紧超时 + Bearer 鉴权头。 */
    private fun openConnection(spec: String, apiKey: String, stream: Boolean): HttpURLConnection {
        val url = URL(spec)
        if (url.protocol != "https") throw IOException("仅允许 HTTPS 的 AI 服务地址")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = if (stream) STREAM_READ_TIMEOUT else READ_TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (stream) setRequestProperty("Accept", "text/event-stream")
        }
    }

    /** 组装 OpenAI 兼容的 chat/completions 请求体（[stream] 控制 SSE 开关）。 */
    private fun buildRequestBody(model: String, contextWords: List<String>, stream: Boolean): ByteArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", "最近输入：${contextWords.joinToString("")}"))
        val body = JSONObject()
            .put("model", model)
            .put("stream", stream)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", TEMPERATURE)
            .put("messages", messages)
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** 非流式响应体提取 choices[0].message.content（缺失返回空串） */
    private fun extractMessageContent(body: String): String =
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: ""

    /**
     * 逐块读取 SSE 流：按行解析 `data:` 事件，提取增量 content 经
     * [onDelta] 交付。累计字节超 [MAX_RESPONSE_BYTES] 或协程作废即停；
     * `[DONE]` 终止符提前结束。空行/注释行（: 开头）按 SSE 规范跳过。
     *
     * @param input SSE 字节流
     * @param isActive 协程存活探针（作废后不再消费后续数据）
     * @param onDelta 增量文本回调（choices[0].delta.content 非空时）
     */
    private fun readSseChunks(
        input: InputStream,
        isActive: () -> Boolean,
        onDelta: (String) -> Unit
    ) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            var total = 0L
            while (isActive()) {
                val line = reader.readLine() ?: break
                total += line.length + 1
                if (total > MAX_RESPONSE_BYTES) {
                    throw IOException("LLM 流式响应超限（上限 ${MAX_RESPONSE_BYTES / 1024}KB）")
                }
                if (line.isEmpty() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                val delta = JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
                if (!delta.isNullOrEmpty()) onDelta(delta)
            }
        }
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
