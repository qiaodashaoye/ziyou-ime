package com.ziyou.ime.data

import android.content.Context
import com.ziyou.ime.core.clipboard.ClipboardEntry
import com.ziyou.ime.core.clipboard.ClipboardHistoryLogic

/**
 * 粘贴板历史仓库。
 *
 * 与 [ToolbarConfigRepository] / [SideSymbolRepository] 同款轻量持久化
 * （SharedPreferences 单键存储，编解码与列表规则委托 :core-logic 的
 * [ClipboardHistoryLogic]），并叠加内存缓存：读路径 O(1) 命中缓存，
 * 仅冷启动首次读盘解码；写路径先更新缓存再 apply() 异步落盘，
 * 不阻塞主线程、不触碰输入热路径。
 *
 * 线程约定：所有方法在主线程调用（剪贴板监听回调与面板点击均在主线程），
 * 缓存无并发写；@Volatile 仅保证极端时序下的可见性兜底。
 */
object ClipboardHistoryRepository {

    private const val PREF_NAME = "ziyou_clipboard"
    private const val KEY_ENTRIES = "clipboard_entries"

    /** 内存缓存（单一热数据源）：null 表示尚未从 SharedPreferences 加载 */
    @Volatile
    private var cache: List<ClipboardEntry>? = null

    /** 读取历史列表（最新在前；缓存命中零 IO） */
    fun getEntries(context: Context): List<ClipboardEntry> {
        cache?.let { return it }
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: ""
        return ClipboardHistoryLogic.decode(raw).also { cache = it }
    }

    /**
     * 头插新条目（去重/截断/容量裁剪见 [ClipboardHistoryLogic.addEntry]）。
     * @return 列表是否发生变化（空白文本拒收时返回 false，跳过落盘）
     */
    fun addEntry(context: Context, text: String): Boolean {
        val current = getEntries(context)
        val updated = ClipboardHistoryLogic.addEntry(current, text, System.currentTimeMillis())
        if (updated == current) return false
        persist(context, updated)
        return true
    }

    /** 按时间戳标识删除单条（幂等） */
    fun removeEntry(context: Context, timestamp: Long) {
        val current = getEntries(context)
        val updated = ClipboardHistoryLogic.removeEntry(current, timestamp)
        if (updated != current) persist(context, updated)
    }

    /** 清空全部历史 */
    fun clearAll(context: Context) {
        cache = emptyList()
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    /** 更新缓存并异步落盘 */
    private fun persist(context: Context, entries: List<ClipboardEntry>) {
        cache = entries
        prefs(context).edit()
            .putString(KEY_ENTRIES, ClipboardHistoryLogic.encode(entries))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
