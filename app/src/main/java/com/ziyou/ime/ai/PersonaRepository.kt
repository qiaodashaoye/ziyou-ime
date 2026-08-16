package com.ziyou.ime.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 人设持久化仓库
 *
 * 以 SharedPreferences（名称 `ziyou_ai_persona`）存储自定义人设列表与
 * 当前选中人设 ID。内置人设由 [AiPersona.BUILTINS] 硬编码提供，与自定义
 * 人设合并后返回给调用方，避免双数据源不一致。
 *
 * JSON 结构（`KEY_CUSTOM_LIST` 的值）：
 * ```json
 * [
 *   { "id": "...", "name": "...", "description": "...", "systemPrompt": "...",
 *     "knowledgeItemIds": ["kb_...", ...] },
 *   ...
 * ]
 * ```
 * `knowledgeItemIds` 为可选字段（缺省反序列化为空列表，旧数据零迁移）。
 * 内置人设不持久化，始终从代码读取，保证升级后内置模板可自动更新。
 */
object PersonaRepository {

    private const val TAG = "PersonaRepository"
    private const val PREF_NAME = "ziyou_ai_persona"
    private const val KEY_CURRENT_ID = "current_persona_id"
    private const val KEY_CUSTOM_LIST = "custom_list"

    /** 自定义人设 ID 前缀（区分内置与自定义，避免 ID 冲突） */
    private const val CUSTOM_ID_PREFIX = "custom_"

    // ===== 查询 =====

    /** 获取全部人设：内置在前 + 自定义在后（顺序稳定，UI 列表直接展示）。 */
    fun getAllPersonas(context: Context): List<AiPersona> {
        return AiPersona.BUILTINS + loadCustomPersonas(context)
    }

    /** 获取当前选中人设；ID 不存在时回退到默认人设。 */
    fun getCurrentPersona(context: Context): AiPersona {
        val currentId = getPreferences(context)
            .getString(KEY_CURRENT_ID, AiPersona.DEFAULT_PERSONA_ID)
            ?: AiPersona.DEFAULT_PERSONA_ID
        return getAllPersonas(context).firstOrNull { it.id == currentId }
            ?: AiPersona.ASSISTANT
    }

    /** 获取当前选中人设 ID。 */
    fun getCurrentPersonaId(context: Context): String =
        getPreferences(context)
            .getString(KEY_CURRENT_ID, AiPersona.DEFAULT_PERSONA_ID)
            ?: AiPersona.DEFAULT_PERSONA_ID

    // ===== 写入 =====

    /** 切换当前人设（不校验 ID 有效性，调用方需保证存在）。 */
    fun setCurrentPersona(context: Context, id: String) {
        getPreferences(context).edit()
            .putString(KEY_CURRENT_ID, id)
            .apply()
    }

    /**
     * 添加自定义人设，返回实际存入的 persona（ID 自动补充前缀，
     * 确保唯一性）。若 ID 与已有记录冲突，追加时间戳后缀。
     */
    fun addCustomPersona(context: Context, persona: AiPersona): AiPersona {
        val customs = loadCustomPersonas(context).toMutableList()
        // 确保 ID 唯一
        var finalId = if (persona.id.startsWith(CUSTOM_ID_PREFIX)) persona.id
        else CUSTOM_ID_PREFIX + persona.id
        if (customs.any { it.id == finalId } || AiPersona.BUILTINS.any { it.id == finalId }) {
            finalId = CUSTOM_ID_PREFIX + System.currentTimeMillis()
        }
        val toSave = persona.copy(id = finalId, isBuiltin = false)
        customs.add(toSave)
        saveCustomPersonas(context, customs)
        return toSave
    }

    /** 更新自定义人设（按 ID 匹配；仅 isBuiltin=false 的记录允许更新）。 */
    fun updateCustomPersona(context: Context, persona: AiPersona): Boolean {
        if (persona.isBuiltin) return false
        val customs = loadCustomPersonas(context).toMutableList()
        val index = customs.indexOfFirst { it.id == persona.id }
        if (index < 0) return false
        customs[index] = persona.copy(isBuiltin = false)
        saveCustomPersonas(context, customs)
        return true
    }

    /**
     * 删除自定义人设（内置人设不可删除；删除后若为当前选中，回退到默认人设）。
     * 其名下跨会话摘要槽由调用方经 AiMemoryStore.clearPersona 一并清理，
     * 避免本层反向依赖 knowledge 包。
     */
    fun removeCustomPersona(context: Context, id: String): Boolean {
        val customs = loadCustomPersonas(context).toMutableList()
        val removed = customs.removeAll { it.id == id && !it.isBuiltin }
        if (!removed) return false
        saveCustomPersonas(context, customs)
        // 若删除的恰好是当前选中人设，回退到默认
        if (getCurrentPersonaId(context) == id) {
            setCurrentPersona(context, AiPersona.DEFAULT_PERSONA_ID)
        }
        return true
    }

    // ===== 知识库绑定引用维护 =====

    /**
     * 清理所有自定义人设对已删知识条目 [itemId] 的绑定引用。
     * 知识条目删除后由 KnowledgeRepository 侧调用；人设与条目均为
     * 个位数规模，全量遍历重写无性能压力。无引用时不触发写入。
     */
    fun purgeKnowledgeRefs(context: Context, itemId: String) {
        val customs = loadCustomPersonas(context)
        if (customs.none { itemId in it.knowledgeItemIds }) return
        val cleaned = customs.map { p ->
            if (itemId in p.knowledgeItemIds) {
                p.copy(knowledgeItemIds = p.knowledgeItemIds.filter { it != itemId })
            } else p
        }
        saveCustomPersonas(context, cleaned)
    }

    /** 反查：绑定指定知识条目的自定义人设数（知识库页徽标展示用）。 */
    fun countPersonasBoundTo(context: Context, itemId: String): Int =
        loadCustomPersonas(context).count { itemId in it.knowledgeItemIds }

    // ===== 内部序列化 =====

    private fun loadCustomPersonas(context: Context): List<AiPersona> {
        val json = getPreferences(context).getString(KEY_CUSTOM_LIST, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val boundIds = obj.optJSONArray("knowledgeItemIds")
                val ids = if (boundIds == null) emptyList()
                else (0 until boundIds.length()).map { boundIds.getString(it) }
                AiPersona(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    systemPrompt = obj.getString("systemPrompt"),
                    isBuiltin = false,
                    knowledgeItemIds = ids
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "反序列化自定义人设失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveCustomPersonas(context: Context, personas: List<AiPersona>) {
        val array = JSONArray()
        personas.forEach { p ->
            val obj = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("description", p.description)
                .put("systemPrompt", p.systemPrompt)
            if (p.knowledgeItemIds.isNotEmpty()) {
                obj.put("knowledgeItemIds", JSONArray(p.knowledgeItemIds))
            }
            array.put(obj)
        }
        getPreferences(context).edit()
            .putString(KEY_CUSTOM_LIST, array.toString())
            .apply()
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
