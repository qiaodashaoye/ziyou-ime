package com.ziyou.ime.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 九宫格拼音侧栏符号项。
 *
 * 参考 yuyansdk 的 `SideSymbol`（Room entity）设计：
 * - [display] 对应 yuyansdk 的 `symbolKey`：在侧栏中展示的文字。
 * - [value]   对应 yuyansdk 的 `symbolValue`：点击后实际上屏 / 输入的内容。
 *
 * 例如 `SideSymbol("，", "，")` 或 `SideSymbol("邮箱", "example@mail.com")`。
 */
data class SideSymbol(val display: String, val value: String)

/**
 * 拼音侧栏自定义符号仓库。
 *
 * ziyou-ime 未引入 Room 数据库，改用 [android.content.SharedPreferences] + JSON 持久化，
 * 在功能上等价于 yuyansdk 中的
 * `DataBaseKT.instance.sideSymbolDao().getAllSideSymbolPinyin()`。
 *
 * 侧栏在「无候选拼音」时展示这些符号，供用户一键上屏常用标点 / 短语。
 */
object SideSymbolRepository {

    private const val PREF_NAME = "ziyou_side_symbols"
    private const val KEY_PINYIN = "pinyin_symbols"

    /** 默认拼音侧栏符号（无候选拼音时展示，方便快速上屏常用标点） */
    private val DEFAULT_PINYIN = listOf(
        SideSymbol("，", "，"),
        SideSymbol("。", "。"),
        SideSymbol("？", "？"),
        SideSymbol("！", "！"),
        SideSymbol("、", "、"),
        SideSymbol("：", "："),
        SideSymbol("；", "；"),
        SideSymbol("…", "……")
    )

    /** 读取拼音侧栏符号；用户未自定义时回退到默认集合 */
    fun getPinyinSideSymbols(context: Context): List<SideSymbol> {
        val raw = prefs(context).getString(KEY_PINYIN, null) ?: return DEFAULT_PINYIN
        return parse(raw).ifEmpty { DEFAULT_PINYIN }
    }

    /** 添加一个侧栏符号（[SideSymbol.display] 相同则覆盖，避免重复） */
    fun addPinyinSideSymbol(context: Context, symbol: SideSymbol) {
        val list = getPinyinSideSymbols(context).toMutableList()
        list.removeAll { it.display == symbol.display }
        list.add(symbol)
        save(context, list)
    }

    /** 按显示文字删除侧栏符号 */
    fun removePinyinSideSymbol(context: Context, display: String) {
        val list = getPinyinSideSymbols(context).toMutableList()
        list.removeAll { it.display == display }
        save(context, list)
    }

    /** 恢复默认侧栏符号 */
    fun resetToDefault(context: Context) {
        prefs(context).edit().remove(KEY_PINYIN).apply()
    }

    // ===== 内部实现 =====

    private fun save(context: Context, list: List<SideSymbol>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("d", s.display).put("v", s.value))
        }
        prefs(context).edit().putString(KEY_PINYIN, arr.toString()).apply()
    }

    private fun parse(raw: String): List<SideSymbol> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.getString("d")
            SideSymbol(display, o.optString("v", display))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
