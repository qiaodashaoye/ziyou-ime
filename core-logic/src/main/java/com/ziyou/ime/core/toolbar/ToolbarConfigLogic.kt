package com.ziyou.ime.core.toolbar

/**
 * 候选区功能栏配置的纯逻辑：清洗、排序与编解码。
 *
 * 功能栏按钮以字符串 id 标识（目录定义在 :app 的 ToolbarItem 中），
 * 本对象只处理 id 列表本身，不感知按钮的展示与功能码，可独立 JVM 单测。
 * 持久化格式为逗号分隔的 id 串（id 均为 ASCII 短词，无转义需求）。
 */
object ToolbarConfigLogic {

    private const val SEPARATOR = ","

    /**
     * 清洗配置：去重（保留首次出现的顺序）、剔除不在 [validIds] 目录中的未知 id
     * （如旧版本残留），清洗后为空时回退到 [fallback]，保证功能栏永不为空。
     */
    fun sanitize(ids: List<String>, validIds: Collection<String>, fallback: List<String>): List<String> {
        val cleaned = ids.filter { it in validIds }.distinct()
        return cleaned.ifEmpty { fallback }
    }

    /**
     * 将 [index] 位置的条目移动 [offset] 个位置（负数向前，正数向后）。
     * 目标位置越界时钳制到边界；index 非法时原样返回。
     */
    fun move(ids: List<String>, index: Int, offset: Int): List<String> {
        if (index !in ids.indices || offset == 0) return ids
        val target = (index + offset).coerceIn(0, ids.size - 1)
        if (target == index) return ids
        val result = ids.toMutableList()
        val item = result.removeAt(index)
        result.add(target, item)
        return result
    }

    /** 序列化为持久化字符串（逗号分隔）。 */
    fun encode(ids: List<String>): String = ids.joinToString(SEPARATOR)

    /** 从持久化字符串还原 id 列表；空串 / 纯空白返回空列表。 */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
