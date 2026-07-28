package com.ziyou.ime.skill

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.ziyou.ime.core.skill.SkillPanelSpec
import com.ziyou.ime.core.skill.SkillPermission
import com.ziyou.ime.ime.GalleryImageSaver
import com.ziyou.ime.ime.ImeImageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
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

        /** PNG 文件头魔数（image.* 仅接受 PNG，防伪造 MIME） */
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

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

        /** 设置面板高度比例（键盘高度的倍数，已钳制）。仅提升挂载（needs_input）生效 */
        fun setPanelHeightRatio(ratio: Float)

        /** 当前编辑器是否接受图片富媒体（EditorInfo.contentMimeTypes 含 image 类型） */
        fun editorAcceptsImage(): Boolean

        /** 将 PNG 文件经 commitContent 发送到宿主编辑器（主线程），返回是否提交成功 */
        fun commitImage(file: File, description: String): Boolean
    }

    /** 运行时协程域：fetch / storage 等异步能力；面板关闭时整体取消 */
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** storage 专属串行 IO 调度器：磁盘读写移出主线程，且保证 set/remove 提交顺序 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storageContext = Dispatchers.IO.limitedParallelism(1)

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
        if (method.startsWith("storage.")) {
            handleStorage(method, params, complete)
            return
        }
        if (method.startsWith("image.")) {
            handleImage(method, params, complete)
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

        // 面板高度自定义（API v4）：ratio 为键盘高度的倍数，宿主钳制到合法区间；
        // 仅提升挂载（needs_input）生效，键盘叠层形态面板本就占满键盘区
        "ui.setPanelHeight" -> {
            if (!params.has("ratio")) throw SkillApiException("ratio 不能为空")
            val ratio = params.optDouble("ratio", Double.NaN).toFloat()
            host.setPanelHeightRatio(SkillPanelSpec.clampHeightRatio(ratio))
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

    // ===== storage（异步：磁盘读写不占主线程）=====

    /**
     * storage 三方法统一异步处理：权限/参数校验在主线程即时失败，
     * 文件读写在 [storageContext]（单并发 IO）串行执行后切回主线程交付。
     * runtimeScope 为主线程顺序启动协程 + 单并发调度器 FIFO，提交顺序得以保持。
     */
    private fun handleStorage(method: String, params: JSONObject, complete: (Result<String?>) -> Unit) {
        val key = runCatching {
            requirePermission(SkillPermission.STORAGE)
            requireKey(params)
        }.getOrElse {
            complete(Result.failure(it))
            return
        }
        runtimeScope.launch {
            val result = withContext(storageContext) {
                runCatching {
                    when (method) {
                        "storage.get" -> {
                            val value = loadStorage().opt(key)
                            if (value == null) "null" else JSONObject.quote(value.toString())
                        }
                        "storage.set" -> {
                            val store = loadStorage()
                            store.put(key, params.optString("value"))
                            saveStorage(store)
                            null
                        }
                        "storage.remove" -> {
                            val store = loadStorage()
                            store.remove(key)
                            saveStorage(store)
                            null
                        }
                        else -> throw SkillApiException("未知方法: $method")
                    }
                }
            }
            complete(result)
        }
    }

    // ===== image（API v3：图片输出，需 image 权限）=====

    /**
     * image.send / image.saveToGallery 统一处理：权限与前置条件在主线程即时失败，
     * base64 解码/写盘在 IO 协程执行；image.send 回主线程经宿主 commitContent 提交
     *（与涂鸦/AI 面板发图同路径）。仅接受 PNG（文件头魔数校验）。
     */
    private fun handleImage(method: String, params: JSONObject, complete: (Result<String?>) -> Unit) {
        val data = runCatching {
            requirePermission(SkillPermission.IMAGE)
            when (method) {
                "image.send" -> if (!host.editorAcceptsImage()) {
                    throw SkillApiException("当前输入框不支持接收图片")
                }
                "image.saveToGallery" -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw SkillApiException("保存到相册需要 Android 10 及以上系统")
                }
                else -> throw SkillApiException("未知方法: $method")
            }
            val raw = params.optString("data")
            if (raw.isEmpty()) throw SkillApiException("图片数据不能为空")
            raw
        }.getOrElse {
            complete(Result.failure(it))
            return
        }
        runtimeScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { decodePngBytes(data) }
                if (method == "image.send") {
                    val file = withContext(Dispatchers.IO) { writeImageFile(bytes) }
                    // commitContent 必须在主线程（InputConnection 约束）
                    if (!host.commitImage(file, skill.manifest.name)) {
                        throw SkillApiException("图片发送失败，当前输入框可能不支持")
                    }
                } else {
                    withContext(Dispatchers.IO) { insertToGallery(bytes) }
                }
                null
            }.recoverCatching { e ->
                if (e is SkillApiException) throw e
                Log.w(TAG, "$method 失败: ${e.message}")
                throw SkillApiException("图片处理失败")
            }
            complete(result)
        }
    }

    /** 解码 base64（容忍 data URL 前缀）并校验 PNG 文件头。 */
    private fun decodePngBytes(data: String): ByteArray {
        val payload = data.substringAfter("base64,", data)
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.size < PNG_HEADER.size) throw SkillApiException("图片数据无效")
        PNG_HEADER.forEachIndexed { index, byte ->
            if (bytes[index] != byte) throw SkillApiException("仅支持 PNG 图片")
        }
        return bytes
    }

    /** 写入 FileProvider 已暴露的共享缓存子目录（先清理本技能历史文件，避免缓存累积）。 */
    private fun writeImageFile(bytes: ByteArray): File {
        val dir = File(context.cacheDir, ImeImageCache.CACHE_DIR_NAME).apply { mkdirs() }
        val prefix = "skill_${skill.manifest.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}_"
        dir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        val file = File(dir, "$prefix${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        return file
    }

    /** 插入系统相册 Pictures/字由输入法/（API 29+ MediaStore 免存储权限，
     *  与涂鸦/AI 面板「保存」路径共用 [GalleryImageSaver] 实现）。 */
    private fun insertToGallery(bytes: ByteArray) {
        if (!GalleryImageSaver.savePng(context, bytes, "ziyou_skill")) {
            throw SkillApiException("相册写入失败")
        }
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
            // 白名单比对前归一化：小写 + IDN/punycode，防 "WTTR.IN" / Unicode 同形域名绕过
            val host = normalizeHost(url.host) ?: throw SkillApiException("非法 URL")
            if (skill.manifest.networkDomains.none { normalizeHost(it) == host }) {
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

    /** 域名归一化：IDN 转 ASCII（punycode）+ 统一小写；非法域名返回 null。 */
    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        return try {
            IDN.toASCII(host).lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }
    }

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
