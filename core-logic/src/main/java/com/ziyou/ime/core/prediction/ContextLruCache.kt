package com.ziyou.ime.core.prediction

/**
 * 上下文 LRU 缓存：词序列 → LLM 候选列表（纯内存，零持久化）。
 *
 * 中文输入重复上下文极常见（同一句话反复试词、预测链点击），
 * 命中即免去一次网络请求；容量 [CAPACITY] 条，超出按最久未访问淘汰。
 *
 * 本类非线程安全：调用方（应用层协调器）保证只在主线程访问。
 */
class ContextLruCache {

    companion object {
        /** 缓存容量（条）：内存预算 < 100KB 内的经验值 */
        const val CAPACITY = 32

        /** key 连接符：不可打印字符，避免与词内容产生拼接歧义 */
        private const val KEY_SEPARATOR = "\u0001"
    }

    /** accessOrder=true：get/put 均刷新访问序，removeEldestEntry 据此淘汰最久未访问项 */
    private val map = object : LinkedHashMap<String, List<String>>(
        CAPACITY, 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > CAPACITY
    }

    /** 按词序列查询缓存候选；命中会刷新该项访问序（LinkedHashMap accessOrder 语义） */
    fun get(words: List<String>): List<String>? = map[keyOf(words)]

    /** 写入缓存（同 key 覆盖并刷新访问序） */
    fun put(words: List<String>, candidates: List<String>) {
        map[keyOf(words)] = candidates
    }

    /** 清空全部缓存（输入会话切换时与词窗口同步清理） */
    fun clear() = map.clear()

    /** 当前缓存条数 */
    fun size(): Int = map.size

    /** key = 词序列以不可打印分隔符拼接（顺序敏感，窗口为时间序） */
    private fun keyOf(words: List<String>): String = words.joinToString(KEY_SEPARATOR)
}
