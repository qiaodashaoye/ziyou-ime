package com.ziyou.ime.core.clipboard

/**
 * 粘贴板历史条目（纯数据，无 Android 依赖）。
 *
 * @param text 文本内容（入库前已截断至 [ClipboardHistoryLogic.MAX_TEXT_LENGTH]
 *             并剥离编码控制符）
 * @param timestamp 创建时间戳（毫秒），同时充当条目唯一标识（删除按此定位）
 */
data class ClipboardEntry(
    val text: String,
    val timestamp: Long
)

/**
 * 粘贴板历史纯逻辑（容量裁剪 / 去重 / 编解码 / 相对时间格式化）。
 *
 * 与 [com.ziyou.ime.core.toolbar.ToolbarConfigLogic] 同一定位：
 * 持久化编解码与列表操作规则下沉本模块，JVM 可测；
 * SharedPreferences 读写由 :app 的 ClipboardHistoryRepository 负责。
 *
 * 编码格式不用 JSON（:core-logic 为纯 Kotlin 库，JVM 单测无 org.json）：
 * 以 ASCII 控制符分隔（[RECORD_SEPARATOR] 分隔记录、[FIELD_SEPARATOR] 分隔字段），
 * 入库文本已剥离这两个控制符，格式无歧义、无需转义，
 * 天然支持含换行 / emoji 的任意剪贴文本。
 */
object ClipboardHistoryLogic {

    /** 历史容量上限：超出时裁掉最旧条目 */
    const val MAX_ENTRIES = 10

    /** 单条文本截断上限（字符数）：防超长粘贴撑爆 SharedPreferences */
    const val MAX_TEXT_LENGTH = 5000

    /** 记录分隔符（ASCII Record Separator） */
    private const val RECORD_SEPARATOR = '\u001E'

    /** 字段分隔符（ASCII Unit Separator） */
    private const val FIELD_SEPARATOR = '\u001F'

    /**
     * 头插新条目并返回新列表（原列表不变）：
     * - 空白文本拒收（返回原列表）；
     * - 文本先剥离控制符并截断至 [MAX_TEXT_LENGTH]；
     * - 与头条同文本视为同一次复制，返回原列表（重复捕获不刷时间戳、
     *   不触发落盘，兼容 onStartInputView 的高频兜底同步调用）；
     * - 非头条同文本去重：旧条目移除、新条目携新时间戳置顶；
     * - 超出 [MAX_ENTRIES] 时裁掉尾部最旧条目。
     */
    fun addEntry(entries: List<ClipboardEntry>, text: String, timestamp: Long): List<ClipboardEntry> {
        val sanitized = sanitize(text)
        if (sanitized.isBlank()) return entries
        if (entries.firstOrNull()?.text == sanitized) return entries
        val deduped = entries.filter { it.text != sanitized }
        return (listOf(ClipboardEntry(sanitized, timestamp)) + deduped).take(MAX_ENTRIES)
    }

    /** 按时间戳标识删除单条并返回新列表（无匹配时返回等值列表，幂等） */
    fun removeEntry(entries: List<ClipboardEntry>, timestamp: Long): List<ClipboardEntry> =
        entries.filter { it.timestamp != timestamp }

    /** 编码为单字符串持久化：`timestamp<US>text` 记录以 `<RS>` 相连 */
    fun encode(entries: List<ClipboardEntry>): String =
        entries.joinToString(RECORD_SEPARATOR.toString()) {
            "${it.timestamp}$FIELD_SEPARATOR${it.text}"
        }

    /**
     * 解码持久化字符串（容错：时间戳非法 / 字段缺失 / 文本为空的损坏记录
     * 逐条跳过，永不抛异常），并按容量上限裁剪。
     */
    fun decode(raw: String): List<ClipboardEntry> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .mapNotNull { record ->
                val sep = record.indexOf(FIELD_SEPARATOR)
                if (sep <= 0) return@mapNotNull null
                val timestamp = record.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val text = record.substring(sep + 1)
                if (text.isBlank()) return@mapNotNull null
                ClipboardEntry(text, timestamp)
            }
            .take(MAX_ENTRIES)
    }

    /**
     * 相对时间展示（面板条目时间标签）：
     * 刚刚（<1 分钟）→ N分钟前 → N小时前 → N天前；
     * 时钟回拨（timestamp 晚于 now）按「刚刚」兜底。
     */
    fun formatRelativeTime(timestamp: Long, now: Long): String {
        val elapsed = now - timestamp
        return when {
            elapsed < 60_000L -> "刚刚"
            elapsed < 3_600_000L -> "${elapsed / 60_000L}分钟前"
            elapsed < 86_400_000L -> "${elapsed / 3_600_000L}小时前"
            else -> "${elapsed / 86_400_000L}天前"
        }
    }

    /** 剥离编码控制符并截断超长文本（入库前统一清洗） */
    private fun sanitize(text: String): String {
        val cleaned = text.filterNot { it == RECORD_SEPARATOR || it == FIELD_SEPARATOR }
        return if (cleaned.length > MAX_TEXT_LENGTH) cleaned.substring(0, MAX_TEXT_LENGTH) else cleaned
    }
}
