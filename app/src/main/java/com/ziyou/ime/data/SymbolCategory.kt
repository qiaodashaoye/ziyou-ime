package com.ziyou.ime.data

import android.content.Context
import android.util.Log
import com.ziyou.ime.config.AssetDeployer
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 符号键盘分类。
 *
 * 对应主流中文输入法（搜狗/百度/讯飞）符号面板的左侧竖排分类导航项：
 * - [id]    分类唯一标识（持久化 / 数据源路由用）。
 * - [label] 在分类栏中展示的名称，如「常用」「数学」。
 */
data class SymbolCategory(val id: String, val label: String)

/**
 * 符号键盘数据仓库。
 *
 * 与 [SideSymbolRepository] 保持一致的设计风格（SharedPreferences + JSON 持久化，
 * 不引入 Room），为 [com.ziyou.ime.ime.SymbolKeyboardView] 提供分类与符号数据：
 *
 * 数据源分三类：
 * 1. **用户数据**（常用 / 最近）：SharedPreferences 持久化；「常用」支持用户自定义
 *    （键盘内长按收藏 / 设置页管理），「最近」按使用时间自动记录。
 * 2. **内置分类**（中文 / 英文 / 括号）：高频标点，代码内置保证零加载成本。
 * 3. **YAML 分类**（数学 / 序号 / 箭头等）：解析已部署的 Rime `symbols.yaml`
 *    （`punctuator/symbols` 段），与 Rime 生态符号数据同源；解析失败回退内置兜底集。
 *
 * 性能：YAML 仅在首次访问时整体解析一次并全量缓存（[preload] 可在后台线程预热），
 * 之后所有访问零 IO；文件本身仅 200 余行，解析耗时可忽略。
 */
object SymbolRepository {

    private const val TAG = "SymbolRepository"

    private const val PREF_NAME = "ziyou_symbols"
    private const val KEY_FAVORITES = "favorite_symbols"
    private const val KEY_RECENT = "recent_symbols"

    /** 「最近」分类最多记录条数 */
    private const val RECENT_LIMIT = 30

    /** 已部署 Rime 配置中的符号文件名（与 assets/rime/symbols.yaml 同源） */
    private const val SYMBOLS_YAML = "symbols.yaml"

    // ===== 分类定义 =====

    const val CATEGORY_FAVORITE = "favorite"
    const val CATEGORY_RECENT = "recent"

    /** 全部分类，顺序即符号键盘左侧分类栏的展示顺序 */
    private val CATEGORIES = listOf(
        SymbolCategory(CATEGORY_FAVORITE, "常用"),
        SymbolCategory(CATEGORY_RECENT, "最近"),
        SymbolCategory("cn", "中文"),
        SymbolCategory("en", "英文"),
        SymbolCategory("bracket", "括号"),
        SymbolCategory("math", "数学"),
        SymbolCategory("num", "序号"),
        SymbolCategory("arrow", "箭头"),
        SymbolCategory("currency", "货币"),
        SymbolCategory("star", "星号"),
        SymbolCategory("geometry", "几何"),
        SymbolCategory("unit", "单位")
    )

    /** YAML 分类 id → symbols.yaml `punctuator/symbols` 段的编码键 */
    private val YAML_KEYS = mapOf(
        "math" to "'/sx'",
        "num" to "'/szq'",
        "arrow" to "'/jt'",
        "currency" to "'/hb'",
        "star" to "'/xh'",
        "geometry" to "'/jh'",
        "unit" to "'/dw'"
    )

    // ===== 内置符号集 =====

    /** 「常用」默认符号（用户未自定义时展示，可在设置页 / 键盘内长按管理） */
    private val DEFAULT_FAVORITES = listOf(
        "，", "。", "？", "！", "、", "：", "；", "……",
        "～", "·", "—", "“", "”", "（", "）", "《", "》",
        "@", "#", "%", "&", "*", "/", "\\"
    )

    /** 中文标点（全角） */
    private val CN_SYMBOLS = listOf(
        "，", "。", "、", "？", "！", "：", "；", "…", "……",
        "“", "”", "‘", "’", "（", "）", "《", "》", "〈", "〉",
        "【", "】", "〔", "〕", "「", "」", "『", "』",
        "—", "——", "～", "·", "•", "﹏", "＿", "￣",
        "﹃", "﹄", "〖", "〗", "※", "§", "〇"
    )

