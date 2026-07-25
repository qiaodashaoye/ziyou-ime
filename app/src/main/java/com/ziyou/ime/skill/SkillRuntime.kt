package com.ziyou.ime.skill

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.ziyou.ime.core.skill.SkillPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 技能 API 调用失败（权限拒绝 / 参数错误 / 限额超限），message 会透传给脚本侧 reject。 */
class SkillApiException(message: String) : Exception(message)

/**
 * 技能运行时：Bridge API 的能力实现层。
 *
 * 与 [SkillBridge] 分离：Bridge 只做消息搬运与线程切换，本类承载全部业务
 * （权限检查、storage 限额、fetch 代理、剪贴板、输入路由），不持有 WebView。
 *
 * [handle] 在主线程调用（Bridge 已切换），结果经回调异步交付：
 * 同步能力立即回调；fetch 在 IO 协程执行后切回主线程回调。
 * 面板关闭时须调用 [release] 取消未完成的网络请求。
 */
class SkillRuntime(
    private val context: Context,
    private val skill: SkillInfo,
    private val host: Host
) {
    companion object {
        private const val TAG = "SkillRuntime"

        /** 单技能 storage 限额（序列化后字节数） */
        private const val STORAGE_LIMIT_BYTES = 1024 * 1024

        /** sendText 单次长度上限，防脚本注入超长文本 */
        private const val MAX_COMMIT_LENGTH = 5000

        /** 面板标题长度上限 */
        private const val MAX_TITLE_LENGTH = 20

        // fetch 代理限额（可行性方案 §7 冻结值）
        private const val FETCH_TIMEOUT_MS = 10_000
        private const val FETCH_MAX_RESPONSE_BYTES = 1024 * 1024
        private const val FETCH_MAX_PER_MINUTE = 30
        private const val FETCH_MAX_CONCURRENT = 2

        private fun storageFileFor(context: Context, skillId: String): File {
            val safeId = skillId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return File(File(context.filesDir, "skill_data").apply { mkdirs() }, "$safeId.json")
        }

        /** 卸载技能时清理其 storage（由 [SkillPackageInstaller.uninstall] 调用）。 */
        fun deleteStorage(context: Context, skillId: String) {
            storageFileFor(context, skillId).delete()
        }
    }

    /** 宿主能力注入（由 Service / 面板容器实现）。 */
    interface Host {
        /** 文本上屏（经 InputLogicController 统一出口，含等级计分） */
        fun commitText(text: String)

        /** 关闭技能面板 */
        fun closePanel()

        /** 设置面板标题栏文字 */
        fun setPanelTitle(title: String)

        /** 当前前台编辑器所属应用包名 */
        fun editorPackageName(): String?

        /** 当前输入框类型（text / number / phone / datetime） */
        fun editorInputType(): String

        /** 按键震动反馈 */
        fun performHaptic()

        /** 输入路由开关：true 时键盘上屏文本改道注入面板（Phase 3，需 needs_input） */
        fun requestInputRouting(active: Boolean)

        /** 输入法界面展开开关：false 时键盘/编码区/候选区整体缩回、面板接管其空间
         *  （窗口总高不变）；true 完整恢复。仅提升挂载（needs_input）生效 */
        fun setImeExpanded(expanded: Boolean)
    }

    /** 运行时协程域：fetch 等异步能力；面板关闭时整体取消 */
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** fetch 频控：最近一分钟内的请求时间戳 */
    private val fetchTimestamps = ArrayDeque<Long>()

    /** fetch 并发计数 */
    private var activeFetches = 0

    private var storageCache: JSONObject? = null

    private val storageFile: File by lazy { storageFileFor(context, skill.manifest.id) }

    /** 释放运行时：取消未完成的 fetch，路由复位。 */
    fun release() {
        runtimeScope.cancel()
    }

    /**
     * 处理一次 API 调用（主线程），结果经 [complete] 异步交付：
     * 成功传 JSON 文本（null = 无返回值），失败传 [SkillApiException]。
     */
    fun handle(method: String, params: JSONObject, complete: (Result<String?>) -> Unit) {
        if (method == "fetch") {
            handleFetch(params, complete)
            return
        }
        // 其余方法均为同步能力，立即完成
        complete(runCatching { handleSync(method, params) })
    }

    private fun handleSync(method: String, params: JSONObject): String? = when (method) {
        "sendText" -> {
            val text = params.optString("text")
            if (text.isEmpty()) throw SkillApiException("text 不能为空")
            if (text.length > MAX_COMMIT_LENGTH) throw SkillApiException("文本超长（上限 $MAX_COMMIT_LENGTH 字符）")
            // sendText 语义固定直达宿主编辑器：若输入路由仍激活（脚本未 releaseFocus），
            // 先复位路由，否则文本会被 commitTarget 注回面板自身
            host.requestInputRouting(false)
            host.commitText(text)
            host.closePanel()
            null
        }

        "getContext" -> JSONObject()
            .put("packageName", host.editorPackageName() ?: "")
            .put("inputType", host.editorInputType())
            .toString()

        "getLocale" -> JSONObject.quote(Locale.getDefault().toLanguageTag())

        "haptic" -> {
            host.performHaptic()
            null
        }

        "ui.setTitle" -> {
            host.setPanelTitle(params.optString("title").take(MAX_TITLE_LENGTH))
            null
        }

        "ui.close" -> {
            host.closePanel()
            null
        }

        // 输入法界面展开/收缩：needs_input 技能查询完成后传 false 收缩整个输入法界面
        // （键盘/编码区/候选区缩回为内容腾位，窗口总高不变）；缺省 true = 恢复
        "ui.setExpanded" -> {
            host.setImeExpanded(params.optBoolean("expanded", true))
            null
        }

        "storage.get" -> {
            requirePermission(SkillPermission.STORAGE)
            val value = loadStorage().opt(requireKey(params))
            if (value == null) "null" else JSONObject.quote(value.toString())
        }

        "storage.set" -> {
            requirePermission(SkillPermission.STORAGE)
            val store = loadStorage()
            store.put(requireKey(params), params.optString("value"))
            saveStorage(store)
            null
        }

        "storage.remove" -> {
            requirePermission(SkillPermission.STORAGE)
            val store = loadStorage()
            store.remove(requireKey(params))
            saveStorage(store)
            null
        }

        "clipboard.read" -> {
            requirePermission(SkillPermission.CLIPBOARD_READ)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            if (text == null) "null" else JSONObject.quote(text)
        }

        "clipboard.write" -> {
            requirePermission(SkillPermission.CLIPBOARD_WRITE)
            val text = params.optString("text")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("skill", text))
            null
        }

        // 输入路由（Phase 3）：把键盘上屏文本改道注入面板输入框
        "input.requestFocus" -> {
            if (!skill.manifest.needsInput) {
                throw SkillApiException("manifest 未声明 needs_input，无法使用面板输入")
            }
            host.requestInputRouting(true)
            null
        }

        "input.releaseFocus" -> {
            host.requestInputRouting(false)
            null
        }

        else -> throw SkillApiException("未知方法: $method")
    }

    // ===== fetch 代理（Phase 2）=====

    /**
     * 宿主代理网络请求：强制 HTTPS + manifest 域名白名单（精确匹配）+
     * 超时 10s + 响应 ≤1MB + 频控 30 次/分钟 + 并发 ≤2 + 禁跟随重定向（防白名单逃逸）。
     */
    private fun handleFetch(params: JSONObject, complete: (Result<String?>) -> Unit) {
        val checked = runCatching {
            requirePermission(SkillPermission.NETWORK)

            val url = try {
                URL(params.optString("url"))
            } catch (e: Exception) {
                throw SkillApiException("非法 URL")
            }
            if (url.protocol != "https") throw SkillApiException("仅允许 HTTPS 请求")
            if (url.host !in skill.manifest.networkDomains) {
                throw SkillApiException("域名不在白名单: ${url.host}")
            }

            // 频控：滑动窗口 30 次/分钟
            val now = System.currentTimeMillis()
            while (fetchTimestamps.isNotEmpty() && now - fetchTimestamps.first() > 60_000) {
                fetchTimestamps.removeFirst()
            }
            if (fetchTimestamps.size >= FETCH_MAX_PER_MINUTE) {
                throw SkillApiException("请求过于频繁（上限 $FETCH_MAX_PER_MINUTE 次/分钟）")
            }
            if (activeFetches >= FETCH_MAX_CONCURRENT) {
                throw SkillApiException("并发请求超限（上限 $FETCH_MAX_CONCURRENT）")
            }
            fetchTimestamps.addLast(now)
            url
        }
        val url = checked.getOrElse {
            complete(Result.failure(it))
            return
        }

        val options = params.optJSONObject("options") ?: JSONObject()
        val httpMethod = if (options.optString("method").uppercase() == "POST") "POST" else "GET"
        val body = options.optString("body").takeIf { it.isNotEmpty() && httpMethod == "POST" }
        val contentType = options.optString("contentType").takeIf { it.isNotEmpty() }

        activeFetches++
        runtimeScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { executeFetch(url, httpMethod, body, contentType) }
                    .recoverCatching { e ->
                        if (e is SkillApiException) throw e
                        Log.w(TAG, "fetch 失败 ${url.host}: ${e.message}")
                        throw SkillApiException("网络请求失败: ${e.javaClass.simpleName}")
                    }
            }
            activeFetches--
            complete(result)
        }
    }

    /** IO 线程执行请求，返回 {status, body} JSON。 */
    private fun executeFetch(url: URL, method: String, body: String?, contentType: String?): String {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = FETCH_TIMEOUT_MS
            connection.readTimeout = FETCH_TIMEOUT_MS
            // 禁跟随重定向：重定向可能指向白名单之外的域
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ZiyouIME-Skill/${skill.manifest.id}")
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > FETCH_MAX_RESPONSE_BYTES) {
                        throw SkillApiException("响应超限（上限 1MB）")
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: ByteArray(0)

            return JSONObject()
                .put("status", status)
                .put("body", String(bytes, Charsets.UTF_8))
                .toString()
        } finally {
            connection?.disconnect()
        }
    }

    // ===== 内部 =====

    private fun requirePermission(permission: SkillPermission) {
        if (permission !in skill.manifest.permissions) {
            throw SkillApiException("权限拒绝：manifest 未声明 ${permission.id}")
        }
    }

    private fun requireKey(params: JSONObject): String {
        val key = params.optString("key")
        if (key.isEmpty()) throw SkillApiException("key 不能为空")
        return key
    }

    private fun loadStorage(): JSONObject {
        storageCache?.let { return it }
        val loaded = try {
            if (storageFile.exists()) JSONObject(storageFile.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "storage 读取失败，重置: ${e.message}")
            JSONObject()
        }
        storageCache = loaded
        return loaded
    }

    private fun saveStorage(store: JSONObject) {
        val serialized = store.toString()
        if (serialized.toByteArray(Charsets.UTF_8).size > STORAGE_LIMIT_BYTES) {
            throw SkillApiException("存储超限（上限 1MB）")
        }
        try {
            storageFile.writeText(serialized)
        } catch (e: Exception) {
            throw SkillApiException("存储写入失败")
        }
    }
}
