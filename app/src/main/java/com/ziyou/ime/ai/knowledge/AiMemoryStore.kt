package com.ziyou.ime.ai.knowledge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ziyou.ime.ai.AiChatClient
import com.ziyou.ime.ai.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 对话记忆存储
 *
 * 两级记忆（SharedPreferences `ziyou_ai_memory`）：
 * - 最近会话历史：面板关闭/新对话时持久化最近一轮完整对话（JSON，上限
 *   与面板 MAX_HISTORY_SIZE 一致），面板重开可恢复「继续上次对话」；
 *   仅问答模式写入，润色内容不落盘（用户原创文本隐私纪律）；
 * - 跨会话摘要：按人设分槽（key = [SUMMARY_KEY_PREFIX] + personaId），
 *   会话结束且轮次足够时，后台经 [AiChatClient.ask] 以固定摘要提示词
 *   生成 ≤100 字要点摘要，覆盖存储（每槽一条防膨胀），下次提问经
 *   RagPromptBuilder 注入【长期记忆】区块。旧版全局单条摘要首次读取时
 *   懒迁移到默认人设槽。
 *
 * 摘要生成失败静默放弃，不影响任何主流程；摘要协程使用独立 IO 作用域，
 * 不依赖面板已取消的 panelScope。
 */
object AiMemoryStore {

    private const val TAG = "AiMemoryStore"
    private const val PREF_NAME = "ziyou_ai_memory"
    private const val KEY_LAST_SESSION = "last_session"

    /** 摘要槽 key 前缀（完整 key = 前缀 + personaId） */
    private const val SUMMARY_KEY_PREFIX = "summary_"

    /** 旧版全局摘要 key（升级前单槽存储，懒迁移用） */
    private const val LEGACY_SUMMARY_KEY = "summary"

    /** 旧摘要迁入的目标槽（升级前默认人设即智能助手） */
    private const val LEGACY_MIGRATE_PERSONA_ID = "builtin_assistant"

    /** 持久化历史条数上限（与面板历史上限一致） */
    private const val MAX_SESSION_MESSAGES = 10

    /** 触发摘要生成的最小历史条数（两轮完整问答） */
    private const val MIN_MESSAGES_FOR_SUMMARY = 4

    /** 摘要生成提示词 */
    private const val SUMMARY_PROMPT = "你是对话摘要助手。请用不超过100字总结以下对话的" +
        "要点与用户偏好（如称呼、关注话题、回答风格偏好），输出纯文本，不要使用 Markdown。"

    /** 摘要生成协程作用域（独立于面板生命周期） */
    private val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ===== 会话历史 =====

    /** 持久化最近会话历史（空历史时清除记录）。 */
    fun saveSession(context: Context, history: List<ChatMessage>) {
        val prefs = getPreferences(context)
        if (history.isEmpty()) {
            prefs.edit().remove(KEY_LAST_SESSION).apply()
            return
        }
        val array = JSONArray()
        history.takeLast(MAX_SESSION_MESSAGES).forEach { msg ->
            array.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }
        prefs.edit().putString(KEY_LAST_SESSION, array.toString()).apply()
    }

    /** 读取上次会话历史；无记录或损坏返回空列表。 */
    fun loadSession(context: Context): List<ChatMessage> {
        val json = getPreferences(context).getString(KEY_LAST_SESSION, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ChatMessage(obj.getString("role"), obj.getString("content"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "反序列化会话历史失败: ${e.message}", e)
            emptyList()
        }
    }

    // ===== 跨会话摘要（按人设分槽） =====

    /**
     * 指定人设的跨会话摘要（无摘要返回空串）。
     * 首次调用时懒迁移旧版全局摘要到默认人设槽（一次性）。
     */
    fun loadSummary(context: Context, personaId: String): String {
        val prefs = getPreferences(context)
        prefs.getString(summaryKey(personaId), null)?.let { return it }
        // 旧版全局摘要懒迁移：仅迁入默认人设槽（升级前的摘要均产生于默认人设）
        if (personaId == LEGACY_MIGRATE_PERSONA_ID) {
            val legacy = prefs.getString(LEGACY_SUMMARY_KEY, null)
            if (!legacy.isNullOrEmpty()) {
                prefs.edit()
                    .putString(summaryKey(personaId), legacy)
                    .remove(LEGACY_SUMMARY_KEY)
                    .apply()
                return legacy
            }
        }
        return ""
    }

    /**
     * 会话结束时异步更新指定人设的跨会话摘要（历史不足
     * [MIN_MESSAGES_FOR_SUMMARY] 条时跳过）。生成经网络调用，
     * 失败静默放弃保留旧摘要。
     */
    fun updateSummaryAsync(context: Context, personaId: String, history: List<ChatMessage>) {
        if (history.size < MIN_MESSAGES_FOR_SUMMARY) return
        val application = context.applicationContext
        // 单条消息截断 + 整体截断，确保不超 AiChatClient 的单次提问长度上限（2000 字）
        val transcript = history.joinToString("\n") { msg ->
            (if (msg.role == "user") "用户：" else "AI：") + msg.content.take(300)
        }.take(1900)
        summaryScope.launch {
            AiChatClient.ask(application, transcript, SUMMARY_PROMPT)
                .onSuccess { summary ->
                    getPreferences(application).edit()
                        .putString(summaryKey(personaId), summary.take(200))
                        .apply()
                    Log.i(TAG, "跨会话摘要已更新（persona=$personaId，${summary.length} 字）")
                }
                .onFailure { e ->
                    Log.w(TAG, "跨会话摘要生成失败（保留旧摘要）: ${e.message}")
                }
        }
    }

    /** 清除指定人设的摘要槽（删除自定义人设时调用）。 */
    fun clearPersona(context: Context, personaId: String) {
        getPreferences(context).edit().remove(summaryKey(personaId)).apply()
    }

    /** 清空全部记忆（会话历史 + 全部人设摘要槽 + 旧版全局摘要）。 */
    fun clear(context: Context) {
        val prefs = getPreferences(context)
        val editor = prefs.edit().remove(KEY_LAST_SESSION).remove(LEGACY_SUMMARY_KEY)
        prefs.all.keys.filter { it.startsWith(SUMMARY_KEY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun summaryKey(personaId: String): String = SUMMARY_KEY_PREFIX + personaId

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
