package com.ziyou.ime.data

import android.content.Context
import android.content.SharedPreferences
import com.ziyou.ime.core.toolbar.ToolbarConfigLogic

/**
 * 功能栏预设模板：一组按固定顺序排列的按钮 id。
 *
 * itemIds 为 ToolbarItem 的持久化 id（字符串），与按钮目录的一致性
 * 由 app 模块单元测试保证（见 ToolbarConfigTest）。
 */
data class ToolbarPreset(val name: String, val summary: String, val itemIds: List<String>)

/**
 * 候选区功能栏配置仓库。
 *
 * 与 [SideSymbolRepository] 同款轻量持久化（SharedPreferences），
 * 存储启用按钮的有序 id 列表（编解码委托 :core-logic 的 [ToolbarConfigLogic]）。
 * 功能栏视图通过 [registerListener] 观察配置变更实时刷新，
 * 设置页写入后无需重启输入法即可生效。
 */
object ToolbarConfigRepository {

    private const val PREF_NAME = "ziyou_toolbar"
    private const val KEY_ITEMS = "toolbar_items"

    /** 默认配置：仅三核心按钮（技能 / 粘贴板 / AI 问答；功能栏从右往左排列，
     *  视觉左起为 AI→粘贴板→技能）。设置与收起键盘为常驻固定按钮，不入配置；
     *  其余按钮（主题/方案/涂鸦/悬浮）由用户在设置页或键盘内工具面板编辑模式自行添加；
     *  已自定义配置的存量用户不受默认值变更影响（仅未配置时回退本列表） */
    val DEFAULT_IDS = listOf("skill", "clipboard", "ai")

    /** 全量按钮配置（「全功能」预设，历史默认顺序 + 新增按钮追加在后） */
    private val FULL_IDS = listOf(
        "theme", "schema", "doodle", "skill", "ai", "clipboard", "floating", "keyboard"
    )

    /** 预设模板（设置页「预设模板」入口展示；设置/收起键盘常驻，不在模板内） */
    val PRESETS = listOf(
        ToolbarPreset("核心", "问AI、粘贴板、技能（默认）", DEFAULT_IDS),
        ToolbarPreset("全功能", "全部功能按钮", FULL_IDS),
        ToolbarPreset(
            "创作", "偏重涂鸦与 AI 创作场景",
            listOf("doodle", "ai", "skill", "theme")
        ),
        ToolbarPreset(
            "效率", "偏重粘贴板、悬浮切换与快捷操作",
            listOf("clipboard", "floating", "theme")
        )
    )

    /** 读取启用按钮的有序 id 列表（原始存储值，未做目录清洗；未配置时返回默认） */
    fun getItemIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return DEFAULT_IDS
        return ToolbarConfigLogic.decode(raw).ifEmpty { DEFAULT_IDS }
    }

    /** 保存启用按钮的有序 id 列表 */
    fun setItemIds(context: Context, ids: List<String>) {
        prefs(context).edit().putString(KEY_ITEMS, ToolbarConfigLogic.encode(ids)).apply()
    }

    /** 恢复默认配置 */
    fun resetToDefault(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }

    /**
     * 注册配置变更监听（观察者模式：功能栏视图挂载时注册、脱离时注销）。
     * SharedPreferences 仅弱引用监听器，调用方须自行持有强引用。
     */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    /** 注销配置变更监听 */
    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