    /** 英文标点（半角 ASCII） */
    private val EN_SYMBOLS = listOf(
        ",", ".", "?", "!", ":", ";", "'", "\"",
        "(", ")", "[", "]", "{", "}", "<", ">",
        "@", "#", "$", "%", "^", "&", "*", "-",
        "_", "+", "=", "/", "\\", "|", "~", "`"
    )

    /** 括号（中西混排常用配对括号） */
    private val BRACKET_SYMBOLS = listOf(
        "（", "）", "【", "】", "《", "》", "〈", "〉",
        "「", "」", "『", "』", "〔", "〕", "〖", "〗",
        "﹙", "﹚", "﹛", "﹜", "﹝", "﹞", "（）", "【】",
        "(", ")", "[", "]", "{", "}", "<", ">"
    )

    /** YAML 分类解析失败时的兜底符号集（取各分类高频子集） */
    private val YAML_FALLBACK = mapOf(
        "math" to listOf(
            "＋", "－", "×", "÷", "＝", "≠", "≈", "±", "＜", "＞",
            "≤", "≥", "√", "∞", "∑", "∏", "∈", "∩", "∪", "∫",
            "∠", "⊥", "∥", "∴", "∵", "°", "‰", "％"
        ),
        "num" to listOf(
            "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑴", "⑵", "⑶", "⑷", "⑸", "⒈", "⒉", "⒊", "⒋", "⒌",
            "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "㈠", "㈡", "㈢", "㈣", "㈤"
        ),
        "arrow" to listOf(
            "←", "→", "↑", "↓", "↔", "↕", "↖", "↗", "↘", "↙",
            "⇐", "⇒", "⇑", "⇓", "⇔", "➔", "➜", "➡", "↩", "↪"
        ),
        "currency" to listOf(
            "￥", "¥", "$", "＄", "€", "£", "￡", "¢", "￠", "₩", "₹", "₪"
        ),
        "star" to listOf(
            "★", "☆", "✦", "✧", "✩", "✪", "✫", "✬", "✭", "✮",
            "✯", "✰", "❋", "❊", "❉", "❈", "❇", "✿", "❀", "❁"
        ),
        "geometry" to listOf(
            "■", "□", "▲", "△", "▼", "▽", "◆", "◇", "●", "○",
            "◎", "◉", "◐", "◑", "▶", "◀", "▪", "▫", "◢", "◣"
        ),
        "unit" to listOf(
            "℃", "℉", "°", "％", "‰", "㎜", "㎝", "㎡", "㎥", "㎞",
            "㎏", "㎎", "㏄", "㎐", "㎾", "㏎", "㏑", "㏒", "Å", "‱"
        )
    )

    // ===== YAML 缓存 =====

    /** YAML 分类符号缓存（分类 id → 符号列表），首个访问者触发整体解析 */
    private val yamlCache = ConcurrentHashMap<String, List<String>>()

    /** YAML 是否已解析过（无论成败只解析一次，失败走兜底集） */
    @Volatile
    private var yamlParsed = false

    // ===== 公开API =====

    /** 全部符号分类（左侧分类栏数据源） */
    fun getCategories(): List<SymbolCategory> = CATEGORIES

    /** 读取指定分类的符号列表 */
    fun getSymbols(context: Context, categoryId: String): List<String> = when (categoryId) {
        CATEGORY_FAVORITE -> getFavorites(context)
        CATEGORY_RECENT -> getRecent(context)
        "cn" -> CN_SYMBOLS
        "en" -> EN_SYMBOLS
        "bracket" -> BRACKET_SYMBOLS
        else -> getYamlSymbols(context, categoryId)
    }

    /**
     * 预热 YAML 分类缓存（建议在后台线程调用，如 Service onCreate 时），
     * 使键盘内首次切到数学 / 序号等分类时零 IO 无卡顿。
     */
    fun preload(context: Context) {
        parseYamlIfNeeded(context)
    }

    // ===== 常用符号（用户自定义） =====

