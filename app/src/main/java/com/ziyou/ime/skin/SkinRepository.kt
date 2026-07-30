package com.ziyou.ime.skin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.core.skin.SkinLayer
import com.ziyou.ime.core.skin.SkinSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 皮肤持久化仓库：当前皮肤 id、已安装皮肤索引、用户自定义覆盖、深浅色策略。
 *
 * 存储布局：
 * - SharedPreferences("ziyou_skin")：current_skin_id / installed_index /
 *   override.<skinId> / dark_mode_policy
 * - filesDir/skins/<skinId>/：导入皮肤安装目录（skin.json + images/ + fonts/ + preview.png）
 *
 * 内置皮肤为内存规格（[SkinDefaults]），不落盘、不进索引、不可卸载。
 * 旧版 ThemeManager 偏好（"ziyou_theme"）在首次访问时一次性迁移。
 */
object SkinRepository {
    private const val TAG = "SkinRepository"

    private const val PREF_NAME = "ziyou_skin"
    private const val KEY_CURRENT = "current_skin_id"
    private const val KEY_INDEX = "installed_index"
    private const val KEY_OVERRIDE_PREFIX = "override."
    private const val KEY_DARK_POLICY = "dark_mode_policy"

    // 旧版主题偏好（一次性迁移后删除）
    private const val LEGACY_PREF_NAME = "ziyou_theme"
    private const val LEGACY_KEY_THEME = "current_theme"

    // 已废弃的历史内置皮肤 id → 现行 id（读取时透明改写）
    private val RENAMED_SKIN_IDS = mapOf("builtin.dark" to SkinDefaults.ID_YUNWU)

    /** 深浅色策略。 */
    enum class DarkModePolicy(val id: String) {
        FOLLOW_SYSTEM("followSystem"),
        FORCE_LIGHT("forceLight"),
        FORCE_DARK("forceDark");

        companion object {
            fun fromId(id: String?): DarkModePolicy =
                entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
        }
    }

    // ===== 当前皮肤 =====

    fun getCurrentSkinId(context: Context): String {
        migrateLegacyIfNeeded(context)
        val stored = prefs(context).getString(KEY_CURRENT, SkinDefaults.DEFAULT_SKIN_ID)
            ?: SkinDefaults.DEFAULT_SKIN_ID
        // 内置皮肤改名迁移：历史持久化 id 改写为现行 id
        val renamed = RENAMED_SKIN_IDS[stored] ?: return stored
        setCurrentSkinId(context, renamed)
        Log.i(TAG, "内置皮肤 id 已迁移: $stored -> $renamed")
        return renamed
    }

    fun setCurrentSkinId(context: Context, skinId: String) {
        prefs(context).edit().putString(KEY_CURRENT, skinId).apply()
    }

    // ===== 深浅色策略 =====

    fun getDarkModePolicy(context: Context): DarkModePolicy =
        DarkModePolicy.fromId(prefs(context).getString(KEY_DARK_POLICY, null))

    fun setDarkModePolicy(context: Context, policy: DarkModePolicy) {
        prefs(context).edit().putString(KEY_DARK_POLICY, policy.id).apply()
    }

    // ===== 皮肤目录 =====

    fun skinsRoot(context: Context): File = File(context.filesDir, "skins")

    /**
     * 皮肤资源目录。导入皮肤 = 安装目录；内置皮肤规格在内存不落盘，
     * 但同名目录仍用于承载用户自定义附加资产（如自选背景图）。
     */
    fun skinDir(context: Context, skinId: String): File = File(skinsRoot(context), skinId)

    // ===== 规格加载 =====

    /** 进程级规格缓存：导入皮肤的 skin.json 解码产物，避免读热路径重复磁盘 IO 。 */
    private val specCache = ConcurrentHashMap<String, SkinSpec>()

    /**
     * 加载皮肤规格：内置取内存规格，导入皮肤优先命中 [specCache]，
     * 未命中才读安装目录 skin.json（安装/卸载时经 [evictSpec] 定向失效）。
     * @throws IllegalArgumentException 皮肤不存在或 skin.json 非法
     */
    fun loadSpec(context: Context, skinId: String): SkinSpec {
        SkinDefaults.builtinSpec(skinId)?.let { return it }
        specCache[skinId]?.let { return it }
        val file = File(skinsRoot(context), skinId).resolve("skin.json")
        if (!file.isFile) {
            throw IllegalArgumentException("皮肤不存在或已损坏: $skinId")
        }
        return SkinSpecCodec.decodeSpec(file.readText()).also { specCache[skinId] = it }
    }

    /** 皮肤安装 / 卸载后定向失效规格缓存（与 [SkinAssetCache.evict] 同时机调用）。 */
    fun evictSpec(skinId: String) {
        specCache.remove(skinId)
    }

    // ===== 已安装索引 =====

