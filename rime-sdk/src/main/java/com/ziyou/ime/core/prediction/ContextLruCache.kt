package com.ziyou.ime.core.prediction

/**
 * 上下文 LRU 缓存：词序列 → LLM 候选列表。
 *
 * 中文输入重复上下文极常见（同一句话反复试词、预测链点击、口头禅与
 * 模板回复），命中即免去一次网络请求；容量 [CAPACITY] 条，超出按最久
 * 未访问淘汰。
 *
 * 持久化分工（联想优化方案 §4.3）：本类保持**纯内存**语义，跨会话持久化
 * 由应用层经 [snapshot]/[restore] 完成——启动时装载、脏变更防抖落盘；
 * 热路径（get/put）始终零磁盘 IO。
 *
 * 本类非线程安全：调用方（应用层协调器）保证只在主线程访问。
 */
class ContextLruCache {

    /** 可持久化快照条目：词窗口序列（时间序）与该上下文产出的候选列表 */
    data class Entry(val words: List<String>, val candidates: List<String>)

    companion object {
        /**
         * 缓存容量（条）：持久化扩容后的经验值（原 32）。
         * 内存预算：256 × (key ≤64 字符 + 候选 ≤5×20 字符) ≈ 数十 KB 量级；
         * 命中率是缓存层的第一指标——重复语境的长尾远超 32 条窗口。
         */
        const val CAPACITY = 256

        /** key 连接符：不可打印字符，避免与词内容产生拼接歧义 */
        private const val KEY_SEPARATOR = "\u0001"

        /** key 内部连接符对外暴露（持久化层反解词序列需要同一分隔语义） */
        fun keyOf(words: List<String>): String = words.joinToString(KEY_SEPARATOR)

        /** 反解持久化层的 key 为词序列（与 [keyOf] 互逆） */
        fun wordsOf(key: String): List<String> =
            if (key.isEmpty()) emptyList() else key.split(KEY_SEPARATOR)
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

    /** 清空全部缓存（仅清内存态；持久化层是否同步清理由调用方决定） */
    fun clear() = map.clear()

    /** 当前缓存条数 */
    fun size(): Int = map.size

    /**
     * 导出全量快照用于持久化。
     *
     * @return 按访问序排列的条目列表，**最久未访问在前、最近访问在后**；
     *         [restore] 按此顺序重放即可还原相同的 LRU 访问序
     */
    fun snapshot(): List<Entry> = map.entries.map { Entry(wordsOf(it.key), it.value) }

    /**
     * 从持久化快照恢复缓存（先清空再按序重放）。
     *
     * 重放顺序 = 快照顺序（最旧先入），LinkedHashMap accessOrder 语义下
     * 后重放者访问序更新，与快照时刻的 LRU 状态一致；超出容量的最旧项
     * 在重放过程中被自然淘汰。词序列或候选为空的脏条目静默跳过。
     *
     * @param entries [snapshot] 产出的条目序列（或其持久化往返等价物）
     */
    fun restore(entries: List<Entry>) {
        map.clear()
        for (entry in entries) {
            if (entry.words.isEmpty() || entry.candidates.isEmpty()) continue
            put(entry.words, entry.candidates)
        }
    }

    /**
     * 最近访问的 [n] 个词窗口序列（最近在前），供词窗口预热（联想优化
     * 方案 §4.4）按热度取高频上下文；不足 n 个时返回全部。
     */
    fun recentKeys(n: Int): List<List<String>> =
        map.keys.toList().asReversed().take(n).map { wordsOf(it) }
}