    /** 读取「常用」符号；用户未自定义时回退默认集合 */
    fun getFavorites(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return DEFAULT_FAVORITES
        return parseJsonList(raw).ifEmpty { DEFAULT_FAVORITES }
    }

    /** 添加常用符号（已存在则移到末尾，避免重复） */
    fun addFavorite(context: Context, symbol: String) {
        if (symbol.isEmpty()) return
        val list = getFavorites(context).toMutableList()
        list.remove(symbol)
        list.add(symbol)
        saveJsonList(context, KEY_FAVORITES, list)
    }

    /** 移除常用符号 */
    fun removeFavorite(context: Context, symbol: String) {
        val list = getFavorites(context).toMutableList()
        if (list.remove(symbol)) {
            saveJsonList(context, KEY_FAVORITES, list)
        }
    }

    /** 是否已在常用中 */
    fun isFavorite(context: Context, symbol: String): Boolean =
        getFavorites(context).contains(symbol)

    /** 恢复默认常用符号 */
    fun resetFavorites(context: Context) {
        prefs(context).edit().remove(KEY_FAVORITES).apply()
    }

    // ===== 最近使用（自动记录） =====

    /** 读取「最近」符号（按使用时间倒序） */
    fun getRecent(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return parseJsonList(raw)
    }

    /** 记录一次符号使用：移到最前并截断到上限 */
    fun recordRecent(context: Context, symbol: String) {
        if (symbol.isEmpty()) return
        val list = getRecent(context).toMutableList()
        list.remove(symbol)
        list.add(0, symbol)
        while (list.size > RECENT_LIMIT) {
            list.removeAt(list.size - 1)
        }
        saveJsonList(context, KEY_RECENT, list)
    }

    // ===== YAML 解析 =====

    private fun getYamlSymbols(context: Context, categoryId: String): List<String> {
        if (!YAML_KEYS.containsKey(categoryId)) return emptyList()
        parseYamlIfNeeded(context)
        return yamlCache[categoryId] ?: YAML_FALLBACK[categoryId] ?: emptyList()
    }

    /**
     * 解析 symbols.yaml 的 `punctuator/symbols` 段并填充全部 YAML 分类缓存。
     * 优先读取已部署目录（用户可能通过重新部署更新过配置），缺失时回退 assets 原始文件；
     * 单个分类解析失败不影响其他分类，整体失败由 [YAML_FALLBACK] 兜底。
     */
    @Synchronized
    private fun parseYamlIfNeeded(context: Context) {
        if (yamlParsed) return
        try {
            val text = readSymbolsYaml(context)
            if (text != null) {
                for ((categoryId, yamlKey) in YAML_KEYS) {
                    val symbols = extractYamlList(text, yamlKey)
                    if (symbols.isNotEmpty()) {
                        yamlCache[categoryId] = symbols
                    }
                }
                Log.i(TAG, "symbols.yaml 解析完成: ${yamlCache.size}/${YAML_KEYS.size} 个分类")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 symbols.yaml 失败，使用内置兜底符号集: ${e.message}")
        }
        yamlParsed = true
    }

    /** 读取 symbols.yaml 内容：已部署目录优先，回退 assets */
    private fun readSymbolsYaml(context: Context): String? {
        val deployed = File(AssetDeployer.getSharedDataDir(context), SYMBOLS_YAML)
        if (deployed.exists()) {
            return deployed.readText()
        }
        return try {
            context.assets.open("rime/$SYMBOLS_YAML").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "assets 中也未找到 $SYMBOLS_YAML: ${e.message}")
            null
        }
    }

    /**
     * 从 YAML 文本中提取形如 `'/sx': [ a, b, c ]` 的单行列表。
     * symbols 段的条目均为单行数组写法，逐行匹配即可，无需完整 YAML 解析器。
     */
    private fun extractYamlList(text: String, yamlKey: String): List<String> {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("$yamlKey:")) continue
            val start = trimmed.indexOf('[')
            val end = trimmed.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            return trimmed.substring(start + 1, end)
                .split(',')
                .map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    // ===== 内部实现 =====

    private fun saveJsonList(context: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(s)
        }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    private fun parseJsonList(raw: String): List<String> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> arr.getString(i) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