    /** 全部可用皮肤（内置在前，导入按索引顺序在后）。 */
    fun listInstalled(context: Context): List<SkinInfo> {
        val builtins = SkinDefaults.builtinSpecs.map { spec ->
            SkinInfo(
                id = spec.meta.id,
                name = spec.meta.name,
                author = spec.meta.author,
                version = spec.meta.version,
                isBuiltin = true,
                installDir = null
            )
        }
        val imported = readIndex(context).mapNotNull { entry ->
            val dir = File(skinsRoot(context), entry.id)
            // 目录被外部清掉时静默剔除，避免列表出现幽灵皮肤
            if (dir.resolve("skin.json").isFile) {
                SkinInfo(entry.id, entry.name, entry.author, entry.version,
                    isBuiltin = false, installDir = dir)
            } else {
                null
            }
        }
        return builtins + imported
    }

    fun findInstalled(context: Context, skinId: String): SkinInfo? =
        listInstalled(context).firstOrNull { it.id == skinId }

    /** 安装成功后登记索引（同 id 覆盖旧条目）。 */
    fun addToIndex(context: Context, spec: SkinSpec) {
        val entries = readIndex(context).filterNot { it.id == spec.meta.id } +
            IndexEntry(spec.meta.id, spec.meta.name, spec.meta.author, spec.meta.version)
        writeIndex(context, entries)
    }

    fun removeFromIndex(context: Context, skinId: String) {
        writeIndex(context, readIndex(context).filterNot { it.id == skinId })
    }

    // ===== 用户自定义覆盖 =====

    /** 读取皮肤的用户覆盖层（无覆盖或损坏时返回 null）。 */
    fun getOverride(context: Context, skinId: String): SkinLayer? {
        val json = prefs(context).getString(KEY_OVERRIDE_PREFIX + skinId, null) ?: return null
        return try {
            SkinSpecCodec.decodeLayerString(json).takeIf { !it.isEmpty() }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "用户覆盖损坏，已忽略: $skinId, ${e.message}")
            null
        }
    }

    fun setOverride(context: Context, skinId: String, layer: SkinLayer) {
        if (layer.isEmpty()) {
            clearOverride(context, skinId)
            return
        }
        prefs(context).edit()
            .putString(KEY_OVERRIDE_PREFIX + skinId, SkinSpecCodec.encodeLayer(layer))
            .apply()
    }

    fun clearOverride(context: Context, skinId: String) {
        prefs(context).edit().remove(KEY_OVERRIDE_PREFIX + skinId).apply()
    }

    // ===== 旧版偏好迁移 =====

    /** 旧 ThemeManager 偏好 → 内置皮肤 id 的一次性迁移（幂等）。 */
    private fun migrateLegacyIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_CURRENT)) return
        val legacy = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
        val legacyTheme = legacy.getString(LEGACY_KEY_THEME, null)
        val skinId = SkinDefaults.legacyThemeToSkinId(legacyTheme)
        p.edit().putString(KEY_CURRENT, skinId).apply()
        if (legacyTheme != null) {
            legacy.edit().clear().apply()
            Log.i(TAG, "旧主题偏好已迁移: $legacyTheme -> $skinId")
        }
    }

    // ===== 内部 =====

    private data class IndexEntry(
        val id: String,
        val name: String,
        val author: String?,
        val version: String
    )

    private fun readIndex(context: Context): List<IndexEntry> {
        val json = prefs(context).getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                IndexEntry(
                    id = id,
                    name = obj.optString("name", id),
                    author = obj.optString("author").takeIf { it.isNotEmpty() },
                    version = obj.optString("version", "1.0.0")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "皮肤索引损坏，尝试从磁盘扫描重建: ${e.message}")
            rebuildIndexFromDisk(context)
        }
    }

    /**
     * 索引损坏自愈：扫描 skins/ 下含合法 skin.json 的安装目录重建索引并回写。
     * 单个目录解析失败仅跳过该目录，不中断重建；仅在索引 JSON 损坏分支触发，
     * 正常路径零改动。
     */
    private fun rebuildIndexFromDisk(context: Context): List<IndexEntry> {
        val dirs = skinsRoot(context).listFiles { f ->
            f.isDirectory && !f.name.startsWith(".")
        } ?: emptyArray()
        val entries = dirs.mapNotNull { dir ->
            val file = dir.resolve("skin.json")
            if (!file.isFile) return@mapNotNull null
            try {
                val spec = SkinSpecCodec.decodeSpec(file.readText())
                // 目录名与规格 id 不一致视为非法安装，跳过
                if (spec.meta.id != dir.name) return@mapNotNull null
                IndexEntry(spec.meta.id, spec.meta.name, spec.meta.author, spec.meta.version)
            } catch (e: Exception) {
                Log.w(TAG, "索引重建跳过损坏目录: ${dir.name}, ${e.message}")
                null
            }
        }
        writeIndex(context, entries)
        Log.i(TAG, "皮肤索引已从磁盘重建，恢复 ${entries.size} 个皮肤")
        return entries
    }

    private fun writeIndex(context: Context, entries: List<IndexEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("name", entry.name)
                entry.author?.let { put("author", it) }
                put("version", entry.version)
            })
        }
        prefs(context).edit().putString(KEY_INDEX, array.toString()).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
