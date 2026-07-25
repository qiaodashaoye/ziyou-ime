package com.ziyou.ime.skill

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * JS Bridge 单入口。
 *
 * 脚本经 `__IMESkillNative.postMessage(json)` 发起调用（消息体含 callId/method/params），
 * 宿主处理后经 `evaluateJavascript("__imeskillResolve(...)")` 异步 resolve/reject——
 * 单入口窄面 + 全异步，规避 addJavascriptInterface 多方法直暴与同步返回的安全隐患。
 *
 * 线程模型：[postMessage] 运行在 WebView 的 JavaBridge 线程，立即切主线程再分发；
 * 所有异常全量兜底（技能崩溃不得波及 IME 主进程）。
 */
class SkillBridge(
    private val runtime: SkillRuntime,
    private val webViewProvider: () -> WebView?
) {
    companion object {
        private const val TAG = "SkillBridge"

        /** 注入到 JS 全局的原生对象名（垫片 imeskill.js 封装为 window.IMESkill） */
        const val JS_INTERFACE_NAME = "__IMESkillNative"

        /** 单条消息长度上限，防脚本构造超大消息拖垮主线程 */
        private const val MAX_MESSAGE_LENGTH = 512 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 释放后拒绝一切调用（面板已关闭，脚本残留的异步回调静默丢弃） */
    @Volatile
    private var released = false

    /** 面板关闭时调用：后续消息全部丢弃。 */
    fun release() {
        released = true
    }

    @JavascriptInterface
    fun postMessage(message: String?) {
        if (released || message == null || message.length > MAX_MESSAGE_LENGTH) return
        mainHandler.post { dispatch(message) }
    }

    private fun dispatch(message: String) {
        if (released) return
        val callId: Long
        val method: String
        val params: JSONObject
        try {
            val obj = JSONObject(message)
            callId = obj.optLong("callId", -1L)
            method = obj.optString("method")
            params = obj.optJSONObject("params") ?: JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "非法 Bridge 消息: ${e.message}")
            return
        }
        if (callId < 0 || method.isEmpty()) return

        try {
            // 结果异步交付（fetch 在 IO 协程完成后回调，其余能力立即回调）
            runtime.handle(method, params) { result ->
                result.fold(
                    onSuccess = { data -> resolve(callId, ok = true, dataJson = data) },
                    onFailure = { e ->
                        val message = if (e is SkillApiException) e.message else {
                            // 未预期异常：兜底不抛出，脚本收到通用错误
                            Log.e(TAG, "Bridge 调用异常 method=$method: ${e.message}", e)
                            "内部错误"
                        }
                        resolve(callId, ok = false,
                            dataJson = JSONObject().put("message", message).toString())
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bridge 分发异常 method=$method: ${e.message}", e)
            resolve(callId, ok = false, dataJson = JSONObject().put("message", "内部错误").toString())
        }
    }

    /** 将结果回传脚本：dataJson 为 JSON 文本（以 JS 字符串字面量注入，脚本侧 JSON.parse）。 */
    private fun resolve(callId: Long, ok: Boolean, dataJson: String?) {
        if (released) return
        val webView = webViewProvider() ?: return
        val dataLiteral = if (dataJson == null) "null" else JSONObject.quote(dataJson)
        webView.evaluateJavascript(
            "window.__imeskillResolve($callId,$ok,$dataLiteral)", null
        )
    }
}
