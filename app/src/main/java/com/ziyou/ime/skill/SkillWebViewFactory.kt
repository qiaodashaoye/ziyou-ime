package com.ziyou.ime.skill

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ziyou.ime.core.skill.SkillManifestValidator
import com.ziyou.ime.core.skill.ZipEntryValidator
import java.io.ByteArrayInputStream

/**
 * 技能 WebView 工厂：统一全部安全配置，技能面板只能经此创建 WebView。
 *
 * 安全基线（可行性方案 §1.3 / §4.2）：
 * - 资源全量拦截：仅放行虚拟域名下映射到技能包内的资源，其余请求（含 img/script/css
 *   等旁路出网通道）一律返回空响应；HTML 响应附加 CSP 头二次收紧。
 * - 文件/内容访问全关，JS 仅暴露 [SkillBridge] 单入口。
 * - onRenderProcessGone 兜底：渲染进程崩溃时销毁面板，IME 主进程照常存活（红线）。
 * - 垫片 imeskill.js 优先经 DOCUMENT_START_SCRIPT 注入（页面脚本执行前可用），
 *   不支持的 WebView 回退 onPageStarted 注入（垫片幂等）。
 */
object SkillWebViewFactory {
    private const val TAG = "SkillWebViewFactory"

    /** 技能资源虚拟域名（androidx.webkit 惯例保留域，不会真实出网） */
    private const val VIRTUAL_HOST = "appassets.androidplatform.net"

    /** 技能包内资源的虚拟路径前缀 */
    private const val SKILL_PATH_PREFIX = "/skill/"

    /** 垫片脚本的 assets 路径 */
    private const val SHIM_ASSET = "skill_runtime/imeskill.js"

    /** HTML 响应附加的 CSP：包内资源 + 内联脚本样式，禁一切连接与外域 */
    private const val CSP =
        "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'"

    /** 技能入口页 URL。 */
    fun entryUrl(skill: SkillInfo): String =
        "https://$VIRTUAL_HOST$SKILL_PATH_PREFIX${skill.manifest.entry}"

    /**
     * 创建已完成安全配置的技能 WebView。
     *
     * @param onRenderProcessGone 渲染进程崩溃回调（主线程），调用方应销毁面板
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        skill: SkillInfo,
        bridge: SkillBridge,
        onRenderProcessGone: () -> Unit
    ): WebView {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            // 禁缓存：技能资源本地即取，避免 WebView 缓存目录膨胀
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        // 仅 debuggable 构建开启远程调试（Chrome DevTools），生产包不暴露
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        webView.addJavascriptInterface(bridge, SkillBridge.JS_INTERFACE_NAME)

        val shimScript = loadShim(context)
        val shimInjectedAtStart = injectShimAtDocumentStart(webView, shimScript)

        webView.webViewClient = object : WebViewClient() {

            /** 全量资源拦截：仅放行虚拟域名下的技能包内资源 */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.host == VIRTUAL_HOST && uri.path?.startsWith(SKILL_PATH_PREFIX) == true) {
                    val relativePath = uri.path!!.removePrefix(SKILL_PATH_PREFIX)
                    if (ZipEntryValidator.isSafeRelativePath(relativePath)) {
                        skill.openResource(context, relativePath)?.let { stream ->
                            val mime = guessMimeType(relativePath)
                            return if (mime == "text/html") {
                                WebResourceResponse(
                                    mime, "utf-8", 200, "OK",
                                    mapOf("Content-Security-Policy" to CSP), stream
                                )
                            } else {
                                WebResourceResponse(mime, "utf-8", stream)
                            }
                        }
                    }
                }
                // 其余一律空响应（含外网 img/script/css 等旁路通道）
                return blockedResponse()
            }

            /** 禁止一切页面跳转（技能只能停留在自身入口页体系内） */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host != VIRTUAL_HOST

            /** 垫片回退注入（不支持 DOCUMENT_START_SCRIPT 的 WebView；垫片幂等可重复执行） */
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!shimInjectedAtStart) {
                    view.evaluateJavascript(shimScript, null)
                }
            }

            /**
             * 渲染进程崩溃兜底：返回 true 表示宿主已处理，系统不得杀死 IME 主进程。
             * （API 26+ 回调；26 以下渲染器与应用同进程，无此事件）
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.e(TAG, "技能渲染进程终止 (crash=${detail.didCrash()})，销毁面板保活 IME")
                onRenderProcessGone()
                return true
            }
        }
        return webView
    }

    // ===== 内部 =====

    /** 读取垫片脚本并同步 apiVersion 为宏事实源 [SkillManifestValidator.HOST_API_VERSION]
     *  （正则不命中时保留文件内回退值）；读取失败返回空串（技能仍可渲染，仅 Bridge 不可用并记录错误） */
    private fun loadShim(context: Context): String = try {
        context.assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
            .replaceFirst(
                Regex("""apiVersion:\s*\d+"""),
                "apiVersion: ${SkillManifestValidator.HOST_API_VERSION}"
            )
    } catch (e: Exception) {
        Log.e(TAG, "imeskill.js 垫片读取失败: ${e.message}")
        ""
    }

    /** 尝试 DOCUMENT_START_SCRIPT 注入（页面任何脚本执行前生效）。@return 是否成功 */
    private fun injectShimAtDocumentStart(webView: WebView, script: String): Boolean = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, script, setOf("https://$VIRTUAL_HOST")
            )
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "DOCUMENT_START_SCRIPT 注入失败，回退 onPageStarted: ${e.message}")
        false
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun guessMimeType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html", "htm" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "woff", "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
