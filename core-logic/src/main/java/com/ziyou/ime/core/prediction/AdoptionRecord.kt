package com.ziyou.ime.core.prediction

/**
 * 采纳词对攒批记录（联想优化方案 §4.6 形态 B：构建期固化的数据源）。
 *
 * 用户在预测态点选候选（引擎预测词或 LLM 续写词）时，记录
 * 「前文词 → 被采纳词」词对计数；数据**不参与任何运行时排序**，
 * 仅防抖落盘后由离线构建脚本（scripts/build_predict_db.py）合并进
 * 自建 predict.db 语料——个性化以数据迭代而非运行时管线的形式存在，
 * 与第 11 节简洁性决策兼容（ADR 见联想优化方案 §4.6）。
 *
 * 隐私口径（与已删除的 UserBigramModel 同一红线）：
 * - 仅记录 1~4 字纯汉字词对计数，标点/英文/数字/混合词一律过滤；
 * - 不含语句上下文、不进日志；仅存本机，可关闭、可清除。
 *
 * 本类非线程安全：调用方保证只在主线程访问（上屏/采纳出口均在主线程）。
 */
class AdoptionRecord {

    /** 单条词对：前文词（头词）与被采纳的后续词（尾词） */
    data class Pair(val prev: String, val next: String)

    companion object {
        /** 头词容量上限（防攒批文件无界增长；超限淘汰最旧头词） */
        const val MAX_HEADS = 500

        /** 每个头词保留的尾词上限（超限淘汰计数最小者，并列淘汰最旧） */
        const val MAX_TAILS_PER_HEAD = 8

        /** 可学习词判定：1~4 字纯汉字（基本区 + 扩展 A 区） */
        fun isLearnableWord(text: String): Boolean =
            text.length in 1..4 &&
                text.all { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
    }

    /** 头词 →（尾词 → 采纳次数）；LinkedHashMap 保插入序供容量淘汰 */
    private val pairs = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    /** 自上次 [drainSince] 以来是否有新记录（持久化层据此决定是否需要落盘） */
    private var dirty = false

    /**
     * 记录一次采纳。
     *
     * @param prev 前文词（词窗口末位汉字词；非法词静默忽略）
     * @param next 被采纳的候选词（非法词静默忽略；剥离标点后判定）
     */
    fun record(prev: String, next: String) {
        val p = prev.trim()
        val n = next.trim().filter { it.isLetterOrDigit() }
        if (!isLearnableWord(p) || !isLearnableWord(n)) return
        if (p == n) return
        val tails = pairs.getOrPut(p) { LinkedHashMap() }
        tails[n] = (tails[n] ?: 0) + 1
        evictTails(tails)
        evictHeads()
        dirty = true
    }

    /** 当前全部词对计数的不可变快照（prev → (next → count)） */
    fun snapshot(): Map<String, Map<String, Int>> =
        pairs.mapValues { it.value.toMap() }

    /**
     * 从持久化数据恢复（覆盖当前内容）。
     *
     * 恢复不计入脏标记（未产生新数据，无需立即回写）；非法条目
     * （超词长/含非汉字/计数非正）静默跳过，容忍旧版本脏数据。
     */
    fun restore(data: Map<String, Map<String, Int>>) {
        pairs.clear()
        for ((prev, tails) in data) {
            if (!isLearnableWord(prev)) continue
            val cleanTails = LinkedHashMap<String, Int>()
            for ((next, count) in tails) {
                if (isLearnableWord(next) && next != prev && count > 0) {
                    cleanTails[next] = count
                }
            }
            if (cleanTails.isNotEmpty()) {
                evictTails(cleanTails)
                pairs[prev] = cleanTails
            }
        }
        evictHeads()
        dirty = false
    }

    /**
     * 排空并返回自上次排空以来的全量快照（构建脚本导出用），
     * 随后清空内存态。导出即视为已消费，脏标记复位。
     */
    fun drainSince(): Map<String, Map<String, Int>> {
        val result = snapshot()
        pairs.clear()
        dirty = false
        return result
    }

    /** 清空全部记录（设置页「清除」入口） */
    fun clear() {
        pairs.clear()
        dirty = false
    }

    /** 自上次落盘/排空后是否有新增记录 */
    fun isDirty(): Boolean = dirty

    /** 词对总条数（头词 × 尾词） */
    fun size(): Int = pairs.values.sumOf { it.size }

    /** 尾词容量淘汰：计数最小者先出，并列时最旧者先出（保序遍历首中即最小） */
    private fun evictTails(tails: LinkedHashMap<String, Int>) {
        while (tails.size > MAX_TAILS_PER_HEAD) {
            val victim = tails.entries.minByOrNull { it.value }?.key ?: break
            tails.remove(victim)
        }
    }

    /** 头词容量淘汰：最旧插入者先出（LinkedHashMap 首项） */
    private fun evictHeads() {
        while (pairs.size > MAX_HEADS) {
            val victim = pairs.keys.firstOrNull() ?: break
            pairs.remove(victim)
        }
    }
}
